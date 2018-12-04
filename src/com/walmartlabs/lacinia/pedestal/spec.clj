; Copyright (c) 2018-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.pedestal.spec
  "Common specifications."
  {:added "0.10.0"}
  (:require
    com.walmartlabs.lacinia.schema                          ; for CompiledSchema
    [clojure.core.async.impl.protocols :refer [Buffer]]
    io.pedestal.interceptor                                 ; for Interceptor
    [clojure.spec.alpha :as s])
  (:import
    (com.walmartlabs.lacinia.schema CompiledSchema)
    (io.pedestal.interceptor Interceptor)))

(when (-> *clojure-version* :minor (< 9))
  (require '[clojure.future :refer [pos-int?]]))

(s/def ::compiled-schema (s/or :direct #(instance? CompiledSchema %)
                               :indirect fn?))

(s/def ::interceptors (s/coll-of ::interceptor))

;; This is a bit narrower than io.pedestal.interceptor's definition; we are excluding
;; things that can be turned into an interceptor (outside of map), to focus on there
;; being a valid name, which is important for com.walmartlabs.lacinia.pedestal/inject.
;; Lastly, we support a handler, which is typically at the end, and is like a nameless interceptor.

(s/def ::interceptor (s/or :handler fn?
                           :interceptor (s/and (s/nonconforming (s/or :interceptor #(instance? Interceptor %)
                                                                      :map map?))
                                               #(-> % :name keyword?))))

(s/def ::app-context map?)

(s/def ::buffer-or-n (s/or :buffer #(satisfies? Buffer %)
                           :n pos-int?))
