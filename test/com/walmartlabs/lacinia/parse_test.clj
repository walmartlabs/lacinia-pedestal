(ns com.walmartlabs.lacinia.parse-test
  (:require
    [clojure.test :refer :all]
    [com.walmartlabs.lacinia.pedestal :as lp]))


(deftest parse-test
  (are [s r]
    (= r (lp/parse-content-type s))

    "application/json"
    {:content-type :application/json
     :content-type-params {}}

    "application/json;q=0.3"
    {:content-type :application/json
     :content-type-params {:q "0.3"}}

    "text/html; charset=UTF-8"
    {:content-type :text/html
     :content-type-params {:charset "UTF-8"}}

    "application/edn;CharSet=UTF-32"
    {:content-type :application/edn
     :content-type-params {:charset "UTF-32"}}

    "multipart/form-data; boundary=x; charset=US-ASCII"
    {:content-type :multipart/form-data
     :content-type-params {:boundary "x", :charset "US-ASCII"}}

    " application/json "
    {:content-type :application/json
     :content-type-params {}}

    " application/json;  charset=UTF-16 "
    {:content-type :application/json
     :content-type-params {:charset "UTF-16"}}

    "text/html; charset=ISO-8859-4"
    {:content-type :text/html
     :content-type-params {:charset "ISO-8859-4"}}

    ""
    nil

    nil
    nil))
