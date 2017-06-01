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
  to return a ResolverResult.

  Only functions that have the meta-data :channel-result are wrapped."
  [object-name field-name f]
  (if (-> f meta :channel-result)
    ^resolve/ResolverResult
    (fn [context args value]
      (channel->result (f context args value)))
    f))
