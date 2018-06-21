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
    [clojure.core.async.impl.protocols :refer [Buffer]]
    [io.pedestal.interceptor :refer [IntoInterceptor]]
    [clojure.spec.alpha :as s])
  (:import
    (com.walmartlabs.lacinia.schema CompiledSchema)))

(s/def ::compiled-schema (s/or :direct #(instance? CompiledSchema %)
                               :indirect fn?))

(s/def ::interceptors (s/coll-of ::interceptor))
(s/def ::interceptor #(satisfies? IntoInterceptor %))

(s/def ::app-context map?)

(s/def ::buffer-or-n (s/or :buffer #(satisfies? Buffer %)
                           :n pos-int?))
