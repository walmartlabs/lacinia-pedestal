(ns com.walmartlabs.lacinia.pedestal
  "Defines Pedestal interceptors and supporting code."
  (:require
    [clojure.core.async :refer [chan put!]]
    [com.walmartlabs.lacinia :as lacinia]
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.pedestal.interceptors
     :as interceptors
     :refer [ordered-after add]]
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
    [io.pedestal.http.jetty.websockets :as ws]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]))

(def ^:private default-subscriptions-path "/graphql-ws")

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

(defmulti extract-query
  "Based on the content type of the query, adds up to three keys to the request:

  :graphql-query
  : The query itself, as a string (parsing the query happens later)

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
     :graphql-operation-name operation-name}))

(defmethod extract-query :application/graphql [request]
  (let [query (:body request)
        variables (when-let [vars (get-in request [:query-params :variables])]
                    (cheshire/parse-string vars true))]
    {:graphql-query query
     :graphql-vars variables}))

(defmethod extract-query :default [request]
  (let [query (get-in request [:query-params :query])]
    {:graphql-query query}))


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
  (-> {:name ::graphql-data
       :enter (fn [context]
                (let [request (:request context)
                      q (extract-query request)]
                  (assoc context :request
                         (merge request q))))}
      interceptor
      (ordered-after [::json-response])))

(defn ^:private query-not-found-error
  [request]
  (let [request-method (get request :request-method)
        content-type (get-in request [:headers "content-type"])
        body (get request :body)
        message (cond
                  (= request-method :get) "Query parameter 'query' is missing or blank."
                  (str/blank? body) "Request body is empty."
                  :else "Request content type must be application/graphql or application/json.")]
    (message-as-errors message)))

(def missing-query-interceptor
  "Rejects the request when there's no GraphQL query in the request map.

   This must come after [[graphql-data-interceptor]], which is responsible for adding the query to the request map."
  (-> {:name ::missing-query
       :enter (fn [context]
                (if (-> context :request :graphql-query str/blank?)
                  (assoc context :response
                         (bad-request (query-not-found-error (:request context))))
                  context))}
      interceptor
      (ordered-after [::status-conversion])))

(defn ^:private as-errors
  [exception]
  {:errors [(util/as-error-map exception)]})

(defn query-parser-interceptor
  "Given an schema, returns an interceptor that parses the query.

   `compiled-schema` may be the actual compiled schema, or a no-arguments function
   that returns the compiled schema.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed and prepared
   query."
  [compiled-schema]
  (-> {:name ::query-parser
       :enter (fn [context]
                (try
                  (let [request (:request context)
                        {q :graphql-query
                         vars :graphql-vars
                         operation-name :graphql-operation-name} request
                        actual-schema (if (map? compiled-schema)
                                        compiled-schema
                                        (compiled-schema))
                        parsed-query (parser/parse-query actual-schema q operation-name)
                        prepared (parser/prepare-with-query-variables parsed-query vars)
                        errors (validator/validate actual-schema prepared {})]
                    (if (seq errors)
                      (assoc context :response (bad-request {:errors errors}))
                      (assoc-in context [:request :parsed-lacinia-query] prepared)))
                  (catch Exception e
                    (assoc context :response
                           (bad-request (as-errors e))))))}
      interceptor
      (ordered-after [::missing-query])))

(def status-conversion-interceptor
  "Checks to see if any error map in the :errors key of the response
  contains a :status value.  If so, the maximum status value of such errors
  is found and used as the status of the overall response, and the
  :status key is dissoc'ed from all errors."
  (-> {:name ::status-conversion
       :leave (fn [context]
                (let [response (:response context)
                      errors (get-in response [:body :errors])
                      statuses (keep :status errors)]
                  (if (seq statuses)
                    (let [max-status (reduce max (:status response) statuses)]
                      (-> context
                          (assoc-in [:response :status] max-status)
                          (assoc-in [:response :body :errors]
                                    (map #(dissoc % :status) errors))))
                    context)))}
      interceptor
      (ordered-after [::graphql-data])))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request.

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically; for example, to extract authentication information
  from the request and expose that as app-context keys."
  {:added "0.2.0"}
  [app-context]
  (-> {:name ::inject-app-context
       :enter (fn [context]
                (assoc-in context [:request :lacinia-app-context]
                          (assoc app-context :request (:request context))))}
      interceptor
      (ordered-after [::query-parser])))

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
  (-> {:name ::disallow-subscriptions
       :enter (fn [context]
                (if (-> context :request :parsed-lacinia-query parser/operations :type (= :subscription))
                  (assoc context :response (bad-request (message-as-errors "Subscription queries must be processed by the WebSockets endpoint.")))
                  context))}
      interceptor
      (ordered-after [::query-parser])))

(defn graphql-interceptors
  "Returns a dependency map of the GraphQL interceptors:

  * ::json-response [[json-response-interceptor]]
  * ::graphql-data [[graphql-data-interceptor]]
  * ::status-conversion [[status-conversion-interceptor]]
  * ::missing-query [[missing-query-interceptor]]
  * ::query-parser [[query-parser-interceptor]]
  * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
  * ::inject-app-context [[inject-app-context-interceptor]]
  * ::query-executor [[query-executor-handler]] or [[async-query-executor-handler]]

  `compiled-schema` may be the actual compiled schema, or a no-arguments function that returns the compiled schema.

  Options:

  :async (default false)
  : If true, the query will execute asynchronously.

  :app-context
  : The base application context provided to Lacinia when executing a query."
  {:added "0.3.0"}
  [compiled-schema options]
  (let [query-parser (query-parser-interceptor compiled-schema)
        inject-app-context (inject-app-context-interceptor (:app-context options))
        executor (if (:async options)
                   async-query-executor-handler
                   query-executor-handler)]
    (-> [json-response-interceptor
         graphql-data-interceptor
         status-conversion-interceptor
         missing-query-interceptor
         query-parser
         disallow-subscriptions-interceptor
         inject-app-context]
        interceptors/as-dependency-map
        (assoc ::query-executor
               (ordered-after executor [::inject-app-context ::disallow-subscriptions])))))

(defn routes-from-interceptor-map
  "Returns a set of route vectors from a primary interceptor dependency map.
  This uses a standard rule for splicing in the POST support.

  This function is useful as an alternative to [[graphql-routes]] when the
  default interceptor dependency map (from [[graphql-interceptors]]) is modified."
  {:added "0.3.0"}
  [route-path get-interceptor-map]
  (let [post-interceptor-map (-> get-interceptor-map
                                 (add body-data-interceptor ::json-response)
                                 (update ::graphql-data ordered-after [::body-data]))]
    #{[route-path :post
       (interceptors/order-by-dependency post-interceptor-map)
       :route-name ::graphql-post]
      [route-path :get
       (interceptors/order-by-dependency get-interceptor-map)
       :route-name ::graphql-get]}))

