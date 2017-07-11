(ns com.walmartlabs.lacinia.pedestal
  "Defines Pedestal interceptors and supporting code."
  (:require
    [clojure.core.async :refer [chan put!]]
    [com.walmartlabs.lacinia :as lacinia]
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.pedestal.interceptors
     :as interceptors
     :refer [with-dependencies]]
    [clojure.string :as str]
    [io.pedestal.http :as http]
    [io.pedestal.http.route :as route]
    [ring.util.response :as response]
    [com.walmartlabs.lacinia.resolve :as resolve]
    [com.walmartlabs.lacinia.parser :as parser]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.validator :as validator]
    [com.walmartlabs.lacinia.executor :as executor]
    [com.walmartlabs.lacinia.constants :as constants]
    [io.pedestal.http.jetty.websockets :as ws]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]))

(defn bad-request
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

(defmulti extract-query
  "Based on the content type of the query, adds up to three keys to the request:

  :graphql-query
  : The query itself, as a string (parsing the query happens later)

  :graphql-vars
  : A map of variables used when executing the query.

  :graphql-operation-name
  : The specific operation requested (for queries that define multiple named operations)."
  (fn [request]
    (get-in request [:headers "content-type"])))

(defmethod extract-query "application/json" [request]
  (let [body (cheshire/parse-string (:body request) true)
        query (:query body)
        variables (:variables body)
        operation-name (:operationName body)]
    {:graphql-query query
     :graphql-vars variables
     :graphql-operation-name operation-name}))

(defmethod extract-query  "application/graphql" [request]
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
      (with-dependencies [::json-response])))

;; TODO: These are all inconsistent. Should probably all be in the form of
;; {:errors [{:message "xxx"}]

(defn ^:private query-not-found-error
  [request]
  (let [request-method (get request :request-method)
        content-type (get-in request [:headers "content-type"])
        body (get request :body)]
    (cond
      (= request-method :get) "Query parameter 'query' is missing or blank."
      (nil? body) "Request body is empty."
      :else {:message "Request content type must be application/graphql or application/json."})))


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
      (with-dependencies [::status-conversion])))

(defn ^:private as-errors
  [exception]
  {:errors [(util/as-error-map exception)]})

(defn query-parser-interceptor
  "Given an schema, returns an interceptor that parses the query.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed and prepared
   query."
  [schema]
  (-> {:name ::query-parser
       :enter (fn [context]
                (try
                  (let [request (:request context)
                        {q :graphql-query
                         vars :graphql-vars
                         operation-name :graphql-operation-name} request
                        parsed-query (parser/parse-query schema q operation-name)
                        prepared (parser/prepare-with-query-variables parsed-query vars)
                        errors (validator/validate schema prepared {})]
                    (if (seq errors)
                      (assoc context :response (bad-request {:errors errors}))
                      (assoc-in context [:request :parsed-lacinia-query] prepared)))
                  (catch Exception e
                    (assoc context :response
                           (bad-request (as-errors e))))))}
      interceptor
      (with-dependencies [::missing-query])))

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
      (with-dependencies [::graphql-data])))

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
      (with-dependencies [::query-parser])))

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
  "Handles requests for subscriptions."
  (-> {:name ::disallow-subscriptions
       :enter (fn [context]
                (if (-> context :request :parsed-lacinia-query parser/operations :type (= :subscription))
                  (assoc context :response (bad-request (message-as-errors "Subscription queries must be processed by the WebSockets endpoint."))))
                context)}
      interceptor
      (with-dependencies [::query-parser])))

