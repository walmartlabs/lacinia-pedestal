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

(ns ^{:added "0.14.0"} com.walmartlabs.lacinia.pedestal2
  "Utilities for creating handlers, interceptors, routes, and service maps needed by a Pedestal service
  that exposes a GraphQL API and GraphiQL IDE."
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors]
            [com.walmartlabs.lacinia.pedestal.internal :as internal]
            [com.walmartlabs.lacinia.tracing :as tracing]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :refer [interceptor]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.util.response :as response]))

(def json-response-interceptor
  "An interceptor that sees if the response body is a map and, if so,
  converts the map to JSON and sets the response Content-Type header."
  (interceptor
    {:name ::json-response
     :leave internal/on-leave-json-response}))

(def ^{:added "0.14.0"} error-response-interceptor
  "Returns an internal server error response when an exception was not handled in prior interceptors.

   This must come after [[json-response-interceptor]], as the error still needs to be converted to json."
  (interceptor
    {:name ::error-response
     :error internal/on-error-error-response}))

(def body-data-interceptor
  "Converts the POSTed body from a input stream into a string, or rejects the request
  with a 400 response if the content type is not application/json."
  (interceptor
    {:name ::body-data
     :enter (fn [context]
              (let [content-type (-> context :request internal/content-type)]
                (if (= content-type :application/json)
                  (update-in context [:request :body] slurp)
                  (assoc context :response (internal/failure-response "Must be application/json")))))}))

(defn ^:private clear-graphql-data
  [context]
  (update context :request dissoc :graphql-query :graphql-vars :graphql-operation-name))

(def graphql-data-interceptor
  "Comes after [[body-data-interceptor]], extracts the JSON query and other data into request keys
  :graphql-query (the query document as a string),
  :graphql-vars (a map)
  and :graphql-operation-name (a string).

  These keys are dissoc'ed on leave, or on error.

  Comes after [[body-data-interceptor]]."
  (interceptor
    {:name ::graphql-data
     :enter (fn [context]
              (try
                (let [payload (-> context :request :body (cheshire/parse-string true))
                      {:keys [query variables]
                       operation-name :operationName} payload]
                  (update context :request
                          assoc
                          :graphql-query query
                          :graphql-vars variables
                          :graphql-operation-name operation-name))
                (catch Exception e
                  (assoc context :response
                         (internal/failure-response
                           {:message (str "Invalid request: " (.getMessage e))})))))
     :leave clear-graphql-data
     :error (fn [context exception]
              (-> (clear-graphql-data context)
                  (internal/add-error exception)))}))

(def missing-query-interceptor
  "Rejects the request with a 400 response is the JSON query variable is missing or blank.

  Comes after [[graphql-data-interceptor]]."
  (interceptor
    {:name ::missing-query
     :enter (fn [context]
              (if (-> context :request :graphql-query str/blank?)
                (assoc context :response
                       (internal/failure-response "JSON 'query' key is missing or blank"))
                context))}))

(defn query-parser-interceptor
  "Given a compiled schema, returns an interceptor that parses the query.

   `compiled-schema` may be the actual compiled schema, or a no-arguments function
   that returns the compiled schema.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed query.
   This key is removed on leave or on error.

   `cache` defaults to nil, it should implement [[ParsedQueryCache]].

   Before execution, [[prepare-query-interceptor]] injects query variables and performs
   validations."
  ([compiled-schema]
   (query-parser-interceptor compiled-schema nil))
  ([compiled-schema cache]
   (interceptor
     {:name ::query-parser
      :enter (fn [context]
               (internal/on-enter-query-parser context compiled-schema cache (get-in context [:request ::timing-start])))
      :leave internal/on-leave-query-parser
      :error internal/on-error-query-parser})))

(def prepare-query-interceptor
  "Prepares (with query variables) and validates the query, previously parsed
  by [[query-parser-interceptor]]."
  (interceptor
    {:name ::prepare-query
     :enter internal/on-enter-prepare-query}))

