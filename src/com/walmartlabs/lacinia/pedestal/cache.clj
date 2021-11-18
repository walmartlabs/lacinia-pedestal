; Copyright (c) 2021-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.pedestal.cache
  "A parsed query cache can reduce processing time by avoiding the normal parsing of a query."
  {:added "1.1"}
  (:require [com.rpl.proxy-plus :refer [proxy+]])
  (:import (java.util LinkedHashMap Collections)))

(defprotocol ParsedQueryCache

  (get-parsed-query [this cache-key]
    "Returns a parsed query from the cache, if it contains the query.

    The cache-key uniquely identifies the query and the operation name.

    Returns the previously stored parsed query, or nil if not found.")

  (store-parsed-query [this cache-key parsed-query]
    "Stores a parsed query into the cache.

    Returns the parsed query.

    The cache may need to modify the parsed query prior to storing it. It should store
    and return the modified parsed query; the return value is what will be attached
    to the Ring request map."))

(extend-type nil

  ParsedQueryCache

  (get-parsed-query [_ _] nil)

  (store-parsed-query [_ _ parsed-query] parsed-query))

(defn synchronized-lru-map
  "Creates an LRU map, ordered by access, with a maximum number of stored values."
  [max-count]
  (assert (pos-int? max-count))
  (-> (proxy+ [16 0.75 true]

              LinkedHashMap

              (removeEldestEntry [^LinkedHashMap this _]
                                 (> (.size this) max-count)))
      Collections/synchronizedMap))

(defn parsed-query-cache
  "Returns a simple, synchronized, LRU cache."
  [max-count]
  (let [cache (synchronized-lru-map max-count)]
    (reify ParsedQueryCache

      (get-parsed-query [_ cache-key]
        (.get cache cache-key))

      (store-parsed-query [_ cache-key parsed-query]
        (.put cache cache-key parsed-query)
        parsed-query))))