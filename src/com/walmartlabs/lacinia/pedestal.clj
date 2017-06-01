(ns com.walmartlabs.lacinia.pedestal
  "Defines Pedestal interceptors and supporting code."
  (:require
    [clojure.core.async :refer [chan put!]]
    [com.walmartlabs.lacinia :as lacinia]
    [com.walmartlabs.lacinia.parser :refer [parse-query]]
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [clojure.string :as str]
    [io.pedestal.http :as http]
    [io.pedestal.http.route :as route]
    [ring.util.response :as response]
    [com.walmartlabs.lacinia.resolve :as resolve]))

(defn bad-request
  "Generates a bad request Ring response."
  ([body]
   (bad-request 400 body))
  ([status body]
   {:status status
    :headers {}
    :body body}))

(defmulti extract-query
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
  (interceptor
   {:name ::body-data
    :enter (fn [context]
             (update-in context [:request :body] slurp))}))

(def graphql-data-interceptor
  "Extracts the raw data (query and variables) from the request and validates that at least the query
  is present.

  Adds two new request keys:  :graphql-query and :graphql-vars."
  (interceptor
    {:name ::graphql-data
     :enter (fn [context]
              (let [request (:request context)
                    q (extract-query request)]
                (assoc context :request
                       (merge request q))))}))


(defn query-not-found-error [request]
  (let [request-method (get request :request-method)
        content-type (get-in request [:headers "content-type"])
        body (get request :body)]
    (cond
      (= request-method :get) "Query parameter 'query' is missing or blank."
      (nil? body) "Request body is empty."
      :else {:message "Request content type must be application/graphql or application/json."})))


(def missing-query-interceptor
  "Rejects the request when there's no GraphQL query (via the [[graphql-data-interceptor]])."
  (interceptor
    {:name ::missing-query
     :enter (fn [context]
              (if (-> context :request :graphql-query str/blank?)
                (assoc context :response
                       (bad-request (query-not-found-error (:request context))))
                context))}))

(defn query-parser-interceptor
  "Given an schema, returns an interceptor that parses the query.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new reuest key, :parsed-lacinia-query."
  [schema]
  (interceptor
    {:name ::query-parser
     :enter (fn [context]
              (try
                (let [q (get-in context [:request :graphql-query])
                      operation-name (get-in context [:request :graphql-operation-name])
                      parsed-query (parse-query schema q operation-name)]
                  (assoc-in context [:request :parsed-lacinia-query] parsed-query))
                (catch Exception e
                  (assoc context :response
                         (bad-request
                           {:errors [(assoc (ex-data e)
                                            :message (.getMessage e))]})))))}))

(def status-conversion-interceptor
  "Checks to see if any error map in the :errors key of the response
  contains a :status value.  If so, the maximum status value of such errors
  is found and used as the status of the overall response, and the
  :status key is dissoc'ed from all errors."
  (interceptor
    {:name ::status-conversion
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
                  context)))}))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request."
  {:added "0.2.0"}
  [app-context]
  (interceptor
    {:name ::inject-app-context
     :enter (fn [context]
              (assoc-in context [:request :lacinia-app-context]
                        (assoc app-context :request (:request context))))}))

(defn ^:private apply-result-to-context
  [context result]
  ;; When :data is missing, then a failure occured during parsing or preparing
  ;; the request, which indicates a bad request, rather than some failure
  ;; during execution.
  (let [status (if (contains? result :data)
                 200
                 400)
        response {:status status
                  :headers {}
                  :body result}]
    (assoc context :response response)))

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
              (let [request (:request context)
                    {q :parsed-lacinia-query
                     vars :graphql-vars
                     app-context :lacinia-app-context} request
                    result (lacinia/execute-parsed-query q vars app-context)]
                (apply-result-to-context context result)))}))

(def ^ {:added "0.2.0"} async-query-executor-handler
  "Async variant of [[query-executor-handler]] which returns a channel that conveys the
  updated context."
  (interceptor
    {:name ::async-query-executor
     :enter (fn [context]
              (let [request (:request context)
                    {q :parsed-lacinia-query
                     vars :graphql-vars
                     app-context :lacinia-app-context} request
                    ch (chan 1)
                    resolver-result (lacinia/execute-parsed-query-async q vars app-context)]
                (resolve/on-deliver! resolver-result
                                     (fn [result _]
                                       (->> result
                                            (apply-result-to-context context)
                                            (put! ch))))
                ch))}))

(defn graphql-routes
  "Creates default routes for handling GET and POST requests (at `/graphql`) and
  (optionally) the GraphiQL IDE (at `/`).

  Returns a set of route vectors, compatible with
  `io.pedestal.http.route.definition.table/table-routes`.

  The standard interceptor stack:

  * [[json-response-interceptor]]
  * [[require-graphql-content-interceptor]] (for method POST)
  * [[graphql-data-interceptor]]
  * [[missing-query-interceptor]]
  * [[query-parser-interceptor]]
  * [[status-conversion-interceptor]]
  * [[inject-app-context-interceptor]]
  * [[query-executor-handler]] or [[async-query-executor-handler]]

  Options:

  :graphiql (default false)
  : If true, enables routes for the GraphiQL IDE.

  :async (default false)
  : If true, the query will execute asynchronously.

  :app-context
  : The base application context provided to Lacinia when executing a query."
  [compiled-schema options]
  (let [index-handler (when (:graphiql options)
                        (fn [request]
                          (response/redirect "/index.html")))
        query-parser (query-parser-interceptor compiled-schema)
        inject-app-context (inject-app-context-interceptor (:app-context options))
        executor (if (:async options)
                   async-query-executor-handler
                   query-executor-handler)]
    (cond-> #{["/graphql" :post
               [json-response-interceptor
                body-data-interceptor
                graphql-data-interceptor
                missing-query-interceptor
                query-parser
                status-conversion-interceptor
                inject-app-context
                executor]
               :route-name ::graphql-post]
              ["/graphql" :get
               [json-response-interceptor
                graphql-data-interceptor
                missing-query-interceptor
                query-parser
                status-conversion-interceptor
                inject-app-context
                executor]
               :route-name ::graphql-get]}
      index-handler (conj ["/" :get index-handler :route-name ::graphiql-ide-index]))))

(defn pedestal-service
  "Creates and returns a Pedestal service map, ready to be started.
  This uses a server type of :jetty.

  Options:

  :graphiql (default: false)
  : If given, then enables resources to support the GraphiQL IDE

  :port (default: 8888)
  : HTTP port to use.

  :env (default: :dev)
  : Environment being started."
  [compiled-schema options]
  (->
    {:env (:env options :dev)
     ::http/routes (-> (graphql-routes compiled-schema options)
                       route/expand-routes)
     ::http/port (:port options 8888)
     ::http/type :jetty
     ::http/join? false}
    (cond->
      (:graphiql options) (assoc ::http/resource-path "graphiql"))
    http/create-server))
