(ns user
  (:require
    com.walmartlabs.lacinia.expound
    [clojure.spec.alpha :as s]
    [net.lewisship.trace :as trace]
    [expound.alpha :as expound]))

(s/check-asserts true)

(trace/setup-default)

(alter-var-root #'s/*explain-out* (constantly expound/printer))
