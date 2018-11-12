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
  "Defines Pedestal interceptors and supporting code."
  (:require
    [clojure.core.async :refer [chan put!]]
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [io.pedestal.http :as http]
    [ring.util.response :as response]
    [com.walmartlabs.lacinia.resolve :as resolve]
    [com.walmartlabs.lacinia.parser :as parser]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.validator :as validator]
    [com.walmartlabs.lacinia.executor :as executor]
    [com.walmartlabs.lacinia.constants :as constants]
    [com.walmartlabs.lacinia.internal-utils :refer [cond-let]]
    [io.pedestal.http.jetty.websockets :as ws]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]
    [clojure.spec.alpha :as s]
    [com.walmartlabs.lacinia.pedestal.spec :as spec]))

(def ^:private default-path "/graphql")

(def ^:private default-asset-path "/assets/graphiql")

(def ^:private default-subscriptions-path "/graphql-ws")

(def ^:private parsed-query-key-path [:request :parsed-lacinia-query])

(defn ^:private bad-request
  "Generates a bad request Ring response."
  ([body]
   (bad-request 400 body))
  ([status body]
   {:status status
    :headers {}
    :body body}))

(defn ^:private message-as-errors
  [message]
  {:errors [{:message message}]})

(defn ^:no-doc parse-content-type
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


(defn ^:private content-type
  "Gets the content-type of a request. (without encoding)"
  [request]
  (if-let [content-type (get-in request [:headers "content-type"])]
    (:content-type (parse-content-type content-type))))

(defn inject
  "Locates the named interceptor in the list of interceptors and adds (or replaces)
  the new interceptor to the list.

  relative-position may be :before, :after, or :replace.

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
                                     (conj result new-interceptor)))))
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
               :new-interceptor ::interceptor
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
    (content-type request)))

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


(def json-response-interceptor
  "An interceptor that sees if the response body is a map and, if so,
  converts the map to JSON and sets the response Content-Type header."
  (interceptor
    {:name ::json-response
     :leave (fn [context]
              (let [body (get-in context [:response :body])]
                (if (map? body)
                  (-> context
                      (assoc-in [:response :headers "Content-Type"] "application/json")
                      (update-in [:response :body] cheshire/generate-string))
                  context)))}))

(def body-data-interceptor
  "Converts the POSTed body from a input stream into a string."
  (interceptor
    {:name ::body-data
     :enter (fn [context]
              (update-in context [:request :body] slurp))}))

(def graphql-data-interceptor
  "Extracts the raw data (query and variables) from the request using [[extract-query]]."
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
                         (bad-request {:message (str "Invalid request: " (.getMessage e))})))))}))

(defn ^:private query-not-found-error
  [request]
  (let [request-method (get request :request-method)
        body (get request :body)
        message (cond
                  (= request-method :get) "Query parameter 'query' is missing or blank."
                  (str/blank? body) "Request body is empty."
                  (::known-content-type request) "GraphQL query not supplied in request body."
                  :else "Request content type must be application/graphql or application/json.")]
    (message-as-errors message)))

(def missing-query-interceptor
  "Rejects the request when there's no GraphQL query in the request map.

   This must come after [[graphql-data-interceptor]], which is responsible for adding the query to the request map."
  (interceptor
    {:name ::missing-query
     :enter (fn [context]
              (if (-> context :request :graphql-query str/blank?)
                (assoc context :response
                       (bad-request (query-not-found-error (:request context))))
                context))}))

(defn ^:private as-errors
  [exception]
  {:errors [(util/as-error-map exception)]})

(defn ^:private parse-query-document
  [context compiled-schema q operation-name]
  (try
    (let [actual-schema (if (map? compiled-schema)
                          compiled-schema
                          (compiled-schema))
          parsed-query (parser/parse-query actual-schema q operation-name)]
      (assoc-in context parsed-query-key-path parsed-query))
    (catch Exception e
      (assoc context :response
             (bad-request (as-errors e))))))

(defn query-parser-interceptor
  "Given an schema, returns an interceptor that parses the query.

   `compiled-schema` may be the actual compiled schema, or a no-arguments function
   that returns the compiled schema.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed query.

   Before execution, [[prepare-query-interceptor]] injects query variables and performs
   validations."
  [compiled-schema]
  (interceptor
    {:name ::query-parser
     :enter (fn [context]
              (let [{:keys [graphql-query graphql-operation-name]} (:request context)]
                (parse-query-document context
                                      compiled-schema
                                      graphql-query
                                      graphql-operation-name)))}))

