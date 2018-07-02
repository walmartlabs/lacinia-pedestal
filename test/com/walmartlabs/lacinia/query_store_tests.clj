; Copyright (c) 2018-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.query-store-tests
  (:require
    [clojure.test :refer [deftest is]]
    [com.walmartlabs.lacinia.test-utils :refer [send-json-request test-server-fixture]]))

(defmacro with-service [options & body]
  `((test-server-fixture ~options) (fn [] ~@body)))

(defn req
  [body]
  (-> (send-json-request :post "/query" body "application/json")
      (select-keys [:status :body])))


(deftest basic-cache-hit
  (let [*count (atom 0)
        data {"echo/v1" "query ($v: String!) { echo(value: $v) { value } }"}
        query-store (fn [k]
                      (swap! *count inc)
                      (get data k))]
    (with-service {:query-store query-store}

      (is (= {:body {:data {:echo {:value "calculon"}}}
              :status 200}
             (req {:query "echo/v1" :variables {:v "calculon"}})))

      (is (= 1 @*count))

      (is (= {:body {:data {:echo {:value "bender"}}}
              :status 200}
             (req {:query "echo/v1" :variables {:v "bender"}})))

      ;; This time, it's a cache hit
      (is (= 1 @*count)))))

(deftest missing-from-query-store
  (with-service {:query-store (constantly nil)}
    (is (= {:body {:errors [{:message "Stored query not found."}]}
            :status 400}
           (req {:query "does/not/exist"})))))