(defn graphql-interceptors
  "Returns a dependency map of the GraphQL interceptors:

  * ::json-response [[json-response-interceptor]]
  * ::graphql-data [[graphql-data-interceptor]]
  * ::status-conversion [[status-conversion-interceptor]]
  * ::missing-query [[missing-query-interceptor]]
  * ::query-paraser [[query-parser-interceptor]]
  * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
  * ::inject-app-context [[inject-app-context-interceptor]]
  * ::query-executor [[query-executor-handler]] or [[async-query-executor-handler]]

  Options:

  :async (default false)
  : If true, the query will execute asynchronously.

  :app-context
  : The base application context provided to Lacinia when executing a query."
  {:added "0.3.0"}
  [compiled-schema options]
  (let [index-handler (when (:graphiql options)
                        (fn [request]
                          (response/redirect "/index.html")))
        query-parser (query-parser-interceptor compiled-schema)
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
               (with-dependencies executor [::inject-app-context ::disallow-subscriptions])))))

(defn routes-from-interceptor-map
  "Returns a set of route vectors from a primary interceptor dependency map.
  This uses a standard rule for splicing in the POST support.

  This function is useful as an alternative to [[graphql-routes]] when the
  default interceptor dependency map (from [[graphql-interceptors]]) is modified."
  {:added "0.3.0"}
  [route-path get-interceptor-map]
  (let [post-interceptor-map (-> get-interceptor-map
                                 (assoc ::body-data
                                        (with-dependencies body-data-interceptor [::json-response]))
                                 (update ::graphql-data with-dependencies [::body-data]))]
    #{[route-path :post
       (interceptors/order-by-dependency post-interceptor-map)
       :route-name ::graphql-post]
      [route-path :get
       (interceptors/order-by-dependency get-interceptor-map)
       :route-name ::graphql-get]}))

(defn graphql-routes
  "Creates default routes for handling GET and POST requests (at `/graphql`) and
  (optionally) the GraphiQL IDE (at `/`).

  Returns a set of route vectors, compatible with
  `io.pedestal.http.route.definition.table/table-routes`.

  Uses [[graphql-interceptors]] to define the base set of interceptors and
  dependencies.  For the POST route, [[body-data-interceptor]] is spliced in.

  Options:

  :graphiql (default false)
  : If true, enables routes for the GraphiQL IDE.

  :async (default false)
  : If true, the query will execute asynchronously.

  :app-context
  : The base application context provided to Lacinia when executing a query."
  [compiled-schema options]
  (let [get-interceptor-map (graphql-interceptors compiled-schema options)
        index-handler (when (:graphiql options)
                        (fn [request]
                          (response/redirect "/index.html")))]
    (cond-> (routes-from-interceptor-map "/graphql" get-interceptor-map)
      ;; NOTE: The JavaScript initialization code in index.html is hard-wired for
      ;; the routes to be at /graphql.
      index-handler (conj ["/" :get index-handler :route-name ::graphiql-ide-index]))))

(defn pedestal-service
  "Creates and returns a Pedestal service map, ready to be started.
  This uses a server type of :jetty.

  The options are used here, and also passed to [[graphql-routes]]
  (with the two arity version).  Alternately, the complete routes
  are provided (in the three arity version).

  Options:

  :graphiql (default: false)
  : If given, then enables resources to support the GraphiQL IDE

  :subscriptions (default: false)
  : If enabled, then support for WebSocket-based subscriptions is added.

  :port (default: 8888)
  : HTTP port to use.

  :env (default: :dev)
  : Environment being started."
  ([compiled-schema options]
   (pedestal-service compiled-schema
                     (route/expand-routes (graphql-routes compiled-schema options))
                     options))
  ([compiled-schema routes options]
   (->
     {:env (:env options :dev)
      ::http/routes routes
      ::http/port (:port options 8888)
      ::http/type :jetty
      ::http/join? false}
     (cond->
       (:subscriptions options)
       (assoc-in [::http/container-options :context-configurator]
                 ;; The listener-fn is responsible for creating the listener; it is passed
                 ;; the request, response, and the ws-map. In sample code, the ws-map
                 ;; has callbacks such as :on-connect and :on-text, but in our scenario
                 ;; the callbacks are created by the listener-fn, so the value is nil.
                 #(ws/add-ws-endpoints % {"/graphql-ws" nil}
                                       (subscriptions/listener-fn-factory compiled-schema options)))

       (:graphiql options)
       (assoc ::http/resource-path "graphiql"))
     http/create-server)))