(def ^{:added "0.10.0"} prepare-query-interceptor
  "Prepares (with query variables) and validates the query, previously parsed
  by [[query-parsed-interceptor]].

  In earlier releases of lacinia-pedestal, this logic was combined with [[query-parser-interceptor]]."
  (interceptor
    {:name ::prepare-query
     :enter (fn [context]
              (try
                (let [{parsed-query :parsed-lacinia-query
                       vars :graphql-vars} (:request context)
                      prepared (parser/prepare-with-query-variables parsed-query vars)
                      compiled-schema (get prepared constants/schema-key)
                      errors (validator/validate compiled-schema prepared {})]
                  (if (seq errors)
                    (assoc context :response (bad-request {:errors errors}))
                    (assoc-in context parsed-query-key-path prepared)))
                (catch Exception e
                  (assoc context :response
                         (bad-request (as-errors e))))))}))

(defn ^:private remove-status
  "Remove the :status key from the :extensions map; remove the :extensions key if that is now empty."
  [error-map]
  (if-not (contains? error-map :extensions)
    error-map
    (let [error-map' (update error-map :extensions dissoc :status)]
      (if (-> error-map' :extensions seq)
        error-map'
        (dissoc error-map' :extensions)))))

(def status-conversion-interceptor
  "Checks to see if any error map in the :errors key of the response
  contains a :status value (under it's :extensions key).
  If so, the maximum status value of such errors is found and used as the status of the overall response, and the
  :status key is dissoc'ed from all errors."
  (interceptor
    {:name ::status-conversion
     :leave (fn [context]
              (let [response (:response context)
                    errors (get-in response [:body :errors])
                    statuses (keep #(-> % :extensions :status) errors)]
                (if (seq statuses)
                  (let [max-status (reduce max (:status response) statuses)]
                    (-> context
                        (assoc-in [:response :status] max-status)
                        (assoc-in [:response :body :errors]
                                  (map remove-status errors))))
                  context)))}))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request.

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically; for example, to extract authentication information
  from the request and expose that as app-context keys."
  {:added "0.2.0"}
  [app-context]
  (interceptor
    {:name ::inject-app-context
     :enter (fn [context]
              (assoc-in context [:request :lacinia-app-context]
                        (assoc app-context :request (:request context))))}))

(defn ^:private apply-result-to-context
  [context result]
  ;; When :data is missing, then a failure occurred during parsing or preparing
  ;; the request, which indicates a bad request, rather than some failure
  ;; during execution.
  (let [status (if (contains? result :data)
                 200
                 400)
        response {:status status
                  :headers {}
                  :body result}]
    (assoc context :response response)))

(defn ^:private execute-query
  [context]
  (let [request (:request context)
        {q :parsed-lacinia-query
         app-context :lacinia-app-context} request]
    (executor/execute-query (assoc app-context
                                   constants/parsed-query-key q))))

(def query-executor-handler
  "The handler at the end of interceptor chain, invokes Lacinia to
  execute the query and return the main response.

  The handler adds the Ring request map as the :request key to the provided
  app-context before executing the query.

  This comes after [[query-parser-interceptor]], [[inject-app-context-interceptor]],
  and [[status-conversion-interceptor]] in the interceptor chain."
  (interceptor
    {:name ::query-executor
     :enter (fn [context]
              (let [resolver-result (execute-query context)
                    *result (promise)]
                (resolve/on-deliver! resolver-result
                                     (fn [result]
                                       (deliver *result result)))
                (apply-result-to-context context @*result)))}))

(def ^{:added "0.2.0"} async-query-executor-handler
  "Async variant of [[query-executor-handler]] which returns a channel that conveys the
  updated context."
  (interceptor
    {:name ::async-query-executor
     :enter (fn [context]
              (let [ch (chan 1)
                    resolver-result (execute-query context)]
                (resolve/on-deliver! resolver-result
                                     (fn [result]
                                       (->> result
                                            (apply-result-to-context context)
                                            (put! ch))))
                ch))}))

(def ^{:added "0.3.0"} disallow-subscriptions-interceptor
  "Handles requests for subscriptions.  Subscription requests must only be sent to the subscriptions web-socket, not the
  general query endpoint, so any subscription request received in this pipeline is a bad request."
  (interceptor
    {:name ::disallow-subscriptions
     :enter (fn [context]
              (if (-> context :request :parsed-lacinia-query parser/operations :type (= :subscription))
                (assoc context :response (bad-request (message-as-errors "Subscription queries must be processed by the WebSockets endpoint.")))
                context))}))

(defn default-interceptors
  "Returns the default set of GraphQL interceptors, as a seq:

    * ::json-response [[json-response-interceptor]]
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

  Options are as defined by [[service-map]]."
  {:added "0.7.0"}
  [compiled-schema options]
  [json-response-interceptor
   graphql-data-interceptor
   status-conversion-interceptor
   missing-query-interceptor
   (query-parser-interceptor compiled-schema)
   disallow-subscriptions-interceptor
   prepare-query-interceptor
   (inject-app-context-interceptor (:app-context options))
   (if (:async options)
     async-query-executor-handler
     query-executor-handler)])

(defn routes-from-interceptors
  "Returns a set of route vectors from a primary seq of interceptors.
  This returns a set, one element for GET (using the seq as is),
  one for POST (prefixing with [[body-data-interceptor]]).

  Options:

  :get-enabled (default true)
  : If true, then a route for the GET method is included."
  {:added "0.7.0"}
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

  Reads the template file, makes necessary substitutions, and returns a Ring response."
  {:added "0.7.0"}
  [options]
  (let [{:keys [path asset-path subscriptions-path ide-headers]
         :or {path default-path
              asset-path default-asset-path
              subscriptions-path default-subscriptions-path}} options
        ide-headers' (assoc ide-headers "Content-Type" "application/json")
        replacements {:asset-path asset-path
                      :path path
                      :subscriptions-path subscriptions-path
                      :request-headers (str "{"
                                            (->> ide-headers'
                                                 (map (fn [[k v]]
                                                        (str \" (name k) "\": \"" (name v) \")))
                                                 (str/join ", "))
                                            "}")}]
    (-> "com/walmartlabs/lacinia/pedestal/graphiql.html"
        io/resource
        slurp
        (str/replace #"\{\{(.+?)}}" (fn [[_ key]]
                                      (get replacements (keyword key) "--NO-MATCH--")))
        response/response
        (response/content-type "text/html"))))

(defn graphql-routes
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
  and deliver 404 responses)."
  [compiled-schema options]
  (let [{:keys [path ide-path asset-path graphiql]
         :or {path default-path
              asset-path default-asset-path
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
  : Path at which GraphQL requests are services (distinct from the GraphQL IDE).

  :ide-path (default: \"/\")
  : Path from which the GraphiQL IDE, if enabled, can be loaded.

  :asset-path (default: \"/assets/graphiql\")
  : Path from which the JavaScript and CSS assets may be loaded.

  :ide-headers
  : A map from header name to header value. Keys and values may be strings, keywords,
    or symbols and are converted to strings using clojure.core/name.
    These define additional headers to be included in the requests from the IDE.
    Typically, the headers are used to identify and authenticate the requests.

  :interceptors
  : A seq of interceptors to be used in GraphQL routes; passed to [[routes-from-interceptors]].
    If not provided, [[default-interceptors]] is invoked.

  :get-enabled (default true)
  : If true, then a route for the GET method is included. GET requests include the query
    as the `query` query parameter, and can't specify variables or an operation name.

  :async (default: false)
  : If true, the query will execute asynchronously; the handler will return a clojure.core.async
    channel rather than blocking.

  :app-context
  : The base application context provided to Lacinia when executing a query.

  :subscriptions-path (default: \"/graphql-ws\")
  : If subscriptions are enabled, the path at which to service GraphQL websocket requests.
    This must be a distinct path (not the same as the main path or the GraphiQL IDE path).

  :port (default: 8888)
  : HTTP port to use.

  :env (default: :dev)
  : Environment being started.

  See further notes in [[graphql-routes]] and [[default-interceptors]]."
  {:added "0.5.0"}
  [compiled-schema options]
  (let [{:keys [graphiql subscriptions port env subscriptions-path]
         :or {graphiql false
              subscriptions false
              port 8888
              subscriptions-path default-subscriptions-path
              env :dev}} options
        routes (or (:routes options)
                   (graphql-routes compiled-schema options))]
    (cond-> {:env env
             ::http/routes routes
             ::http/port port
             ::http/type :jetty
             ::http/join? false}

      subscriptions
      (assoc-in [::http/container-options :context-configurator]
                ;; The listener-fn is responsible for creating the listener; it is passed
                ;; the request, response, and the ws-map. In sample code, the ws-map
                ;; has callbacks such as :on-connect and :on-text, but in our scenario
                ;; the callbacks are created by the listener-fn, so the value is nil.
                #(ws/add-ws-endpoints % {subscriptions-path nil}
                                      {:listener-fn
                                       (subscriptions/listener-fn-factory compiled-schema options)}))

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
                                              ::spec/interceptors
                                              ::async
                                              ::spec/app-context
                                              ::subscriptions-path
                                              ::port
                                              ::env]))
(s/def ::graphiql boolean?)
(s/def ::routes some?)                                      ; Details are far too complicated
(s/def ::subscriptions boolean?)
(s/def ::get-enabled boolean?)
(s/def ::path (s/and string?
                     #(str/starts-with? % "/")))
(s/def ::ide-path ::path)
(s/def ::asset-path ::path)
(s/def ::ide-headers map?)
(s/def ::async boolean?)
(s/def ::subscriptions-path ::path)
(s/def ::port pos-int?)
(s/def ::env keyword?)

