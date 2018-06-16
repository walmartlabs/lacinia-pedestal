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

(ns com.walmartlabs.lacinia.async
  "Facilities for leveraging core.async with Lacinia."
  {:added "0.2.0"}
  (:require
    [clojure.core.async :refer [take!]]
    [com.walmartlabs.lacinia.resolve :as resolve]
    [com.walmartlabs.lacinia.util :refer [as-error-map]]))

(defn channel->result
  "Converts a core.async channel into a Lacinia ResolverResult.

  If the channel conveys an exception, that is converted to an error map and delivered (with a nil value).

  Otherwise the first value conveyed is delivered."
  [ch]
  (let [result (resolve/resolve-promise)]
    (take! ch (fn [value]
                (if (instance? Throwable value)
                  (resolve/deliver! result nil (as-error-map value))
                  (resolve/deliver! result value))))
    result))

(defn decorate-channel->result
  "A field resolver decorator that ensures that a field resolver that returns a channel will be wrapped
  to return a ResolverResult."
  [field-resolver]
  ^resolve/ResolverResult
  (fn [context args value]
    (channel->result (field-resolver context args value))))
