(ns com.walmartlabs.lacinia.pedestal.subscription-interceptors-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.core.async :refer [chan alt!! put! timeout]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture *ping-subscribes *ping-cleanups
             ws-uri
             subscriptions-fixture
             send-data send-init <message!! expect-message]]
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as s]
    [com.walmartlabs.lacinia.pedestal.interceptors :as i]
    [cheshire.core :as cheshire]
    [clojure.string :as str]))

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
   (-> (s/default-interceptors schema nil)
       (assoc ::invoke-count invoke-count-interceptor)
       (update ::s/execute-operation i/ordered-after [::invoke-count])
       i/order-by-dependency)})

(use-fixtures :once (test-server-fixture {:subscriptions true
                                          :keep-alive-ms 200}
                                         options-builder))

(use-fixtures :each subscriptions-fixture)

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
