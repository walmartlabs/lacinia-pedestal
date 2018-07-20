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

(ns com.walmartlabs.lacinia.async-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.core.async :refer [go]]
    [com.walmartlabs.lacinia.async :as async]
    [com.walmartlabs.lacinia.test-utils :refer [test-server-fixture
                                                send-request]]
    [com.walmartlabs.lacinia.resolve :as resolve]))

;; TODO: Some way to verify that processing is actually async.

(use-fixtures :once (test-server-fixture {:async true}))

(deftest async-get
  (let [response (send-request "{ echo(value: \"hello\") { value method }}")]
    (is (= 200 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= {:data {:echo {:method "get"
                          :value "hello"}}}
           (:body response)))))

(deftest async-post
  (let [response (send-request :post "{ echo(value: \"hello\") { value method }}")]
    (is (= 200 (:status response)))
    (is (= {:data {:echo {:method "post"
                          :value "hello"}}}
           (:body response)))))


(defn ^:private make-chan-resolver
  [value]
  ^:channel-result
  (fn [_ _ _]
    (go value)))

(defn ^:private execute-resolver
  [conveyed-value]
  (let [f (async/decorate-channel->result (make-chan-resolver conveyed-value))
        *p (promise)]
    (resolve/on-deliver! (f nil nil nil)
                         (fn [value]
                           (deliver *p value)))
    @*p))

(deftest decorate-chan-normal-case
  (is (= ::value
         (execute-resolver ::value))))

(deftest decorate-chan-exception-case
  (let [*result (promise)
        p (reify

            resolve/ResolverResultPromise

            (deliver! [_ value error]
              (deliver *result (resolve/resolve-as {:value value :error error})))

            resolve/ResolverResult

            (on-deliver! [_ callback]
              (resolve/on-deliver! @*result callback)))]
    (with-redefs [resolve/resolve-promise (constantly p)]
      (is (= {:error {:extensions {:status 500}
                      :message "Resolver exception."}
              :value nil}
             (execute-resolver (ex-info "Resolver exception." {:status 500})))))))
