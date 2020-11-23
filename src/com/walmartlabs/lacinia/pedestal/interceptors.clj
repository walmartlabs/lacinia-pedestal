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

(ns com.walmartlabs.lacinia.pedestal.interceptors
  "Common interceptors between the standard and subscriptions code paths."
  {:added "0.13.0"}
  (:require
    [io.pedestal.interceptor :refer [interceptor]]
    [io.pedestal.interceptor.chain :as chain]))

;; Ideally, this function would go inside .internal, but that causes
;; a dependency cycle with .subscriptions.
(defn ^:no-doc on-enter-app-context-interceptor
  [app-context]
  (fn [context]
    (assoc-in context [:request :lacinia-app-context]
              (assoc app-context :request (:request context)))))

(defn ^:no-doc on-leave-app-context-interceptor
  [context]
  (update context :request dissoc :lacinia-app-context))

(defn ^:no-doc on-error-app-context-interceptor
  [context exception]
  (-> (on-leave-app-context-interceptor context)
      (assoc ::chain/error exception)))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request.

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically; for example, to extract authentication information
  from the request and expose that as app-context keys.

  Deprecated.  Use the versions in [[com.walmartlabs.lacinia.pedestal2]]
  or [[com.walmartlabs.lacinia.pedestal.subscriptions]]."
  {:deprecated "0.14.0"}
  [app-context]
  (interceptor
    ;; This function was moved from the lacinia.pedestal namespace but
    ;; existing application code may still reference it (via
    ;; lacinia.pedestal/inject) to keep the name stable.
    {:name :com.walmartlabs.lacinia.pedestal/inject-app-context
     :enter (on-enter-app-context-interceptor app-context)}))
