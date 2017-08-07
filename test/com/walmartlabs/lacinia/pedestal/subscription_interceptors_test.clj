(ns com.walmartlabs.lacinia.pedestal.subscription-interceptors-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.core.async :refer [chan alt!! put! timeout]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture *ping-subscribes *ping-cleanups]]
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as s]
    [com.walmartlabs.lacinia.pedestal.interceptors :as i]
    [cheshire.core :as cheshire]
    [gniazdo.core :as g]
    [io.pedestal.log :as log]
    [clojure.string :as str]))

(def ^:private uri "ws://localhost:8888/graphql-ws")

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

(def ^:private ^:dynamic *messages-ch* nil)

(def ^:private ^:dynamic *session* nil)

(defn send-data
  [data]
  (log/debug :reason ::send-data :data data)
  (g/send-msg *session*
              (cheshire/generate-string data)))

(defn ^:private send-init
  []
  (send-data {:type :connection_init}))

(defn ^:private <message!!
  ([]
   (<message!! 75))
  ([timeout-ms]
   (alt!!
     *messages-ch* ([message] message)

     (timeout timeout-ms) ::timed-out)))

(defmacro ^:private expect-message
  [expected]
  `(is (= ~expected
          (<message!!))))

(use-fixtures :each
  (fn [f]
    (let [messages-ch (chan 10)
          session (g/connect uri
                             :on-receive (fn [message-text]
                                           (log/debug :reason ::receive :message message-text)
                                           (put! messages-ch (cheshire/parse-string message-text true))))]

      (binding [*session* session
                ;; New messages channel on each test as well, to ensure failed tests don't cause
                ;; cascading failures.
                *messages-ch* messages-ch]
        (try
          (reset! *invoke-count 0)
          (f)
          (finally
            (g/close session)))))))

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
