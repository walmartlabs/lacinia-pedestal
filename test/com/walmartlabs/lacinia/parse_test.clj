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
