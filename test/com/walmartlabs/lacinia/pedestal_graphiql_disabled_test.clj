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
          service (-> (lp/service-map empty-schema {:graphiql false})
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
