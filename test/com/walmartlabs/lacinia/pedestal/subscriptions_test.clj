(ns com.walmartlabs.lacinia.pedestal.subscriptions-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.core.async :refer [chan alt!! put! timeout]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture *ping-subscribes *ping-cleanups]]
    [cheshire.core :as cheshire]
    [gniazdo.core :as g]
    [io.pedestal.log :as log]
    [clojure.string :as str]))

(def ^:private uri "ws://localhost:8888/graphql-ws")


(use-fixtures :once (test-server-fixture {:subscriptions true
                                          :keep-alive-ms 200}))

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

(def ^:private *id (atom 0))

(use-fixtures :each
  (fn [f]
    (log/debug :reason ::test-start)
    (let [messages-ch (chan 10)
          session (g/connect uri
                             :on-receive (fn [message-text]
                                           (log/debug :reason ::receive :message message-text)
                                           (put! messages-ch (cheshire/parse-string message-text true)))
                             :on-connect (fn [_] (log/debug :reason ::connected))
                             :on-close #(log/debug :reason ::closed :code %1 :message %2)
                             :on-error #(log/error :reason ::unexpected-error
                                                   :exception %))]

      (binding [*session* session
                ;; New messages channel on each test as well, to ensure failed tests don't cause
                ;; cascading failures.
                *messages-ch* messages-ch]
        (try
          (f)
          (finally
            (log/debug :reason ::test-end)
            (g/close session)))))))

(deftest connect-with-ws
  (send-init)
  (expect-message {:type "connection_ack"}))

(deftest ordinary-operation
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *id inc)]
    (send-data {:id id
                :type :start
                :payload
                {:query "{ echo(value: \"ws\") { value }}"}})
    ;; Queries and mutations always deliver a single payload, then
    ;; a complete.
    (expect-message {:id id
                     :payload {:data {:echo {:value "ws"}}}
                     :type "data"})
    (expect-message {:id id
                     :type "complete"})))

(deftest short-subscription
  (send-init)
  (expect-message {:type "connection_ack"})

  ;; There's an observability issue with core.async, of course, just as there's an observability
  ;; problem inside pure and lazy functions. We have to draw conclusions from some global side-effects
  ;; we've introduced.

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [id (swap! *id inc)]
    (send-data {:id id
                :type :start
                :payload
                {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

    (expect-message {:id id
                     :payload {:data {:ping {:message "short #1"}}}
                     :type "data"})

    (is (> @*ping-subscribes @*ping-cleanups)
        "A subscribe is active, but has not been cleaned up.")

    (expect-message {:id id
                     :payload {:data {:ping {:message "short #2"}}}
                     :type "data"})

    (expect-message {:id id
                     :type "complete"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscription has been cleaned up.")))

(deftest client-stop
  (send-init)
  (expect-message {:type "connection_ack"})

  ;; There's an observability issue with core.async, of course, just as there's an observability
  ;; problem inside pure and lazy functions. We have to draw conclusions from some global side-effects
  ;; we've introduced.

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [id (swap! *id inc)]
    (send-data {:id id
                :type :start
                :payload
                {:query "subscription { ping(message: \"stop\", count: 20 ) { message }}"}})

    (expect-message {:id id
                     :payload {:data {:ping {:message "stop #1"}}}
                     :type "data"})

    (is (> @*ping-subscribes @*ping-cleanups)
        "A subscribe is active, but has not been cleaned up.")

    (expect-message {:id id
                     :payload {:data {:ping {:message "stop #2"}}}
                     :type "data"})

    (send-data {:id id :type :stop})

    (expect-message ::timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscription has been cleaned up.")))

(deftest client-parallel
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [init-subs @*ping-subscribes
        left-id (swap! *id inc)
        right-id (swap! *id inc)]

    (send-data {:id left-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"left\", count: 2 ) { message }}"}})

    (expect-message {:id left-id
                     :payload {:data {:ping {:message "left #1"}}}
                     :type "data"})

    (Thread/sleep 20)

    (send-data {:id right-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"right\", count: 2 ) { message }}"}})

    ;; The timeouts between messages is usually enough to ensure a consistent order.
    ;; But not always ...

    (expect-message {:id right-id
                     :payload {:data {:ping {:message "right #1"}}}
                     :type "data"})

    (is (= 2
           (- @*ping-subscribes init-subs)))

    (expect-message {:id left-id
                     :payload {:data {:ping {:message "left #2"}}}
                     :type "data"})

    (expect-message {:id right-id
                     :payload {:data {:ping {:message "right #2"}}}
                     :type "data"})

    (expect-message {:id left-id
                     :type "complete"})

    (expect-message {:id right-id
                     :type "complete"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-terminates-connection
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [left-id (swap! *id inc)
        right-id (swap! *id inc)]

    (send-data {:id left-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"left\", count: 2 ) { message }}"}})

    (expect-message {:id left-id
                     :payload {:data {:ping {:message "left #1"}}}
                     :type "data"})

    (send-data {:id right-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"right\", count: 2 ) { message }}"}})

    ;; The timeouts between messages should be enough to ensure a consistent order.

    (expect-message {:id right-id
                     :payload {:data {:ping {:message "right #1"}}}
                     :type "data"})

    (send-data {:type :connection_terminate})

    (expect-message ::timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-closes-connection
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [left-id (swap! *id inc)
        right-id (swap! *id inc)]

    (send-data {:id left-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"left\", count: 2 ) { message }}"}})

    (expect-message {:id left-id
                     :payload {:data {:ping {:message "left #1"}}}
                     :type "data"})

    (send-data {:id right-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"right\", count: 2 ) { message }}"}})

    ;; The timeouts between messages should be enough to ensure a consistent order.

    (expect-message {:id right-id
                     :payload {:data {:ping {:message "right #1"}}}
                     :type "data"})

    (g/close *session*)

    (expect-message ::timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-invalid-message
  (send-init)
  (expect-message {:type "connection_ack"})

  (g/send-msg *session* "~~~")

  (let [message (<message!!)]
    (is (= "connection_error"
           (:type message)))
    (is (str/includes? (-> message :payload :message)
                       "Unexpected character"))))

(deftest client-invalid-payload

  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *id inc)]
    (send-data {:id id
                :type :start
                :payload {:query "~~~"}})

    (expect-message {:id id
                     :payload {:message "Failed to parse GraphQL query. Token recognition error at: '~'; No viable alternative at input '<eof>'."}
                     :type "error"})))

(deftest client-keep-alive
  (send-init)
  (expect-message {:type "connection_ack"})

  (dotimes [_ 2]
    (is (= {:type "ka"}
           (<message!! 250)))))
