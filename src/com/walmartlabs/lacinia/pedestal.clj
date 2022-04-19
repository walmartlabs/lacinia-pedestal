; Copyright (c) 2017-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.pedestal
  "Defines Pedestal interceptors and supporting code.

  Many functions here were deprecated in 0.14.0, with replacements in the
  [[com.walmartlabs.lacinia.pedestal2]] namespace."
  (:require
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [clojure.string :as str]
    [io.pedestal.http :as http]
    [ring.util.response :as response]
    [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors]
    [clojure.spec.alpha :as s]
    [com.walmartlabs.lacinia.pedestal.spec :as spec]
    [com.walmartlabs.lacinia.pedestal.internal :as internal]))

(when (-> *clojure-version* :minor (< 9))
  (require '[clojure.future :refer [boolean? pos-int?]]))

(def ^:private default-path "/graphql")

(def ^:private default-asset-path "/assets/graphiql")

(def ^:private default-subscriptions-path "/graphql-ws")

(def ^:private default-host-address "localhost")

(defn inject
  "Locates the named interceptor in the list of interceptors and adds (or replaces)
  the new interceptor to the list.

  relative-position may be :before, :after, or :replace.

  For :replace, the new interceptor may be nil, in which case the interceptor is removed.

  The named interceptor must exist, or an exception is thrown."
  {:added "0.7.0"}
  [interceptors new-interceptor relative-position interceptor-name]
  (let [*found? (volatile! false)
        final-result (reduce (fn [result interceptor]
                               ;; An interceptor can also be a bare handler function, which is 'nameless'
                               (if-not (= interceptor-name (when (map? interceptor)
                                                             (:name interceptor)))
                                 (conj result interceptor)
                                 (do
                                   (vreset! *found? true)
                                   (case relative-position
                                     :before
                                     (conj result new-interceptor interceptor)

                                     :after
                                     (conj result interceptor new-interceptor)

                                     :replace
                                     (if new-interceptor
                                       (conj result new-interceptor)
                                       result)))))
                             []
                             interceptors)]
    (when-not @*found?
      (throw (ex-info "Could not find existing interceptor."
                      {:interceptors interceptors
                       :new-interceptor new-interceptor
                       :relative-position relative-position
                       :interceptor-name interceptor-name})))

    final-result))

(s/def ::interceptor (s/or :interceptor (s/keys :req-un [::name])
                           :handler fn?))
(s/def ::interceptors (s/coll-of ::interceptor))
;; The name of an interceptor; typically this is namespaced, but that is not a requirement.
;; The name may be nil in some cases (typically, the interceptor formed around a bare handler function).
(s/def ::name (s/nilable keyword?))

(s/fdef inject
        :ret ::interceptors
        :args (s/cat :interceptors ::interceptors
                     :new-interceptor (s/nilable ::interceptor)
                     :relative-position #{:before :after :replace}
                     :interceptor-name keyword?))

(defmulti extract-query
  "Based on the content type of the query, adds up to three keys to the request:

  :graphql-query
  : The query itself, as a string (parsing the query happens later).

  :graphql-vars
  : A map of variables used when executing the query.

  :graphql-operation-name
  : The specific operation requested (for queries that define multiple named operations)."
  (fn [request]
    (internal/content-type request)))

(defmethod extract-query :application/json [request]
  (let [body (cheshire/parse-string (:body request) true)
        query (:query body)
        variables (:variables body)
        operation-name (:operationName body)]
    {:graphql-query query
     :graphql-vars variables
     :graphql-operation-name operation-name
     ::known-content-type true}))

(defmethod extract-query :application/graphql [request]
  (let [query (:body request)
        variables (when-let [vars (get-in request [:query-params :variables])]
                    (cheshire/parse-string vars true))]
    {:graphql-query query
     :graphql-vars variables
     ::known-content-type true}))

(defmethod extract-query :default [request]
  (let [query (get-in request [:query-params :query])]
    (when query
      {:graphql-query query})))

(def ^{:deprecated "0.14.0"} json-response-interceptor
  "An interceptor that sees if the response body is a map and, if so,
  converts the map to JSON and sets the response Content-Type header.

  Deprecated: Use [[pedestal2/json-response-interceptor]] instead."
  (interceptor
    {:name ::json-response
     :leave internal/on-leave-json-response}))

(def ^{:added "0.14.0" :deprecated "0.14.0"} error-response-interceptor
  "Returns an internal server error response when an exception was not handled in prior interceptors.

   This must come after [[json-response-interceptor]], as the error still needs to be converted to json.

   Deprecated: Use [[pedestal2/error-response-interceptor]] instead."
  (interceptor
    {:name ::error-response
     :error internal/on-error-error-response}))

(def ^{:deprecated "0.14.0"} body-data-interceptor
  "Converts the POSTed body from a input stream into a string.

  Deprecated: Use [[pedestal2/body-data-interceptor]] instead."
  (interceptor
    {:name ::body-data
     :enter (fn [context]
              (update-in context [:request :body] slurp))}))

(def ^{:deprecated "0.14.0"} graphql-data-interceptor
  "Extracts the raw data (query and variables) from the request using [[extract-query]].

  Deprecated: Use [[pedestal2/graphql-data-interceptor]] instead."
  (interceptor
    {:name ::graphql-data
     :enter (fn [context]
              (try
                (let [request (:request context)
                      q (extract-query request)]
                  (assoc context :request
                         (merge request q)))
                (catch Exception e
                  (assoc context :response
                         (internal/failure-response {:message (str "Invalid request: " (.getMessage e))})))))}))

(defn ^:private query-not-found-error
  [request]
  (let [request-method (get request :request-method)
        body (get request :body)
        message (cond
                  (= request-method :get) "Query parameter 'query' is missing or blank."
                  (str/blank? body) "Request body is empty."
                  (::known-content-type request) "GraphQL query not supplied in request body."
                  :else "Request content type must be application/graphql or application/json.")]
    (internal/message-as-errors message)))

(def ^{:deprecated "0.14.0"} missing-query-interceptor
  "Rejects the request when there's no GraphQL query in the request map.

   This must come after [[graphql-data-interceptor]], which is responsible for adding the query to the request map.

   Deprecated: Use [[pedestal2/missing-query-interceptor]] instead."
  (interceptor
    {:name ::missing-query
     :enter (fn [context]
              (if (-> context :request :graphql-query str/blank?)
                (assoc context :response
                       (internal/failure-response (query-not-found-error (:request context))))
                context))}))

(defn ^{:deprecated "0.14.0"} query-parser-interceptor
  "Given a compiled schema, returns an interceptor that parses the query.

   `compiled-schema` may be the actual compiled schema, or a no-arguments function
   that returns the compiled schema.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed query.

   Before execution, [[prepare-query-interceptor]] injects query variables and performs
   validations.

   Deprecated: Use [[pedestal2/query-parser-interceptor]] instead."
  [compiled-schema]
  (interceptor
    {:name ::query-parser
     :enter (fn [context]
              (internal/on-enter-query-parser context compiled-schema nil nil))}))

(def ^{:added "0.10.0"
       :deprecated "0.14.0"} prepare-query-interceptor
  "Prepares (with query variables) and validates the query, previously parsed
  by [[query-parser-interceptor]].

  In earlier releases of lacinia-pedestal, this logic was combined with [[query-parser-interceptor]].

  Deprecated: Use [[pedestal2/prepare-query-interceptor]] instead."
  (interceptor
    {:name ::prepare-query
     :enter internal/on-enter-prepare-query}))

(def ^{:deprecated "0.14.0"} status-conversion-interceptor
  "Checks to see if any error map in the :errors key of the response
  contains a :status value (under it's :extensions key).
  If so, the maximum status value of such errors is found and used as the status of the overall response, and the
  :status key is dissoc'ed from all errors.

  Deprecated: use [[pedestal2/status-conversion-interceptor]]."
  (interceptor
    {:name ::status-conversion
     :leave internal/on-leave-status-conversion}))

(def ^{:deprecated "0.14.0"} query-executor-handler
  "The handler at the end of interceptor chain, invokes Lacinia to
  execute the query and return the main response.

  This comes after [[query-parser-interceptor]], [[inject-app-context-interceptor]],
  and [[status-conversion-interceptor]] in the interceptor chain.

  Deprecated: Use [[pedestal2/query-executor-handler]] instead."
  (interceptor
    {:name ::query-executor
     :enter (internal/on-enter-query-executor ::query-executor)}))

(def ^{:added "0.2.0"
       :deprecated "0.14.0"} async-query-executor-handler
  "Async variant of [[query-executor-handler]] which returns a channel that conveys the
  updated context.

  Deprecated: Use [[pedestal2/async-query-executor-handler]] instead."
  (interceptor
    {:name ::async-query-executor
     :enter (internal/on-enter-async-query-executor ::async-query-executor)}))

(def ^{:added "0.3.0"
       :deprecated "0.14.0"} disallow-subscriptions-interceptor
  "Handles requests for subscriptions.  Subscription requests must only be sent to the subscriptions web-socket, not the
  general query endpoint, so any subscription request received in this pipeline is a bad request.

  Deprecated: Use [[pedestal2/disallow-subscriptions-interceptor]] instead."
  (interceptor
    {:name ::disallow-subscriptions
     :enter internal/on-enter-disallow-subscriptions}))

(defn default-interceptors
  "Returns the default set of GraphQL interceptors, as a seq:

    * ::json-response [[json-response-interceptor]]
    * ::error-response [[error-response-interceptor]]
    * ::graphql-data [[graphql-data-interceptor]]
    * ::status-conversion [[status-conversion-interceptor]]
    * ::missing-query [[missing-query-interceptor]]
    * ::query-parser [[query-parser-interceptor]]
    * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
    * ::prepare-query [[prepare-query-interceptor]]
    * ::inject-app-context [[inject-app-context-interceptor]]
    * ::query-executor [[query-executor-handler]] or [[async-query-executor-handler]]

  `compiled-schema` may be the actual compiled schema, or a no-arguments function that returns the compiled schema.

  Often, this list of interceptors is augmented by calls to [[inject]].

  Options are as defined by [[service-map]].

  Deprecated: Use [[pedestal2/default-interceptors]] instead."
  {:added "0.7.0"
   :deprecated "0.14.0"}
  [compiled-schema options]
  [json-response-interceptor
   error-response-interceptor
   graphql-data-interceptor
   status-conversion-interceptor
   missing-query-interceptor
   (query-parser-interceptor compiled-schema)
   disallow-subscriptions-interceptor
   prepare-query-interceptor
   (interceptors/inject-app-context-interceptor (:app-context options))
   (if (:async options)
     async-query-executor-handler
     query-executor-handler)])

(defn routes-from-interceptors
  "Returns a set of route vectors from a primary seq of interceptors.
  This returns a set, one element for GET (using the seq as is),
  one for POST (prefixing with [[body-data-interceptor]]).

  Options:

  :get-enabled (default true)
  : If true, then a route for the GET method is included.

  Deprecated with no replacement (build the route yourself)."
  {:added "0.7.0"
   :deprecated "0.14.0"}
  [_compiled-schema interceptors options]
  (let [{:keys [path get-enabled]
         :or {get-enabled true
              path default-path}} options
        post-interceptors (into [body-data-interceptor] interceptors)]
    (cond-> #{[path :post post-interceptors
               :route-name ::graphql-post]}
      get-enabled (conj [path :get interceptors
                         :route-name ::graphql-get]))))

(defn graphiql-ide-response
  "Reads the graphiql.html resource, then injects new content into it, and ultimately returns a Ring
  response map.

  This function is used when creating customized Pedestal routers that expose the GraphiQL IDE.

  Options are as specified in [[graphql-routes]].

  Reads the template file, makes necessary substitutions, and returns a Ring response.

  Deprecated: Use [[pedestal2/graphiql-ide-handler]] instead."
  {:added "0.7.0"
   :deprecated "0.14.0"}
  [options]
  (let [{:keys [path asset-path subscriptions-path ide-headers ide-connection-params ide-use-legacy-ws-client]
         :or {path default-path
              asset-path default-asset-path
              subscriptions-path default-subscriptions-path
              ide-use-legacy-ws-client true}} options]
    (internal/graphiql-response path subscriptions-path asset-path ide-headers ide-connection-params ide-use-legacy-ws-client)))

(defn ^{:deprecated "0.14.0"} graphql-routes
  "Creates default routes for handling GET and POST requests and
  (optionally) the GraphiQL IDE.

  The paths for the routes are determined by the options.

  Returns a set of route vectors, compatible with
  `io.pedestal.http.route.definition.table/table-routes`.

  Uses [[default-interceptors]] to define the base seq of interceptors.
  For the POST route, [[body-data-interceptor]] is prepended.
  May add an additional route to handle named queries.

  The options for this function are described by [[service-map]].

  Asset paths use wildcard matching; you should be careful to ensure that the asset path does not
  overlap the paths for query request handling, the IDE, or subscriptions (or the asset handler will override the others
  and deliver 404 responses).

  Deprecated: Use the [[com.walmartlabs.lacinia.pedestal2]] namespace instead."
  [compiled-schema options]
  (let [{:keys [ide-path asset-path graphiql]
         :or {asset-path default-asset-path
              ide-path "/"}} options
        interceptors (or (:interceptors options)
                         (default-interceptors compiled-schema options))
        base-routes (routes-from-interceptors compiled-schema interceptors options)]
    (if-not graphiql
      base-routes
      (let [index-handler (let [index-response (graphiql-ide-response options)]
                            (fn [_]
                              index-response))

            asset-path' (str asset-path "/*path")

            asset-get-handler (fn [request]
                                (response/resource-response (-> request :path-params :path)
                                                            {:root "graphiql"}))
            asset-head-handler #(-> %
                                    asset-get-handler
                                    (assoc :body nil))]
        (conj base-routes
              [ide-path :get index-handler :route-name ::graphiql-ide-index]
              [asset-path' :get asset-get-handler :route-name ::graphiql-get-assets]
              [asset-path' :head asset-head-handler :route-name ::graphiql-head-assets])))))

(defn service-map
  "Creates and returns a Pedestal service map.
  This uses a server type of :jetty.

  The returned service map can be passed to the `io.pedestal.http/create-server` function.

  The deprecated function [[pedestal-service]] invokes `create-server` before returning.
  However, in many cases, further extensions to the service map are needed before
  creating and starting the server.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema.

  Options:

  :graphiql (default: false)
  : If given, then enables resources to support the GraphiQL IDE.
    This includes disabling the Content-Security-Policy headers
    that Pedestal 0.5.3 generates by default.

  :routes (default: via [[graphql-routes]])
  : Used when explicitly setting up the routes.
    It is significantly easier to configure the interceptors than to set up
    the routes explicitly, this option exists primarily for backwards compatibility.

  :subscriptions (default: false)
  : If enabled, then support for WebSocket-based subscriptions is added.
  : See [[listener-fn-factory]] for further options related to subscriptions.

  :path (default: \"/graphql\")
  : Path at which GraphQL requests are serviced (distinct from the GraphQL IDE).

  :ide-path (default: \"/\")
  : Path from which the GraphiQL IDE, if enabled, can be loaded.

  :asset-path (default: \"/assets/graphiql\")
  : Path from which the JavaScript and CSS assets may be loaded.

  :ide-headers
  : A map from header name to header value. Keys and values may be strings, keywords,
    or symbols and are converted to strings using clojure.core/name.
    These define additional headers to be included in the requests from the IDE.
    Typically, the headers are used to identify and authenticate the requests.

  :ide-connection-params
  : A value that is used with the GraphiQL IDE; this value is converted to JSON,
    and becomes the connectionParams passed in the initial subscription web service call;
    this can be used to identify and authenticate subscription requests.

  :interceptors
  : A seq of interceptors to be used in GraphQL routes; passed to [[routes-from-interceptors]].
    If not provided, [[default-interceptors]] is invoked.

  :get-enabled (default true)
  : If true, then a route for the GET method is included. GET requests include the query
    as the `query` query parameter, and can't specify variables or an operation name.

  :async (default: false)
  : If true, the query will execute asynchronously; the handler will return a clojure.core.async
    channel rather than blocking.

  :host (default: localhost)
  : HOST address bind to pedestal/jetty.

  :app-context
  : The base application context provided to Lacinia when executing a query.

  :subscriptions-path (default: \"/graphql-ws\")
  : If subscriptions are enabled, the path at which to service GraphQL websocket requests.
    This must be a distinct path (not the same as the main path or the GraphiQL IDE path).

  :port (default: 8888)
  : HTTP port to use.

  :env (default: :dev)
  : Environment being started.

  See further notes in [[graphql-routes]] and [[default-interceptors]].

  Deprecated: Use [[default-service]] initially, then roll your own. Seriously."
  {:added "0.5.0"
   :deprecated "0.14.0"}
  [compiled-schema options]
  (let [{:keys [graphiql subscriptions port env subscriptions-path host]
         :or {graphiql false
              subscriptions false
              port 8888
              subscriptions-path default-subscriptions-path
              env :dev
              host default-host-address}} options
        routes (or (:routes options)
                   (graphql-routes compiled-schema options))]
    (cond-> {:env env
             ::http/routes routes
             ::http/port port
             ::http/host host
             ::http/type :jetty
             ::http/join? false}

      subscriptions
      (internal/add-subscriptions-support compiled-schema subscriptions-path options)

      graphiql
      (assoc ::http/secure-headers nil))))

(s/fdef service-map
        :args (s/cat :compiled-schema ::spec/compiled-schema
                     :options (s/nilable ::service-map-options)))

(s/def ::service-map-options (s/keys :opt-un [::graphiql
                                              ::routes
                                              ::subscriptions
                                              ::get-enabled
                                              ::path
                                              ::ide-path
                                              ::asset-path
                                              ::ide-headers
                                              ::ide-connection-params
                                              ::spec/interceptors
                                              ::async
                                              ::spec/app-context
                                              ::subscriptions-path
                                              ::port
                                              ::env
                                              ::host
                                              ]))
(s/def ::graphiql boolean?)
(s/def ::routes some?)                                      ; Details are far too complicated
(s/def ::subscriptions boolean?)
(s/def ::get-enabled boolean?)
(s/def ::path (s/and string?
                     #(str/starts-with? % "/")))
(s/def ::ide-path ::path)
(s/def ::asset-path ::path)
(s/def ::ide-headers map?)
(s/def ::ide-connection-params some?)
(s/def ::async boolean?)
(s/def ::subscriptions-path ::path)
(s/def ::port nat-int?)
(s/def ::env keyword?)
(s/def ::host string?)
