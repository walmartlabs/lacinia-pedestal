(ns com.walmartlabs.lacinia.pedestal-graphiql-disabled-test
  (:require
    [com.walmartlabs.lacinia.pedestal :as lp]
    [clojure.test :refer [deftest is are use-fixtures]]
    [io.pedestal.http :as http]
    [clj-http.client :as client]
    [com.walmartlabs.lacinia.schema :as schema]))

(use-fixtures :once
  (fn [f]
    (let [empty-schema (schema/compile {})
          service (-> (lp/pedestal-service empty-schema {:graphiql false})
                      http/create-server
                      http/start)]
      (try
        (f)
        (finally
          (http/stop service))))))

(deftest graphiql-not-available
  (doseq [path ["/" "/index.html" "/graphiql.css" "/graphiql.js"]]
    (let [response (client/get (str "http://localhost:8888" path) {:throw-exceptions false})]
      (is (= 404 (:status response))
          (str "Expect 404 for path " path)))))