(def status-conversion-interceptor
  "Checks to see if any error map in the :errors key of the response
  contains a :status value (under it's :extensions key).
  If so, the maximum status value of such errors is found and used as the status of the overall response, and the
  :status key is dissoc'ed from all errors."
  (interceptor
    {:name ::status-conversion
     :leave internal/on-leave-status-conversion}))

(def disallow-subscriptions-interceptor
  "Handles requests for subscriptions.  Subscription requests must only be sent to the subscriptions web-socket, not the
  general query endpoint, so any subscription request received in this pipeline is a bad request."
  (interceptor
    {:name ::disallow-subscriptions
     :enter internal/on-enter-disallow-subscriptions}))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request.

  On leave (or error), the :lacinia-app-context key is dissoc'ed.

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically; for example, to extract authentication information
  from the request and expose that as app-context keys."
  [app-context]
  (interceptor
    {:name ::inject-app-context
     :enter (interceptors/on-enter-app-context-interceptor app-context)
     :leave interceptors/on-leave-app-context-interceptor
     :error interceptors/on-error-app-context-interceptor}))

(def query-executor-handler
  "The handler at the end of interceptor chain, invokes Lacinia to
  execute the query and return the main response.

  This comes last in the interceptor chain."
  (interceptor
    {:name ::query-executor
     :enter (internal/on-enter-query-executor ::query-executor)}))

(def async-query-executor-handler
  "Async variant of [[query-executor-handler]] which returns a channel that conveys the
  updated context."
  (interceptor
    {:name ::async-query-executor
     :enter (internal/on-enter-async-query-executor ::async-query-executor)}))

(def ^{:added "0.15.0"} initialize-tracing-interceptor
  "Initializes timing information for the request; largely, this captures the earliest
  possible start time for the request (before any other interceptors), just in case
  tracing is enabled for this request (that decision is made by [[enable-tracing-interceptor]])."
  (interceptor
    {:name ::initialize-tracing
     :enter (fn [context]
              ;; Without this, the tracing during parsing doesn't know when the request actually started
              ;; and assumes no real time has passed, which is less accurate. Capturing the timing start early
              ;; ensures that time spent parsing the request body or doing other work before we get to parsing
              ;; is properly accounted for.
              (assoc-in context [:request ::timing-start] (tracing/create-timing-start)))}))

(def ^{:added "0.15.0"} enable-tracing-interceptor
  "Enables tracing if the `lacinia-tracing` header is present."
  (interceptor
    {:name ::enable-tracing
     :enter (fn [context]
              ;; Must come after the app context is added to the request.
              (let [request (:request context)
                    enabled? (get-in request [:headers "lacinia-tracing"])]
                (cond-> context
                  enabled? (update-in [:request :lacinia-app-context] tracing/enable-tracing))))}))

(defn default-interceptors
  "Returns the default set of GraphQL interceptors, as a seq:

    * ::initialize-tracing [[initialize-tracing-interceptor]]
    * ::json-response [[json-response-interceptor]]
    * ::error-response [[error-response-interceptor]]
    * ::body-data [[body-data-interceptor]]
    * ::graphql-data [[graphql-data-interceptor]]
    * ::status-conversion [[status-conversion-interceptor]]
    * ::missing-query [[missing-query-interceptor]]
    * ::query-parser [[query-parser-interceptor]]
    * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
    * ::prepare-query [[prepare-query-interceptor]]
    * ::inject-app-context [[inject-app-context-interceptor]]
    * ::enable-tracing [[enable-tracing-interceptor]]
    * ::query-executor [[query-executor-handler]]

  `compiled-schema` may be the actual compiled schema, or a no-arguments function that returns the compiled schema.

  `app-context` is the application context that will be passed into all resolvers
  (the [[inject-app-context-interceptor]] adds a :request key to this map).

  The options map may contain key :parsed-query-cache, which will be used by the ::query-parser interceptor."
  ([compiled-schema]
   (default-interceptors compiled-schema nil))
  ([compiled-schema app-context]
   (default-interceptors compiled-schema app-context nil))
  ([compiled-schema app-context options]
   [initialize-tracing-interceptor
    json-response-interceptor
    error-response-interceptor
    body-data-interceptor
    graphql-data-interceptor
    status-conversion-interceptor
    missing-query-interceptor
    (query-parser-interceptor compiled-schema (:parsed-query-cache options))
    disallow-subscriptions-interceptor
    prepare-query-interceptor
    (inject-app-context-interceptor app-context)
    enable-tracing-interceptor
    query-executor-handler]))

