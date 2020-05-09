;  Copyright (c) 2020-present Walmart, Inc.
;
;  Licensed under the Apache License, Version 2.0 (the "License")
;  you may not use this file except in compliance with the License.
;  You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
;  Unless required by applicable law or agreed to in writing, software
;  distributed under the License is distributed on an "AS IS" BASIS,
;  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;  See the License for the specific language governing permissions and
;  limitations under the License.

(ns com.walmartlabs.lacinia.pedestal.ssq
  "Support for Server Stored Queries.

  SSQ execution is typically a separate endpoint from normal (dynamic) query execution; in the SSQ pipeline,
  the query parse step is replaced with a step that obtains the query from an SSQRepository (or
  an in-memory cache).

  Another version is to support dynamic queries in a staging or QA environment but require SSQs in production."
  {:added "0.14.0"}
  (:require
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.internal-utils :refer [cond-let]]
    [com.walmartlabs.lacinia.pedestal.internal :as internal]))

(defprotocol SSQRepository

  "Defines synchronous methods for storing and retrieving query documents from an external repository."

  (find-query [repo query-id]
    "Looks up the provided query in the repo, returning the query document (a string) if found.")

  (store-query [repo query-id query-document]
    "Stores the query into the repo.

    Generally speaking, you should never change the query document stored with a particular query id.
    This is usually accomplished by assigning a query-id that is a hash of the query document."))

;; TODO: If someone really wants to override this, then they will be forced to access
;; some internal functions; we should move the necessary ones to a utils or common namespace.

;; TODO: There isn't a good way to create a alternate endpoint for subscriptions

(defn retrieve-cached-query-interceptor
  "Replaces query parsing in the interceptor chain with access to the query previously stored
   in the repository.

   This is a minimal implementation; a more complete version may do extra logging, authentication,
   etc., but follow the same pattern.

   Responds with a 400 response if the query can't be located by its id, or if
   it can't be parsed."
  ([compiled-schema repo]
   (retrieve-cached-query-interceptor compiled-schema repo (atom {})))
  ([compiled-schema repo *cache]
   (interceptor
     {:name ::retrieve-cached-query
      :enter (fn [context]
               (cond-let
                 :let [{query-id :graphql-query
                        operation-name :graphql-operation-name} (:request context)
                       ;; Because when you parse a query for a specific operation,
                       ;; it is different than when you parse the same query for
                       ;; a different operation.
                       cache-key [query-id operation-name]
                       cached-query (get @*cache cache-key)]

                 ;; There's various races here where multiple threads may
                 ;; read from the repo, parse the query, store it in the cache
                 ;; but all versions of the parsed query are equivalent.

                 cached-query
                 (assoc-in context internal/parsed-query-key-path cached-query)

                 :let [[stored-document e]
                       (try
                         [(find-query repo query-id) nil]
                         (catch Exception e
                           [nil e]))]

                 e
                 (assoc context :response (internal/as-failure-response e))

                 (nil? stored-document)
                 (assoc context :response
                                (internal/failure-response "Stored query not found"))

                 :let [[parsed-query e]
                       (try
                         [(internal/parse-query compiled-schema stored-document operation-name) nil]
                         (catch Exception e
                           [nil e]))]

                 e
                 (assoc context :response (internal/as-failure-response e))

                 :else
                 (do
                   (swap! *cache assoc cache-key parsed-query)
                   (assoc-in context internal/parsed-query-key-path parsed-query))))})))

