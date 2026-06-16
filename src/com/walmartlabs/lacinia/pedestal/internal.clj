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
  (:require [com.walmartlabs.lacinia :as lacinia]
            [clojure.core.async :as async
             :refer [chan put! close! go-loop <! >! alt! thread]]
            [io.pedestal.json :as json]
            [io.pedestal.http.response :as http-response]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.parser :as parser]
            [com.walmartlabs.lacinia.pedestal.cache :as cache]
            [clojure.string :as string]
            [com.walmartlabs.lacinia.validator :as validator]
            [com.walmartlabs.lacinia.constants :as constants]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [com.walmartlabs.lacinia.executor :as executor]
            [com.walmartlabs.lacinia.internal-utils :refer [to-message]]
            [io.pedestal.log :as log]
            [clojure.java.io :as io]
            [better-cond.core :as b]
            [ring.util.response :as response]
            [io.pedestal.interceptor.chain :as chain]
            [com.walmartlabs.lacinia.tracing :as tracing])
  (:import (java.io ByteArrayOutputStream StringReader)))

(def ^:private parsed-query-key-path [:request :parsed-lacinia-query])

(defn ^:private write-json-str
  "Encodes value as a JSON string using the Pedestal JSON abstraction."
  [value]
  (let [out (ByteArrayOutputStream.)]
    (json/stream-json value out)
    (.toString out "UTF-8")))

(defn ^:private parse-json
  "Parses a JSON string into Clojure data with keyword keys."
  [s]
  (json/read-json (StringReader. s) {:key-fn keyword}))

