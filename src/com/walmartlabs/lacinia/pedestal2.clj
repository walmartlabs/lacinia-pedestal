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
  (:require
    [clojure.string :as str]
    [cheshire.core :as cheshire]
    [ring.util.response :as response]
    [io.pedestal.interceptor :refer [interceptor]]
    [io.pedestal.http :as http]
    [com.walmartlabs.lacinia.pedestal.internal :as internal]
    [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors]))

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

(def graphql-data-interceptor
  "Comes after [[body-data-interceptor]], extracts the JSON query and other data into request keys
  :graphql-query (the query document as a string),
  :graphql-vars (a map)
  and :graphql-operation-name (a string).

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
                                   {:message (str "Invalid request: " (.getMessage e))})))))}))

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

   Before execution, [[prepare-query-interceptor]] injects query variables and performs
   validations."
  [compiled-schema]
  (interceptor
    {:name ::query-parser
     :enter (internal/on-enter-query-parser compiled-schema)}))

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

(def query-executor-handler
  "The handler at the end of interceptor chain, invokes Lacinia to
  execute the query and return the main response.

  This comes last in the interceptor chain."
  (interceptor
   {:name  ::query-executor
    :enter (internal/on-enter-query-executor ::query-executor)}))

(def async-query-executor-handler
  "Async variant of [[query-executor-handler]] which returns a channel that conveys the
  updated context."
  (interceptor
    {:name ::async-query-executor
     :enter (internal/on-enter-async-query-executor ::async-query-executor)}))

(defn default-interceptors
  "Returns the default set of GraphQL interceptors, as a seq:

    * ::json-response [[json-response-interceptor]]
    * ::error-response [[error-response-interceptor]]
    * ::body-data [[body-data-interceptor]]
    * ::graphql-data [[graphql-data-interceptor]]
    * ::status-conversion [[status-conversion-interceptor]]
    * ::missing-query [[missing-query-interceptor]]
    * ::query-parser [[query-parser-interceptor]]
    * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
    * ::prepare-query [[prepare-query-interceptor]]
    * ::com.walmartlabs.lacinia.pedestal/inject-app-context [[inject-app-context-interceptor]]
    * ::query-executor [[query-executor-handler]]

  `compiled-schema` may be the actual compiled schema, or a no-arguments function that returns the compiled schema.

  `app-context` is the application context that will be passed into all resolvers
  (the [[inject-app-context-interceptor]] adds a :request key to this map).

  Often, this list of interceptors is augmented by calls to [[inject]]."
  [compiled-schema app-context]
  [json-response-interceptor
   error-response-interceptor
   body-data-interceptor
   graphql-data-interceptor
   status-conversion-interceptor
   missing-query-interceptor
   (query-parser-interceptor compiled-schema)
   disallow-subscriptions-interceptor
   prepare-query-interceptor
   (interceptors/inject-app-context-interceptor app-context)
   query-executor-handler])

(defn graphiql-asset-routes
  "Returns a set of routes for retrieving GraphiQL assets (CSS and JS).

  These routes are needed for the GraphiQL IDE to operate."
  [asset-path]
  (let [asset-path' (str asset-path "/*path")
        asset-get-handler (fn [request]
                            (response/resource-response (-> request :path-params :path)
                                                        {:root "graphiql"}))
        asset-head-handler #(-> %
                                asset-get-handler
                                (assoc :body nil))]
    #{[asset-path' :get asset-get-handler :route-name ::graphiql-get-assets]
      [asset-path' :head asset-head-handler :route-name ::graphiql-head-assets]}))

(def ^:private default-api-path "/api")
(def ^:private default-asset-path "/assets/graphiql")
(def ^:private default-subscriptions-path "/ws")

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

  :ide-connection-params
  : A value that is used with the GraphiQL IDE; this value is converted to JSON,
    and becomes the connectionParams passed in the initial subscription web service call;
    this can be used to identify and authenticate subscription requests."
  [options]
  (let [{:keys [api-path asset-path subscriptions-path ide-headers ide-connection-params]
         :or {api-path default-api-path
              asset-path default-asset-path
              subscriptions-path default-subscriptions-path}} options
        response (internal/graphiql-response
                   api-path subscriptions-path asset-path ide-headers ide-connection-params)]
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

  compiled-schema is either the schema or a function returning the schema.

  options is a map combining options needed by [[graphiql-ide-route]] and [[listener-fn-factory]].

  It may also contain keys :app-context and :port (which defaults to 8888).

  This is useful for initial development and exploration, but applications with any more needs should construct
  their service map directly."
  [compiled-schema options]
  (let [{:keys [api-path ide-path asset-path app-context port]
         :or {api-path default-api-path
              ide-path "/ide"
              asset-path default-asset-path
              port 8888}} options
        interceptors (default-interceptors compiled-schema app-context)
        routes (into #{[api-path :post interceptors :route-name ::graphql-api]
                       [ide-path :get (graphiql-ide-handler options) :route-name ::graphiql-ide]}
                     (graphiql-asset-routes asset-path))]
    (-> {:env :dev
         ::http/routes routes
         ::http/port port
         ::http/type :jetty
         ::http/join? false}
        enable-graphiql
        (enable-subscriptions compiled-schema options))))
