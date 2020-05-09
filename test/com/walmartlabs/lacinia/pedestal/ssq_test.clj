;  Copyright (c) 2020-present Walmart, Inc.
;
;  Licensed under the Apache License, Version 2.0 (the "License")
;  you may not use this file except in compliance with the License.
;  You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
;  Unless required by applicable law or agreed to in writing, software
;  distributed under the License is distributed on an "AS IS" BASIS,
;  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;  See the License for the specific language governing permissions and
;  limitations under the License.

(ns com.walmartlabs.lacinia.pedestal.ssq-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.test-utils :as tu :refer [send-post-request prune]]
    [com.walmartlabs.lacinia.pedestal :refer [inject]]
    [com.walmartlabs.lacinia.pedestal2 :as p2]
    [com.walmartlabs.lacinia.pedestal.ssq :as ssq]
    [io.pedestal.http :as http]
    [com.walmartlabs.test-reporting :refer [reporting]]
    [com.walmartlabs.lacinia.parser :as parser]))

(def stored-queries
  {"q1" "{ echo(value: \"hello\") { value }}"

   "q2"
   "query($s : String!) {
     echo(value: $s) { value }
   }"

   "op"
   "query long ($s : String!) {
     main_result: echo(value: $s) { provided_value: value }
   }
   query short ($x : String!) {
     e: echo(value: $x) { v: value }
   }"

   "bad" "{ echo { value }"})

(defn static-mock-ssq
  []
  (reify ssq/SSQRepository

    (find-query [_ query-id]
      (get stored-queries query-id))

    (store-query [_ _ _])))

(def *parse-count (atom 0))

(defn server-fixture
  [f]
  (let [schema (tu/compile-schema)
        dyn-interceptors (p2/default-interceptors schema nil)
        retrieve-cached-query (ssq/retrieve-cached-query-interceptor schema (static-mock-ssq))
        ssq-interceptors (inject dyn-interceptors retrieve-cached-query :replace ::p2/query-parser)
        routes #{["/api" :post dyn-interceptors :route-name :dynamic]
                 ["/ssq" :post ssq-interceptors :route-name :ssq]}
        service (-> {:env :dev
                     ::http/routes routes
                     ::http/port 8888
                     ::http/type :jetty
                     ::http/join? false}
                    http/create-server
                    http/start)
        parser parser/parse-query]
    (reset! *parse-count 0)
    (with-redefs [parser/parse-query (fn
                                       ([s q]
                                        (swap! *parse-count inc)
                                        (parser s q))
                                       ([s q o]
                                        (swap! *parse-count inc)
                                        (parser s q o)))]
      (try
        (f)
        (finally
          (http/stop service))))))

(use-fixtures :each server-fixture)

(deftest not-yet-in-cache
  (let [response (send-post-request "q1" {:path "/ssq"})]
    (reporting response
               (is (= {:status 200
                       :body {:data {:echo {:value "hello"}}}}
                      (prune response))))
    (is (= 1 @*parse-count)))

  (let [response (send-post-request "q1" {:path "/ssq"})]
    (reporting response
               (is (= {:status 200
                       :body {:data {:echo {:value "hello"}}}}
                      (prune response))))
    ;; Still 1
    (is (= 1 @*parse-count))))

(deftest not-found-at-all
  (let [response (send-post-request "does-not-exist" {:path "/ssq"})]
    (reporting response
               (is (= {:status 400
                       :body "Stored query not found"}
                      (prune response))))))

(deftest variables-with-cached-query
  (let [response (send-post-request "q2" {:path "/ssq"
                                          :vars {:s "fred"}})]
    (reporting response
               (is (= {:status 200
                       :body {:data {:echo {:value "fred"}}}}
                      (prune response))))
    (is (= 1 @*parse-count)))

  (let [response (send-post-request "q2" {:path "/ssq"
                                          :vars {:s "barney"}})]
    (reporting response
               (is (= {:status 200
                       :body {:data {:echo {:value "barney"}}}}
                      (prune response))))
    ;; Still 1
    (is (= 1 @*parse-count))))

(deftest invalid-query-document
  (let [response (send-post-request  "bad" {:path "/ssq"})]
    (reporting response
               (is (= {:status 400
                       :body
                       {:errors
                        [{:message "Failed to parse GraphQL query."
                          :extensions {:errors [{:locations [{:column nil
                                                              :line 1}]
                                                 :message "extraneous input '<EOF>' expecting {'query', 'mutation', 'subscription', '}', '...', NameId}"}]}}]}}
                      (prune response))))))

(deftest parsing-and-caching-of-queries-with-operations
  (let [response (send-post-request "op" {:path "/ssq"
                                          :operation "long"
                                          :vars {:s "fred"}})]
    (reporting response
               (is (= {:status 200
                       :body {:data {:main_result {:provided_value "fred"}}}}
                      (prune response))))
    (is (= 1 @*parse-count)))
  (let [response (send-post-request "op" {:path "/ssq"
                                          :operation "short"
                                          :vars {:x "barney"}})]
    (reporting response
               (is (= {:status 200
                       :body {:data {:e {:v "barney"}}}}
                      (prune response))))
    ;; Although this isn't exactly desirable, have to re-parse/cache for each combination of
    ;; query document and operation, cause that's just how Lacinia operates.
    (is (=  2 @*parse-count))))