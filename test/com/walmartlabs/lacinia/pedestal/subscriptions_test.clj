(ns com.walmartlabs.lacinia.pedestal.subscriptions-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.test-utils :as tu
     :refer [test-server-fixture *ping-subscribes *ping-cleanups
             ws-uri *session* subscriptions-fixture
             send-data send-init <message!! expect-message
             *subscriber-id]]
    [cheshire.core :as cheshire]
    [gniazdo.core :as g]
    [io.pedestal.log :as log]
    [clojure.string :as str]))

(use-fixtures :once (test-server-fixture {:subscriptions true
                                          :keep-alive-ms 200}))
(use-fixtures :each subscriptions-fixture)

(deftest connect-with-ws
  (send-init)
  (expect-message {:type "connection_ack"}))

(deftest ordinary-operation
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *subscriber-id inc)]
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

  (let [id (swap! *subscriber-id inc)]
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

(deftest client-query-validation-error
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id id
                :type :start
                :payload
                ;; Note: missing selections inside ping field
                {:query "subscription { ping(message: \"short\", count: 2 ) }"}})

    (expect-message {:id id
                     :payload {:locations [{:column 13
                                            :line 1}]
                               :message "Field `ping' (of type `Ping') must have at least one selection."}
                     :type "error"})

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

  (let [id (swap! *subscriber-id inc)]
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

    (expect-message ::tu/timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscription has been cleaned up.")))

(deftest client-parallel
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [init-subs @*ping-subscribes
        left-id (swap! *subscriber-id inc)
        right-id (swap! *subscriber-id inc)]

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

  (let [left-id (swap! *subscriber-id inc)
        right-id (swap! *subscriber-id inc)]

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

    (expect-message ::tu/timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-closes-connection
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [left-id (swap! *subscriber-id inc)
        right-id (swap! *subscriber-id inc)]

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

    (expect-message ::tu/timed-out)

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

(deftest client-query-parse-error

  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *subscriber-id inc)]
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

(deftest client-duplicates-subscription
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [init-subs @*ping-subscribes
        sub-id (swap! *subscriber-id inc)]

    (send-data {:id sub-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"original\", count: 2 ) { message }}"}})

    (expect-message {:id sub-id
                     :payload {:data {:ping {:message "original #1"}}}
                     :type "data"})

    (Thread/sleep 20)

    (send-data {:id sub-id
                :type :start
                :payload
                {:query "subscription { ping(message: \"duplicate\", count: 2 ) { message }}"}})

    (is (= 1 (- @*ping-subscribes init-subs)))

    (expect-message {:id sub-id
                     :payload {:data {:ping {:message "original #2"}}}
                     :type "data"})

    (expect-message {:id sub-id
                     :type "complete"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))
