(ns demo
  "Used to demonstrate a simple GraphQL application."
  (:require
    [com.walmartlabs.lacinia.pedestal :as lp]
    [clojure.java.io :as io]
    [clojure.core.async :refer [chan close! go alt! timeout]]
    [com.walmartlabs.lacinia.schema :as schema]
    [clojure.edn :as edn]
    [io.pedestal.http :as http]
    [com.walmartlabs.lacinia.util :as util]))

(defonce server nil)

(defn ticks-streamer
  [context args source-stream]
  (let [abort-ch (chan)]
    (go
      (loop [countdown (-> args :count dec)]
        (if (<= 0 countdown)
          (do
            (source-stream {:count countdown :time-ms (System/currentTimeMillis)})
            (alt!
              abort-ch nil

              (timeout 1000) (recur (dec countdown))))
          (source-stream nil))))
    ;; Cleanup:
    #(close! abort-ch)))

(defn start-server
  [_]
  (let [server (-> "demo-schema.edn"
                   io/resource
                   slurp
                   edn/read-string
                   (util/attach-resolvers {:query/hello (constantly "Welcome to Lacinia-Pedestal")
                                           :tick/time-ms (fn [_ _ tick]
                                                           (-> tick :time-ms str))})
                   (util/attach-streamers {:subscriptions/ticks ticks-streamer})
                   schema/compile
                   (lp/service-map {:graphiql true
                                    :path "/"
                                    :ide-path "/ui"
                                    :ide-headers {"apikey" "mean mister mustard"}
                                    :subscriptions true
                                    :subscriptions-path "/ws"})
                   http/create-server
                   http/start)]
    server))

(defn stop-server
  [server]
  (http/stop server)
  nil)

(defn start
  []
  (alter-var-root #'server start-server)
  :started)

(defn stop
  []
  (alter-var-root #'server stop-server)
  :stopped)

(comment
  (start)
  ;; Open browser to: http://localhost:8888/ui

  (stop)
  )
