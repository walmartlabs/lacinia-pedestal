(ns com.walmartlabs.lacinia.test-utils
  (:require [clj-http.client :as client]
            [io.pedestal.http :as http]
            [com.walmartlabs.lacinia.pedestal :as lp]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
            [cheshire.core :as cheshire]))

(defn ^:private resolve-echo
  [context args _]
  (let [{:keys [value error]} args
        error-map (when error
                    {:message "Forced error."
                     :status error})
        resolved-value {:value value
                        :method (get-in context [:request :request-method])}]
    (resolve-as resolved-value error-map)))

(defn sample-schema-fixture
  [options]
  (fn [f]
    (let [schema (-> (io/resource "sample-schema.edn")
                     slurp
                     edn/read-string
                     (util/attach-resolvers {:resolve-echo resolve-echo})
                     schema/compile)
          service (lp/pedestal-service schema options)]
      (http/start service)
      (try
        (f)
        (finally
          (http/stop service))))))


(defn get-url
  [path]
  (client/get (str "http://localhost:8888" path) {:throw-exceptions false}))

(defn send-request
  "Sends a GraphQL request to the server and returns the response."
  ([query]
   (send-request :get query))
  ([method query]
   (send-request method query nil))
  ([method query vars]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false}
       (cond->
         (= method :get)
         (assoc-in [:query-params :query] query)

         (= method :post)
         (assoc-in [:headers "Content-Type"] "application/graphql")

         ;; :post-bad is like :post, but without setting the content type
         (#{:post :post-bad} method)
         (assoc :body query
                :method :post)

         vars
         (assoc-in [:query-params :variables] (cheshire/generate-string vars)))
       client/request
       (update :body #(try
                        (cheshire/parse-string % true)
                        (catch Exception t
                          %))))))


(defn send-json-request
  ([method json]
   (send-json-request method json "application/json"))
  ([method json content-type]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false
        :body (cheshire/generate-string json)
        :headers {"Content-Type" content-type}}
       client/request
       (update :body
               #(try
                  (cheshire/parse-string % true)
                  (catch Exception t %))))))
