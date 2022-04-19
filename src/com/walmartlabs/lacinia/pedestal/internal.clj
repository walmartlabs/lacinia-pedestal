; Copyright (c) 2020-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns ^:no-doc com.walmartlabs.lacinia.pedestal.internal
  "Internal utilities not part of the public API."
  (:require
    [clojure.core.async :refer [chan put!]]
    [cheshire.core :as cheshire]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.parser :as parser]
    [com.walmartlabs.lacinia.pedestal.cache :as cache]
    [clojure.string :as str]
    [com.walmartlabs.lacinia.validator :as validator]
    [com.walmartlabs.lacinia.constants :as constants]
    [com.walmartlabs.lacinia.resolve :as resolve]
    [com.walmartlabs.lacinia.executor :as executor]
    [io.pedestal.log :as log]
    [clojure.java.io :as io]
    [ring.util.response :as response]
    [io.pedestal.http.jetty.websockets :as ws]
    [io.pedestal.http :as http]
    [io.pedestal.interceptor.chain :as chain]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]
    [com.walmartlabs.lacinia.tracing :as tracing]))

(def ^:private parsed-query-key-path [:request :parsed-lacinia-query])

(defn parse-content-type
  "Parse `s` as an RFC 2616 media type."
  [s]
  (if-let [[_ type _ _ raw-params] (re-matches #"\s*(([^/]+)/([^ ;]+))\s*(\s*;.*)?" (str s))]
    {:content-type (keyword type)
     :content-type-params
     (->> (str/split (str raw-params) #"\s*;\s*")
          (keep identity)
          (remove str/blank?)
          (map #(str/split % #"="))
          (mapcat (fn [[k v]] [(keyword (str/lower-case k)) (str/trim v)]))
          (apply hash-map))}))

(defn content-type
  "Gets the content-type of a request. (without encoding)"
  [request]
  (when-let [content-type (get-in request [:headers "content-type"])]
    (:content-type (parse-content-type content-type))))

(defn on-leave-json-response
  [context]
  (let [body (get-in context [:response :body])]
    (if (map? body)
      (-> context
          (assoc-in [:response :headers "Content-Type"] "application/json")
          (update-in [:response :body] cheshire/generate-string))
      context)))

(defn failure-response
  "Generates a bad request Ring response."
  ([body]
   (failure-response 400 body))
  ([status body]
   {:status status
    :headers {}
    :body body}))

(defn message-as-errors
  [message]
  {:errors [{:message message}]})

(defn as-errors
  [exception]
  {:errors [(util/as-error-map exception)]})

(defn on-enter-query-parser
  [context compiled-schema cache timing-start]
  (let [{:keys [graphql-query graphql-operation-name]} (:request context)
        cache-key (when cache
                    (if graphql-operation-name
                      [graphql-query graphql-operation-name]
                      graphql-query))
        cached (cache/get-parsed-query cache cache-key)]
    (if cached
      (assoc-in context parsed-query-key-path cached)
      (try
        (let [actual-schema (if (map? compiled-schema)
                              compiled-schema
                              (compiled-schema))
              parsed-query (parser/parse-query actual-schema graphql-query graphql-operation-name timing-start)]
          (->> parsed-query
               (cache/store-parsed-query cache cache-key)
               (assoc-in context parsed-query-key-path)))
        (catch Exception e
          (assoc context :response
                 (failure-response (as-errors e))))))))

(defn on-leave-query-parser
  [context]
  (update context :request dissoc :parsed-lacinia-query))

(defn add-error
  [context exception]
  (assoc context ::chain/error exception))

(defn on-error-query-parser
  [context exception]
  (-> (on-leave-query-parser context)
      (add-error exception)))

(defn on-error-error-response
  [context ex]
  (let [{:keys [exception]} (ex-data ex)]
    (assoc context :response (failure-response 500 (as-errors exception)))))

(defn on-enter-prepare-query
  [context]
  (try
    (let [{parsed-query :parsed-lacinia-query
           vars :graphql-vars} (:request context)
          {:keys [::tracing/timing-start]} parsed-query
          start-offset (tracing/offset-from-start timing-start)
          start-nanos (System/nanoTime)
          prepared (parser/prepare-with-query-variables parsed-query vars)
          compiled-schema (get prepared constants/schema-key)
          errors (validator/validate compiled-schema prepared {})
          prepared' (assoc prepared ::tracing/validation {:start-offset start-offset
                                                          :duration (tracing/duration start-nanos)})]
      (if (seq errors)
        (assoc context :response (failure-response {:errors errors}))
        (assoc-in context parsed-query-key-path prepared')))
    (catch Exception e
      (assoc context :response
             (failure-response (as-errors e))))))


(defn ^:private remove-status
  "Remove the :status key from the :extensions map; remove the :extensions key if that is now empty."
  [error-map]
  (if-not (contains? error-map :extensions)
    error-map
    (let [error-map' (update error-map :extensions dissoc :status)]
      (if (-> error-map' :extensions seq)
        error-map'
        (dissoc error-map' :extensions)))))

(defn on-leave-status-conversion
  [context]
  (let [response (:response context)
        errors (get-in response [:body :errors])
        statuses (keep #(-> % :extensions :status) errors)]
    (if (seq statuses)
      (let [max-status (reduce max (:status response) statuses)]
        (-> context
            (assoc-in [:response :status] max-status)
            (assoc-in [:response :body :errors]
                      (map remove-status errors))))
      context)))

(defn ^:private apply-exception-to-context
  "Applies exception to context in the same way Pedestal would if thrown from a synchronous interceptor.

  Based on the (private) `io.pedestal.interceptor.chain/throwable->ex-info` function of pedestal"
  [{::chain/keys [execution-id] :as context} exception interceptor-name]
  (let [exception-str (pr-str (type exception))
        msg (str exception-str " in Interceptor " interceptor-name " - " (ex-message exception))
        wrapped-exception (ex-info msg
                                   (merge {:execution-id execution-id
                                           :stage :enter
                                           :interceptor interceptor-name
                                           :exception-type (keyword exception-str)
                                           :exception exception}
                                          (ex-data exception))
                                   exception)]
    (assoc context ::chain/error wrapped-exception)))

(defn ^:private apply-result-to-context
  [context result interceptor-name]
  ;; Lacinia changed the contract here in 0.36.0 (to support timeouts); the result
  ;; may be an exception thrown during initial processing of the query.
  (if (instance? Throwable result)
    (do
      (log/error :event :execution-exception
                 :ex result)
      ;; Put error in the context map for error interceptors to consume
      ;; If unhandled, will end up in [[error-response-interceptor]]
      (apply-exception-to-context context result interceptor-name))

    ;; When :data is missing, then a failure occurred during parsing or preparing
    ;; the request, which indicates a bad request, rather than some failure
    ;; during execution.
    (let [status (if (contains? result :data)
                   200
                   400)
          response {:status status
                    :headers {}
                    :body result}]
      (assoc context :response response))))

(defn ^:private execute-query
  [context]
  (let [request (:request context)
        {q :parsed-lacinia-query
         app-context :lacinia-app-context} request]
    (executor/execute-query (assoc app-context
                                   constants/parsed-query-key q))))

(defn on-enter-query-executor
  [interceptor-name]
  (fn [context]
    (let [resolver-result (execute-query context)
          *result (promise)]
      (resolve/on-deliver! resolver-result
                           (fn [result]
                             (deliver *result result)))
      (apply-result-to-context context @*result interceptor-name))))

(defn on-enter-async-query-executor
  [interceptor-name]
  (fn [context]
    (let [ch (chan 1)
          resolver-result (execute-query context)]
      (resolve/on-deliver! resolver-result
                           (fn [result]
                             (put! ch (apply-result-to-context context result interceptor-name))))
      ch)))

(defn on-enter-disallow-subscriptions
  [context]
  (if (-> context :request :parsed-lacinia-query parser/operations :type (= :subscription))
    (assoc context :response (failure-response (message-as-errors "Subscription queries must be processed by the WebSockets endpoint.")))
    context))

(defn ^:private request-headers-string
  [headers]
  (str "{"
       (->> headers
            (map (fn [[k v]]
                   (str \" (name k) "\": \"" (name v) \")))
            (str/join ", "))
       "}"))

(defn graphiql-response
  [api-path subscriptions-path asset-path ide-headers ide-connection-params ide-use-legacy-ws-client]
  (let [replacements {:asset-path asset-path
                      :api-path api-path
                      :subscriptions-path subscriptions-path
                      :initial-connection-params (cheshire/generate-string ide-connection-params)
                      :request-headers (request-headers-string ide-headers)
                      :use-legacy-ws-client (str ide-use-legacy-ws-client)}]
    (-> "com/walmartlabs/lacinia/pedestal/graphiql.html"
        io/resource
        slurp
        (str/replace #"\{\{(.+?)}}" (fn [[_ key]]
                                      (get replacements (keyword key) "--NO-MATCH--")))
        response/response
        (response/content-type "text/html"))))

(defn add-subscriptions-support
  [service-map compiled-schema subscriptions-path subscription-options]
  (assoc-in service-map
            [::http/container-options :context-configurator]
            ;; The listener-fn is responsible for creating the listener; it is passed
            ;; the request, response, and the ws-map. In sample code, the ws-map
            ;; has callbacks such as :on-connect and :on-text, but in our scenario
            ;; the callbacks are created by the listener-fn, so the value is nil.
            #(ws/add-ws-endpoints % {subscriptions-path nil}
                                  {:listener-fn
                                   (subscriptions/listener-fn-factory compiled-schema subscription-options)})))