(defn ^:private read-graphiql-html
  "Reads the graphiql.html resource, then injects new content into it, and ultimately returns a canned
  response map."
  [path asset-path options]
  (let [{:keys [subscriptions-path assets-path api-key api-key-header]
         :or {subscriptions-path default-subscriptions-path
              api-key-header "apikey"
              api-key "graphiql"}} options
        replacements {:apikey api-key
                      :apikey-header api-key-header
                      :asset-path asset-path
                      :path path
                      :subscriptions-path subscriptions-path}]
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

  Uses [[graphql-interceptors]] to define the base set of interceptors and
  dependencies.  For the POST route, [[body-data-interceptor]] is spliced in.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema.

  Options:

  :graphiql (default: false)
  : If true, enables routes for the GraphiQL IDE.

  :path (default: \"/graphql\")
  : Path at which GraphQL requests are services (distinct from the GraphQL IDE).

  :ide-path (default: \"/\")
  : Path from which the GraphiQL IDE, if enabled, can be loaded.

  :asset-path (default: \"/assets/graphiql\")
  : Path from which the JavaScript and CSS assets may be loaded.

  :api-key (default: \"graphiql\")
  : Passed back in requests from the IDE in a header (specified by :api-key-header).

  :api-key-header (default \"apikey\")
  : Specifies the header in which the API key will be passed.

  :interceptors
  : The map of interceptors to be passed to [[routes-from-interceptor-map]].
    If not provided, [[graphql-interceptors]] is invoked.

  :async (default: false)
  : If true, the query will execute asynchronously; the handler will return a clojure.core.async
    channel rather than blocking.

  :app-context
  : The base application context provided to Lacinia when executing a query.

  Asset paths use wildcard matching; you should be careful to ensure that the asset path does not
  overlap the paths for request, the IDE, or subscriptions (or the asset handler will override the others
  and deliver 404 responses)."
  [compiled-schema options]
  (let [{:keys [path ide-path asset-path graphiql]
         :or {path "/graphql"
              asset-path "/assets/graphiql"
              ide-path "/"}} options
        get-interceptor-map (or (:interceptors options)
                                (graphql-interceptors compiled-schema options))
        base-routes (routes-from-interceptor-map path get-interceptor-map)]
    (if-not graphiql
      base-routes
      (let [index-handler (let [index-response (read-graphiql-html path asset-path options)]
                            (fn [request]
                              index-response))

            api-key-handler (fn [request]
                              (let [api-key (-> request :path-params :api-key)]
                                (read-graphiql-html path asset-path (assoc options :api-key api-key))))

            asset-path' (str asset-path "/*path")

            asset-get-handler (fn [request]
                                (response/resource-response (-> request :path-params :path)
                                                            {:root "graphiql"}))
            asset-head-handler #(-> %
                                    asset-get-handler
                                    (assoc :body nil))]
        (conj base-routes
              ;; Startup IDE using default API key
              [ide-path :get index-handler :route-name ::graphiql-ide-index]
              ;; Startup IDE using API key specified in the URL
              [(str ide-path "/:api-key") :get api-key-handler :route-name ::graphiql-ide-api-key]
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

  :interceptors
  : Map of interceptor names to interceptors, with dependencies.
    If not provided, default is via [[graphql-interceptors]].
    Used to build the routes (via [[routes-from-interceptor-map]]).

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

  :api-key (default: \"graphiql\")
  : Passed back in requests from the IDE in a header (specified by :api-key-header).

  :api-key-header (default \"apikey\")
  : Specifies the header in which the API key will be passed.

  :interceptors
  : The map of interceptors to be passed to [[routes-from-interceptor-map]].
    If not provided, [[graphql-interceptors]] is invoked.

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

  See further notes in [[graphql-routes]] and [[graphql-interceptors]]."
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

(defn pedestal-service
  "This function has been deprecated in favor of [[service-map]], but is being maintained for
  compatibility.

  This simply invokes [[service-map]] and passes the resulting map through `io.pedestal.http/create-server`."
  {:deprecated "0.5.0"}
  [compiled-schema options]
  (http/create-server (service-map compiled-schema options)))