(defn graphiql-asset-routes
  "Returns a set of routes for retrieving GraphiQL assets (CSS and JS).

  These routes are needed for the GraphiQL IDE to operate."
  [asset-path]
  (let [asset-path' (str asset-path "/*path")
        asset-get-handler (wrap-not-modified
                            (fn [request]
                              (response/resource-response (-> request :path-params :path)
                                                          {:root "graphiql"})))
        asset-head-handler #(-> %
                                asset-get-handler
                                (assoc :body nil))]
    #{[asset-path' :get asset-get-handler :route-name ::graphiql-get-assets]
      [asset-path' :head asset-head-handler :route-name ::graphiql-head-assets]}))

(def ^:private default-api-path "/api")
(def ^:private default-asset-path "/assets/graphiql")
(def ^:private default-subscriptions-path "/ws")
(def ^:private default-host-address "localhost")

(defn graphiql-ide-handler
  "Returns a handler for the GraphiQL IDE.

  A route can be constructed from this handler.

  Options:

  :api-path (default: \"/api\")
  : Path at which GraphQL requests are serviced.

  :asset-path (default: \"/assets/graphiql\")
  : Path from which the JavaScript and CSS assets may be loaded.

  :subscriptions-path (default: \"/ws\")
  : Path for web socket connections, to handle GraphQL subscriptions.

  :ide-headers
  : A map from header name to header value. Keys and values may be strings, keywords,
    or symbols and are converted to strings using clojure.core/name.
    These define additional headers to be included in the requests from the IDE.
    Typically, the headers are used to identify and authenticate the requests.

    The default for :ide-headers is `{\"lacinia-tracing\", \"true\"} to enable GraphQL
    tracing from inside the GraphiQL IDE>

  :ide-connection-params
  : A value that is used with the GraphiQL IDE; this value is converted to JSON,
    and becomes the connectionParams passed in the initial subscription web service call;
    this can be used to identify and authenticate subscription requests.

  :ide-use-legacy-ws-client
  : A boolean which specifies whether GraphiQL IDE uses old `subscriptions-transport-ws` client or `graphql-ws` client.
    The default value is `true` for backward compatibility."
  [options]
  (let [{:keys [api-path asset-path subscriptions-path ide-headers ide-connection-params ide-use-legacy-ws-client]
         :or {api-path default-api-path
              asset-path default-asset-path
              subscriptions-path default-subscriptions-path
              ide-headers {"lacinia-tracing" "true"}
              ide-use-legacy-ws-client true}} options
        response (internal/graphiql-response
                   api-path subscriptions-path asset-path ide-headers ide-connection-params ide-use-legacy-ws-client)]
    (fn [_]
      response)))

(defn enable-subscriptions
  "Updates a Pedestal service map to add support for subscriptions.

  As elsewhere, the compiled-schema may be a function that returns the compiled schema.

  The subscription options are documented at [[listener-fn-factory]], with the addition
  of :subscriptions-path (defaulting to \"/ws\")."
  [service-map compiled-schema subscription-options]
  (internal/add-subscriptions-support service-map
                                      compiled-schema
                                      (:subscriptions-path subscription-options default-subscriptions-path)
                                      subscription-options))

(defn enable-graphiql
  "Disables secure headers in the service map, a prerequisite for GraphiQL requests to operate."
  [service-map]
  (assoc service-map ::http/secure-headers nil))

(defn default-service
  "Returns a default Pedestal service map, with subscriptions and GraphiQL enabled.

  The defaults put the GraphQL API at `/api` and the GraphiQL IDE at `/ide` (and subscriptions endpoint
  at `/ws`).

  Unlike earlier versions of lacinia-pedestal, only POST is supported, and the content type must
  be `application/json`.

  compiled-schema is either the compiled schema itself, or a function returning the compiled schema.

  options is a map combining options needed by [[graphiql-ide-route]], [[default-interceptors]],
  [[enable-subscriptions]], and [[listener-fn-factory]].

  It may also contain keys :app-context and :port (which defaults to 8888).

  You can also define an explicit :host address to your application. Useful when running inside Docker.

  This is useful for initial development and exploration, but applications with any more sophisticated needs
  should construct their service map directly."
  [compiled-schema options]
  (let [{:keys [api-path ide-path asset-path app-context port host]
         :or {api-path default-api-path
              ide-path "/ide"
              asset-path default-asset-path
              port 8888
              host default-host-address}} options
        interceptors (default-interceptors compiled-schema app-context options)
        routes (into #{[api-path :post interceptors :route-name ::graphql-api]
                       [ide-path :get (graphiql-ide-handler options) :route-name ::graphiql-ide]}
                     (graphiql-asset-routes asset-path))]
    (-> {:env :dev
         ::http/routes routes
         ::http/port port
         ::http/host host
         ::http/type :jetty
         ::http/join? false}
        enable-graphiql
        (enable-subscriptions compiled-schema options))))
