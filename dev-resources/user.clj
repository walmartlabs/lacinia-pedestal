(ns user
  (:require
    com.walmartlabs.lacinia.expound
    [clojure.spec.alpha :as s]
    [expound.alpha :as expound]))

(s/check-asserts true)

(alter-var-root #'s/*explain-out* (constantly expound/printer))
