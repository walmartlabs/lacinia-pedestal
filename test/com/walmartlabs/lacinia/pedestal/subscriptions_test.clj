(ns com.walmartlabs.lacinia.pedestal.subscriptions-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture *ping-subscribes *ping-cleanups]]
    [cheshire.core :as cheshire]
    [gniazdo.core :as g]
    [io.pedestal.log :as log]))

(def ^:private uri "ws://localhost:8888/graphql-ws")

(use-fixtures :once (test-server-fixture {:subscriptions true}))

(def ^:private *messages (atom []))

(defn ^:private store-message
  [message-text]
  (log/debug :reason ::receive :message message-text)
  (swap! *messages conj (cheshire/parse-string message-text true)))

(def ^:private ^:dynamic *session* nil)

(defn send-data
  [data]
  (log/debug :reason ::send-data :data data)
  (g/send-msg *session*
              (cheshire/generate-string data)))

(defn ^:private send-init
  []
  (send-data {:type :connection_init}))

(defn await-messages
  []
  (let [start-ts (System/currentTimeMillis)]
    (loop []
      (cond
        (seq @*messages)
        (let [messages @*messages]
          (swap! *messages empty)
          messages)

        (< 5000 (- (System/currentTimeMillis) start-ts))
        [:timed-out]

        :else
        (do
          (Thread/sleep 50)
          (recur))))))

(def ^:private *id (atom 0))

(use-fixtures :each
  (fn [f]
    (log/debug :reason ::test-start)
    (let [session (g/connect uri
                             :on-receive store-message
                             :on-connect (fn [_] (log/debug :reason ::connected))
                             :on-close #(log/debug :reason ::closed :code %1 :message %2)
                             :on-error #(log/error :reason ::unexpected-error
                                                   :exception %))]
      (try
        (swap! *messages empty)
        (binding [*session* session]
          (send-init)
          (f))
        (finally
          (g/close session))))))

(deftest connect-with-ws
  (is (= [{:type "connection_ack"}]
         (await-messages))))

(deftest ordinary-operation
  (is (= [{:type "connection_ack"}]
         (await-messages)))

  (let [id (swap! *id inc)]
    (send-data {:id id
                :type :start
                :payload
                {:query "{ echo(value: \"ws\") { value }}"}})
    ;; Queries and mutations always deliver a single payload, then
    ;; a complete.
    (is (= [{:id id
             :payload {:data {:echo {:value "ws"}}}
             :type "data"}
            {:id id
             :type "complete"}]
           (await-messages)))))
