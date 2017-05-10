(ns com.walmartlabs.lacinia.async-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [com.walmartlabs.lacinia.test-utils :refer [sample-schema-fixture
                                                send-request]]))

;; TODO: Some way to verify that processing is actually async.

(use-fixtures :once (sample-schema-fixture {:async true}))

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
