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

(ns com.walmartlabs.lacinia.pedestal.subscription-interceptors-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.pedestal :refer [inject]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture subscriptions-fixture send-data send-init expect-message]]
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as s]))

(def ^:private *invoke-count (atom 0))

(def ^:private invoke-count-interceptor
  "Used to demonstrate that subscription interceptor customization works."
  (interceptor
    {:name ::invoke-count
     :enter (fn [context]
              (swap! *invoke-count inc)
              context)}))

(defn ^:private options-builder
  [schema]
  {:subscription-interceptors
   ;; Add ::invoke-count, and ensure it executes before ::execute-operation.
   (-> (s/default-subscription-interceptors schema nil)
       (inject invoke-count-interceptor :before ::s/execute-operation))

   :session-initializer
   (fn [_ _]
     (reset! *invoke-count 0))})

(use-fixtures :once (test-server-fixture {:subscriptions true
                                          :keep-alive-ms 200}
                                         options-builder))

(use-fixtures :each (subscriptions-fixture))

(deftest added-interceptor-is-invoked
  (send-init)
  (expect-message {:type "connection_ack"})

  (send-data {:id 987
              :type :start
              :payload
              {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

  (expect-message {:id 987
                   :payload {:data {:ping {:message "short #1"}}}
                   :type "data"})

  (is (= 1 @*invoke-count)
      "The added interceptor has been executed."))

(deftest context-is-created-by-init-context
  (send-init)
  (expect-message {:type "connection_ack"})

  (send-data {:id 987
              :type :start
              :payload
              {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

  (expect-message {:id 987
                   :payload {:data {:ping {:message "short #1"}}}
                   :type "data"}))