(defn parse-content-type
  "Parse `s` as an RFC 2616 media type."
  [s]
  (when-let [[_ type _ _ raw-params] (re-matches #"\s*(([^/]+)/([^ ;]+))\s*(\s*;.*)?" (str s))]
    {:content-type (keyword type)
     :content-type-params
     (->> (string/split (str raw-params) #"\s*;\s*")
          (keep identity)
          (remove string/blank?)
          (map #(string/split % #"="))
          (mapcat (fn [[k v]] [(keyword (string/lower-case k)) (string/trim v)]))
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
          (assoc-in [:response :body] (http-response/stream-json body)))
      context)))

(defn failure-response
  "Generates a bad request Ring response."
  ([body]
   (failure-response 400 body))
  ([status body]
   {:status  status
    :headers {}
    :body    body}))

(defn message-as-errors
  [message]
  {:errors [{:message message}]})

(defn ^:private as-errors
  [exception]
  {:errors [(util/as-error-map exception)]})

(defn on-enter-query-parser
  [context compiled-schema cache timing-start]
  (let [{:keys [graphql-query graphql-operation-name]} (:request context)
        cache-key (when cache
                    (if graphql-operation-name
                      [graphql-query graphql-operation-name]
                      graphql-query))
        cached    (cache/get-parsed-query cache cache-key)]
    (if cached
      (assoc-in context parsed-query-key-path cached)
      (try
        (let [actual-schema (if (map? compiled-schema)
                              compiled-schema
                              (compiled-schema))
              parsed-query  (parser/parse-query actual-schema graphql-query graphql-operation-name timing-start)]
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
           vars         :graphql-vars} (:request context)
          {:keys [::tracing/timing-start]} parsed-query
          start-offset    (tracing/offset-from-start timing-start)
          start-nanos     (System/nanoTime)
          prepared        (parser/prepare-with-query-variables parsed-query vars)
          compiled-schema (get prepared constants/schema-key)
          errors          (validator/validate compiled-schema prepared {})
          prepared'       (assoc prepared ::tracing/validation {:start-offset start-offset
                                                                :duration     (tracing/duration start-nanos)})]
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
        errors   (get-in response [:body :errors])
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
  (let [exception-str     (pr-str (type exception))
        msg               (str exception-str " in Interceptor " interceptor-name " - " (ex-message exception))
        wrapped-exception (ex-info msg
                                   (merge {:execution-id   execution-id
                                           :stage          :enter
                                           :interceptor    interceptor-name
                                           :exception-type (keyword exception-str)
                                           :exception      exception}
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
    (let [status   (if (contains? result :data)
                     200
                     400)
          response {:status  status
                    :headers {}
                    :body    result}]
      (assoc context :response response))))

(defn ^:private execute-query
  [context]
  (let [request (:request context)
        {q           :parsed-lacinia-query
         app-context :lacinia-app-context} request]
    (executor/execute-query (assoc app-context
                                   constants/parsed-query-key q))))

(defn on-enter-query-executor
  [interceptor-name]
  (fn [context]
    (let [resolver-result (execute-query context)
          *result         (promise)]
      (resolve/on-deliver! resolver-result
                           (fn [result]
                             (deliver *result result)))
      (apply-result-to-context context @*result interceptor-name))))

(defn on-enter-async-query-executor
  [interceptor-name]
  (fn [context]
    (let [ch              (chan 1)
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
            (string/join ", "))
       "}"))

(defn graphiql-response
  [api-path subscriptions-path asset-path ide-headers ide-connection-params]
  (let [replacements {:asset-path                asset-path
                      :api-path                  api-path
                      :subscriptions-path        subscriptions-path
                      :initial-connection-params (write-json-str ide-connection-params)
                      :request-headers           (request-headers-string ide-headers)}]
    (-> "com/walmartlabs/lacinia/pedestal/graphiql.html"
        io/resource
        slurp
        (string/replace #"\{\{(.+?)}}" (fn [[_ key]]
                                         (get replacements (keyword key) "--NO-MATCH--")))
        response/response
        (response/content-type "text/html"))))

(defn clear-graphql-data
  [context]
  (update context :request dissoc :graphql-query :graphql-vars :graphql-operation-name))

(defn enter-graphql-data
  [request-key]
  (fn [context]
    (let [payload (-> context
                      (get-in [:request request-key])
                      parse-json)
          {:keys          [query variables]
           operation-name :operationName} payload]
      (update context :request
              assoc
              :graphql-query query
              :graphql-vars variables
              :graphql-operation-name operation-name))))

(defn error-graphql-data
  [context exception]
  (-> (clear-graphql-data context)
      (add-error exception)))

(defn enter-missing-query
  [context]
  (if (-> context :request :graphql-query string/blank?)
    (assoc context :response
           (failure-response "JSON 'query' key is missing or blank"))
    context))

(defn enter-initialize-tracing
  [request-key]
  (fn [context]
    ;; Without this, the tracing during parsing doesn't know when the request actually started
    ;; and assumes no real time has passed, which is less accurate. Capturing the timing start early
    ;; ensures that time spent parsing the request body or doing other work before we get to parsing
    ;; is properly accounted for.
    (assoc-in context [:request request-key] (tracing/create-timing-start))))

(defn enter-enable-tracing
  [context]
  ;; Must come after the app context is added to the request.
  (let [request  (:request context)
        enabled? (get-in request [:headers "lacinia-tracing"])]
    (cond-> context
      enabled? (update-in [:request :lacinia-app-context] tracing/enable-tracing))))

(defn enter-body-data
  [request-key]
  (fn [context]
    (let [content-type (-> context :request content-type)]
      (if (= content-type :application/json)
        (assoc-in context [:request request-key]
                  (-> context :request :body slurp))
        (assoc context :response (failure-response "Must be application/json"))))))

;; Subscriptions

(defn ^:private xform-channel
  [input-ch output-ch xf]
  (go-loop []
    (if-some [input (<! input-ch)]
      (let [output (xf input)]
        (when (>! output-ch output)
          (recur)))
      (close! output-ch))))

(defn response-encode-loop
  "Takes values from the input channel, encodes them as a JSON string, and
  puts them into the output-ch."
  [input-ch output-ch]
  (xform-channel input-ch output-ch write-json-str))

(defn ws-parse-loop
  "Parses text messages sent from the client into Clojure data with keyword keys,
  which is passed along to the output-ch.

  Parse errors are converted into connection_error messages sent to the response-ch."
  [session-id input-ch output-ch response-data-ch]
  (go-loop []
    (when-some [text (<! input-ch)]
      (when-some [parsed (try
                           (parse-json text)
                           (catch Throwable t
                             (log/trace :event ::malformed-text :message text :session-id session-id)
                             (>! response-data-ch
                                 {:type    :connection_error
                                  :payload (util/as-error-map t)})))]
        (>! output-ch parsed))
      (recur))))

(defn ^:private execute-query-interceptors
  "Executes the interceptor chain for an operation, and returns
  a channel used to shutdown and cleanup the operation."
  [id payload response-data-ch cleanup-ch context]
  (let [shutdown-ch     (chan)
        response-spy-ch (chan 1)
        request         (assoc payload
                               :id id
                               :shutdown-ch shutdown-ch
                               :response-data-ch response-spy-ch)]
    ;; When the spy channel is closed, we write the id
    ;; to the cleanup-ch; the containing CSP then removes the
    ;; shutdown-ch from its subs map.
    (go-loop []
      (let [message (<! response-spy-ch)]
        (if (some? message)
          (do
            (>! response-data-ch message)
            (recur))
          (>! cleanup-ch id))))

    ;; Execute the chain, for side effects.
    (chain/execute (update context :request merge request))

    ;; Return a shutdown channel that the CSP can close to shutdown the subscription
    shutdown-ch))

(defn connection-loop
  "A loop started for each connection."
  [session-id keep-alive-ms ws-data-ch response-data-ch context]
  (let [cleanup-ch (chan 1)]
    ;; Keep track of subscriptions by (client-supplied) unique id.
    ;; The value is a shutdown channel that, when closed, triggers
    ;; a cleanup of the subscription.
    (go-loop [connection-state {:subs {} :connection-params nil}]
      (alt!
        cleanup-ch
        ([id]
         (log/trace :event ::cleanup-ch :session-id session-id :id id)
         (recur (update connection-state :subs dissoc id)))

        ;; TODO: Maybe only after connection_init?
        (async/timeout keep-alive-ms)
        (do
          (log/trace :event ::timeout :session-id session-id)
          ;; Subprotocol-aware keep-alive: graphql-transport-ws has no "ka" message (an
          ;; unknown type closes the connection on clients like Apollo/GraphiQL); its heartbeat
          ;; is a "ping" (the client replies "pong"). Legacy graphql-ws uses "ka".
          (>! response-data-ch (if (= "graphql-transport-ws" (::subprotocol context))
                                 {:type :ping}
                                 {:type :ka}))
          (recur connection-state))

        ws-data-ch
        ([data]
         (if (nil? data)
           ;; When the client closes the connection, any running subscriptions need to
           ;; shutdown and cleanup.
           (do
             (log/trace :event ::client-close :session-id session-id)
             (run! close! (-> connection-state :subs vals)))
           ;; Otherwise it's a message from the client to be acted upon.
           (let [{:keys [id payload type]} data]
             (case type
               "connection_init"
               (when (>! response-data-ch {:type :connection_ack})
                 (recur (assoc connection-state :connection-params payload)))

               ;; graphql-transport-ws keep-alive: the client may send a "ping" at any time,
               ;; and the server must reply with a "pong".
               "ping"
               (when (>! response-data-ch {:type :pong})
                 (recur connection-state))

               ;; The client's reply to our keep-alive "ping" (or an unsolicited heartbeat).
               ;; A "pong" requires no response; just keep listening.
               "pong"
               (recur connection-state)

               ;; TODO: Track state, don't allow start, etc. until after connection_init

               ;; "start" is the legacy graphql-ws verb; "subscribe" is its graphql-transport-ws
               ;; equivalent.
               ("start" "subscribe")
               (if (contains? (:subs connection-state) id)
                 (do
                   (log/trace :event ::ignoring-duplicate :id id)
                   (recur connection-state))
                 (do
                   (log/trace :event ::start :session-id session-id :id id)
                   (let [merged-context  (assoc context :connection-params (:connection-params connection-state))
                         sub-shutdown-ch (execute-query-interceptors id payload response-data-ch cleanup-ch merged-context)]
                     (recur (assoc-in connection-state [:subs id] sub-shutdown-ch)))))

               ;; "stop" is the legacy graphql-ws verb; "complete" is its graphql-transport-ws
               ;; equivalent.
               ("stop" "complete")
               (do
                 (log/trace :event ::stop :id id)
                 (when-some [sub-shutdown-ch (get-in connection-state [:subs id])]
                   (close! sub-shutdown-ch))
                 (recur connection-state))

               "connection_terminate"
               (do
                 (log/trace :event ::terminate :id id)
                 (run! close! (-> connection-state :subs vals))
                 ;; This shuts down the connection entirely.
                 (close! response-data-ch))

               ;; Not recognized!
               (let [response (cond-> {:type    :error
                                       :payload {:message "Unrecognized message type."
                                                 :type    type}}
                                id (assoc :id id))]
                 (log/trace :event ::unknown-type :type type :session-id session-id :id id)
                 (>! response-data-ch response)
                 (recur connection-state))))))))))

;; We try to keep the interceptors here and in the main namespace as similar as possible, but
;; there are distinctions that can't be readily smoothed over.

(defn ^:private fix-up-message
  [s]
  (when-not (string/blank? s)
    (-> s
        string/trim
        (string/replace #"\s*\.+$" "")
        string/capitalize)))

(defn ^:private ex-data-seq
  "Uses the exception root causes to build a sequence of non-nil ex-data from each
  exception in the exception stack."
  [t]
  (loop [stack   []
         current ^Throwable t]
    (let [stack' (conj stack current)
          next-t (.getCause current)]
      ;; Sometime .getCause returns this, sometimes nil, when the end of the stack is
      ;; reached.
      (if (or (nil? next-t)
              (= current next-t))
        (keep ex-data stack')
        (recur stack' next-t)))))

(defn ^:private construct-exception-payload
  [^Throwable t]
  (b/cond
    :let [errors (->> t
                      ex-data-seq
                      (keep ::errors)
                      first)
          parse-errors (->> errors
                            (keep :message)
                            distinct)
          locations (->> (mapcat :locations errors)
                         (remove nil?)
                         distinct
                         seq)]

    (seq parse-errors)
    (cond-> {:message (str "Failed to parse GraphQL query. "
                           (->> parse-errors
                                (keep fix-up-message)
                                (string/join "; "))
                           ".")}
      locations (assoc :locations locations))

    ;; Apollo spec only has room for one error, so just use the first

    (seq errors)
    (cond-> (first errors)
      locations (assoc :locations locations))

    :else
    ;; Strip off the exception added by Pedestal and convert
    ;; the message into an error map
    (cond-> {:message (to-message t)}
      locations (assoc :locations locations))))

(defn error-exception-handler
  [context ^Throwable t]
  (let [{:keys [id response-data-ch]} (:request context)
        ;; Strip off the wrapper exception added by Pedestal
        payload (construct-exception-payload (.getCause t))]
    (put! response-data-ch {:type    :error
                            :id      id
                            :payload payload})
    (close! response-data-ch)))

(defn protocol-response-type
  "Returns the message :type to use when delivering query/subscription payloads to the
  client, based on the negotiated WebSocket subprotocol stored in the context under
  ::subprotocol.

  The modern graphql-transport-ws protocol (used by GraphiQL and current Apollo clients)
  expects \"next\" messages, while the legacy graphql-ws (subscriptions-transport-ws)
  protocol expects \"data\" messages. Defaults to :data when the subprotocol is unknown,
  preserving legacy behaviour."
  [context]
  (case (::subprotocol context)
    "graphql-transport-ws" :next
    :data))

(defn leave-send-operation-response
  [context]
  (when-let [response (:response context)]
    (let [{:keys [id response-data-ch]} (:request context)]
      (put! response-data-ch {:type    (protocol-response-type context)
                              :id      id
                              :payload response})
      (put! response-data-ch {:type :complete
                              :id   id})
      (close! response-data-ch)))
  context)

(defn enter-subscription-query-parser
  [schema]
  (fn [context]
    (let [{operation-name :operationName
           :keys          [query variables]} (:request context)
          actual-schema (if (map? schema)
                          schema
                          (schema))
          parsed-query  (try
                          (parser/parse-query actual-schema query operation-name)
                          (catch Throwable t
                            (throw (ex-info (to-message t)
                                            {::errors (-> t ex-data :errors)}
                                            t))))
          prepared      (parser/prepare-with-query-variables parsed-query variables)
          errors        (validator/validate actual-schema prepared {})]

      (if (seq errors)
        (throw (ex-info "Query validation errors." {::errors errors}))
        (assoc-in context [:request :parsed-lacinia-query] prepared)))))

(defn ^:private execute-operation
  [context parsed-query]
  (let [ch (chan 1)]
    (-> context
        (get-in [:request :lacinia-app-context])
        (assoc
          ::lacinia/connection-params (:connection-params context)
          constants/parsed-query-key parsed-query)
        executor/execute-query
        (resolve/on-deliver! (fn [response]
                               (put! ch (assoc context :response response))))
        ;; Don't execute the query in a limited go block thread
        thread)
    ch))


(defn ^:private execute-subscription
  [context parsed-query]
  (let [{:keys [::values-chan-fn request]} context
        response-type        (protocol-response-type context)
        source-stream-ch     (values-chan-fn)
        {:keys [id shutdown-ch response-data-ch]} request
        source-stream        (fn accept-value [value]
                               (cond
                                 (nil? value)
                                 (close! source-stream-ch)

                                 (resolve/is-resolver-result? value)
                                 (resolve/on-deliver! value accept-value)

                                 :else
                                 (put! source-stream-ch value)))
        app-context          (-> context
                                 (get-in [:request :lacinia-app-context])
                                 (assoc
                                   ::lacinia/connection-params (:connection-params context)
                                   constants/parsed-query-key parsed-query))
        ;; A streamer *must* succeed and return a cleanup function.  If there's a problem with the arguments,
        ;; it may pass the source-stream a ResolverResult that wraps an error.
        cleanup-fn           (executor/invoke-streamer app-context source-stream)
        ;; Track how many streamed values are currently executing queries
        *execution-count     (atom 0)
        ;; Track when the streamer has passed a nil to shut down the subscription cleanly
        *shutting-down?      (atom false)
        ;; Closed when shutting down and execution count drops to 0
        streamer-shutdown-ch (chan)]
    (go-loop []
      (alt!

        ;; TODO: A timeout?

        ;; This channel is closed when the client sends a "stop" message;
        ;; any currently executing subscriptions (or executions of streamed
        ;; values) are discarded.
        shutdown-ch
        (do
          (close! response-data-ch)
          (cleanup-fn))

        source-stream-ch
        ([value]
         (cond

           (some? value)
           (do
             (swap! *execution-count inc)
             (log/trace :stream-value value :id id)
             (-> app-context
                 (assoc ::executor/resolved-value value)
                 executor/execute-query
                 (resolve/on-deliver! (fn [response]
                                        (log/trace :response response :id id)
                                        (put! response-data-ch
                                              {:type    response-type
                                               :id      id
                                               :payload response})
                                        (let [new-count (swap! *execution-count dec)]
                                          (when (and @*shutting-down?
                                                     (zero? new-count))
                                            (close! streamer-shutdown-ch)))))
                 ;; Don't execute the query in a limited go block thread
                 thread))

           (= 0 @*execution-count)
           (close! streamer-shutdown-ch)

           :else
           (reset! *shutting-down? true))
         (recur))

        ;; This is a clean shutdown from a streamer that signaled (via passing a nil)
        ;; that the subscription is exhausted.  response-data-ch is only closed
        ;; after any currently executing queries have first put their
        ;; responses on it.
        streamer-shutdown-ch
        (do
          (>! response-data-ch {:type :complete
                                :id   id})
          (close! response-data-ch)
          (cleanup-fn)
          (log/trace :event :streamer-shutdown :id id))))

    ;; Return the context unchanged, it will unwind while the above process
    ;; does the real work.
    context))

(defn enter-execute-operation
  [context]
  (let [request        (:request context)
        parsed-query   (:parsed-lacinia-query request)
        operation-type (-> parsed-query parser/operations :type)]
    (if (= operation-type :subscription)
      (execute-subscription context parsed-query)
      (execute-operation context parsed-query))))
