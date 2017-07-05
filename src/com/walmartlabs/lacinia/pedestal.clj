(ns com.walmartlabs.lacinia.pedestal
  "Defines Pedestal interceptors and supporting code."
  (:require
    [clojure.core.async :refer [chan put!]]
    [com.walmartlabs.lacinia :as lacinia]
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [clojure.string :as str]
    [io.pedestal.http :as http]
    [io.pedestal.http.route :as route]
    [ring.util.response :as response]
    [com.stuartsierra.dependency :as d]
    [com.walmartlabs.lacinia.resolve :as resolve]
    [com.walmartlabs.lacinia.parser :as parser]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.validator :as validator]
    [com.walmartlabs.lacinia.executor :as executor]
    [com.walmartlabs.lacinia.constants :as constants]))

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
  (interceptor
    {:name ::graphql-data
     :enter (fn [context]
              (let [request (:request context)
                    q (extract-query request)]
                (assoc context :request
                       (merge request q))))}))

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

(defn query-parser-interceptor
  "Given an schema, returns an interceptor that parses the query.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed and prepared
   query."
  [schema]
  (interceptor
    {:name ::query-parser
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
                         (bad-request (as-errors e))))))}))

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
  (interceptor
    {:name ::disallow-subscriptions
     :enter (fn [context]
              (if (-> context :request :parsed-lacinia-query parser/operations :type (= :subscription))
                (assoc context :response (bad-request (message-as-errors "Subscription queries must be processed by the WebSockets endpoint."))))
              context)}))

(defn with-dependencies
  "Adds metadata to an interceptor to identify its dependencies.

  Dependencies are a seq of keywords.

  with-dependencies is additive.

  Returns the interceptor with updated metadata."
  {:added "0.3.0"}
  [interceptor dependencies]
  {:pre [(some? interceptor)
         (seq dependencies)]}
  (vary-meta interceptor
             (fn [m]
               (update m ::dependencies
                       #(into (or % #{}) dependencies)))))

(defn order-by-dependency
  "Orders an interceptor *map* by dependencies.
  The keys of the map are arbitrary keywords (generally the same as the :name
  key of the interceptor map), and each value is an interceptor that has been
  augmented via [[with-dependencies]].

  The result is an ordered list of just the non-nil interceptors. "
  {:added "0.3.0"}
  [dependency-map]
  (let [reducer (fn [g dep-name interceptor]
                  (reduce #(d/depend %1 dep-name %2)
                          g
                          (-> interceptor meta ::dependencies)))]
    (->> dependency-map
         (reduce-kv reducer (d/graph))
         d/topo-sort
         (map #(get dependency-map %))
         ;; When dealing with dependencies, you might replace a dependency with
         ;; an empty map that, perhaps, has dependencies on some new dependency.
         (remove empty?)
         vec)))

(defn graphql-interceptors
  "Returns a dependency map of the GraphQL interceptors:

  * ::json-response [[json-response-interceptor]]
  * ::graphql-data [[graphql-data-interceptor]]
  * ::status-conversion [[status-conversion-interceptor]]
  * ::missing-query [[missing-query-interceptor]]
  * ::query-paraser [[query-parser-interceptor]]
  * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
  * ::inject-app-context [[inject-app-context-interceptor]]
  * [[query-executor-handler]] or [[async-query-executor-handler]]

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
                   query-executor-handler)
        interceptors [json-response-interceptor
                      (with-dependencies graphql-data-interceptor [::json-response])
                      (with-dependencies status-conversion-interceptor [::graphql-data])
                      (with-dependencies missing-query-interceptor [::status-conversion])
                      (with-dependencies query-parser [::missing-query])
                      (with-dependencies disallow-subscriptions-interceptor [::query-parser])
                      (with-dependencies inject-app-context [::query-parser])]]
    (-> (zipmap (map :name interceptors)
                interceptors)
        (assoc ::query-executor
               (with-dependencies executor [::inject-app-context ::disallow-subscriptions])))))

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
        post-interceptor-map (-> get-interceptor-map
                                 (assoc ::body-data
                                        (with-dependencies body-data-interceptor [::json-response]))
                                 (update ::graphql-data with-dependencies [::body-data]))
        index-handler (when (:graphiql options)
                        (fn [request]
                          (response/redirect "/index.html")))]
    (cond-> #{["/graphql" :post
               (order-by-dependency post-interceptor-map)
               :route-name ::graphql-post]
              ["/graphql" :get
               (order-by-dependency get-interceptor-map)
               :route-name ::graphql-get]}
      index-handler (conj ["/" :get index-handler :route-name ::graphiql-ide-index]))))

(defn pedestal-service
  "Creates and returns a Pedestal service map, ready to be started.
  This uses a server type of :jetty.

  The options are used here, and also passed to [[graphql-routes]].

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
     ::http/routes (route/expand-routes (graphql-routes compiled-schema options))
     ::http/port (:port options 8888)
     ::http/type :jetty
     ::http/join? false}
    (cond->
      (:graphiql options) (assoc ::http/resource-path "graphiql"))
    http/create-server))


