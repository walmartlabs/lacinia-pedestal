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

(ns com.walmartlabs.lacinia.indirect-schema-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture subscriptions-fixture
             send-json-request
             send-init send-data
             expect-message *ping-subscribes *ping-cleanups
             *subscriber-id]]))

(use-fixtures :once (test-server-fixture {:graphiql true
                                          :indirect-schema true
                                          :subscriptions true
                                          :keep-alive-ms 200}))

(use-fixtures :each subscriptions-fixture)

(deftest standard-json-request
  (let [response
        (send-json-request :post
                           {:query "{ echo(value: \"hello\") { value method }}"})]
    (is (= 200 (:status response)))
    (is (= {:data {:echo {:method "post"
                          :value "hello"}}}
           (:body response)))))

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
