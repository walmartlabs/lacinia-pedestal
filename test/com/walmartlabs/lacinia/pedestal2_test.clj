(ns com.walmartlabs.lacinia.pedestal2-test
  "Tests for the new pedestal2 namespace.

  Since the majority of the code goes through the shared internal library, these tests are light as long as
  the original lacinia.pedestal namespace exists."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.pedestal2 :as p2]
    [com.walmartlabs.lacinia.test-utils :as tu]
    [io.pedestal.http :as http]
    [cheshire.core :as cheshire]
    [clj-http.client :as client]
    [com.walmartlabs.test-reporting :refer [reporting]]))

(defn prune
  [response]
  (select-keys response [:status :body]))

(defn server-fixture
  [f]
  (reset! tu/*ping-subscribes 0)
  (reset! tu/*ping-cleanups 0)
  (let [service (-> (tu/compile-schema)
                    (p2/default-service nil)
                    http/create-server
                    http/start)]
    (try
      (f)
      (finally
        (http/stop service)))))

(use-fixtures :once server-fixture)
(use-fixtures :each (tu/subscriptions-fixture "ws://localhost:8888/ws"))

(defn send-request
  "Sends a GraphQL request to the server and returns the response."
  ([query]
   (send-request query nil))
  ([query vars]
   (-> {:method :post
        :url "http://localhost:8888/api"
        :headers {"Content-Type" "application/json"}
        :throw-exceptions false
        :body (cheshire/generate-string {:query query
                                         :variables vars})}
       client/request
       (update :body #(try
                        (cheshire/parse-string % true)
                        (catch Exception t
                          %))))))

(deftest basic-request
  (let [response (send-request "{ echo(value: \"hello\") { value method }}")]
    (reporting response
               (is (= {:status 200
                       :body {:data {:echo {:method "post"
                                            :value "hello"}}}}
                      (prune response)))
               (is (= {:data {:echo {:method "post"
                                     :value "hello"}}}
                      (:body response))))))

(deftest missing-query
  (let [response (send-request nil)]
    (reporting response
               (is (= {:body "JSON 'query' key is missing or blank"
                       :status 400}
                      (prune response))))))

(deftest must-be-json
  (let [response (client/post "http://localhost:8888/api"
                              {:headers {"Content-Type" "text/plain"}
                               :body "does not matter"
                               :throw-exceptions false})]
    (reporting response
               (is (= {:body "Must be application/json"
                       :status 400}
                      (prune response))))))

(deftest subscriptions-ws-request
  (tu/send-init)
  (tu/expect-message {:type "connection_ack"}))
