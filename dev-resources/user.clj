(ns user
  (:require
    com.walmartlabs.lacinia.expound
    [clojure.spec.alpha :as s]
    [net.lewisship.trace :as trace]
    [clj-commons.pretty.repl :as repl]
    matcher-combinators.test
    [expound.alpha :as expound]))

(s/check-asserts true)

(trace/setup-default)

(repl/install-pretty-exceptions)

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(comment
  (trace/set-enable-trace! false)
  )
