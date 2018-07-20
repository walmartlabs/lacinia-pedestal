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

(ns com.walmartlabs.lacinia.pedestal.subscriptions-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia :as lacinia]
    [com.walmartlabs.lacinia.test-utils :as tu
     :refer [test-server-fixture *ping-subscribes *ping-cleanups *ping-context *echo-context
             ws-uri *session* subscriptions-fixture
             send-data send-init <message!! expect-message
             *subscriber-id]]
    [com.walmartlabs.test-reporting :refer [reporting]]
    [gniazdo.core :as g]
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
                     :payload {:message "Failed to parse GraphQL query. Field `ping' (of type `ping') must have at least one selection."
                               :locations [{:column 16
                                            :line 1}]}
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
                     :payload {:message "Failed to parse GraphQL query. Token recognition error at: '~'; No viable alternative at input '<eof>'."
                               :locations [{:column nil
                                            :line 1}]}
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

(deftest connection-params
  (let [connection-params {:authentication "token"}
        id (swap! *subscriber-id inc)
        query {:id id
               :type :start
               :payload
               {:query "{ echo(value: \"ws\") { value }}"}}
        response {:id id
                  :payload {:data {:echo {:value "ws"}}}
                  :type "data"}
        complete {:id id
                  :type "complete"}]
    ;; send connection-params that will be kept during the whole session
    (send-init connection-params)
    (expect-message {:type "connection_ack"})

    ;; connection params are available in query context
    (send-data query)
    (expect-message response)
    (expect-message complete)
    (reporting {:context @*echo-context}
      (is (= connection-params (::lacinia/connection-params @*echo-context))))

    ;; connection-params are kept for following queries
    (send-data query)
    (expect-message response)
    (expect-message complete)
    (reporting {:context @*echo-context}
      (is (= connection-params (::lacinia/connection-params @*echo-context))))

    ;; connection-params are kept for following subscriptions
    (send-data {:id (swap! *subscriber-id inc)
                :type :start
                :payload
                {:query "subscription { ping(message: \"stop\", count: 1 ) { message }}"}})
    ;; block until streamer has been called
    (<message!! 250)
    (reporting {:context @*ping-context}
      (is (= connection-params (::lacinia/connection-params @*ping-context))))))


