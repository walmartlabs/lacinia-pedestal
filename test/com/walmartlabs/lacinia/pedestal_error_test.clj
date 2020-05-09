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

(ns com.walmartlabs.lacinia.pedestal-error-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [com.walmartlabs.lacinia.pedestal :refer [default-interceptors inject] :as lp]
   [com.walmartlabs.lacinia.test-utils :refer [test-server-fixture
                                               send-request]]
   [clojure.spec.test.alpha :as stest]
   [io.pedestal.interceptor :refer [interceptor]]))

(stest/instrument)

(defn proof-interceptor
  "Interceptor that records how often its error function is called"
  [proof]
  (interceptor
   {:name ::proof
    :error (fn [_ ex]
             (swap! proof inc)
             (throw ex))}))

(def prior-error-proof (atom 0))
(def post-error-proof (atom 0))

(use-fixtures :once (test-server-fixture {:graphiql true}
                                         (fn [schema]
                                           {:interceptors (-> (default-interceptors schema {})
                                                              ;; Should not be called
                                                              (inject (proof-interceptor prior-error-proof)
                                                                      :before ::lp/error-response)
                                                              ;; Should be called
                                                              (inject (proof-interceptor post-error-proof)
                                                                      :after  ::lp/error-response))})))

(deftest can-return-failure-response
  (let [response (send-request "{ fail }")]
    (is (= {:body {:errors [{:message "resolver exception"}]}
            :status 500}
           (select-keys response [:status :body])))))

(deftest calls-post-error-interceptor
  (reset! prior-error-proof 0)
  (reset! post-error-proof 0)
  (send-request "{ fail }")
  (is (= 1 @post-error-proof)))

(deftest skips-prior-error-interceptor
  (reset! prior-error-proof 0)
  (reset! post-error-proof 0)
  (send-request "{ fail }")
  (is (= 0 @prior-error-proof)))
