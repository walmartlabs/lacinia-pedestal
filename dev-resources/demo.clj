(ns demo
  "Used to demonstrate a simple GraphQL application."
  (:require
    [com.walmartlabs.lacinia.pedestal :as lp]
    [com.walmartlabs.lacinia.pedestal2 :as lp2]
    [clojure.java.io :as io]
    [clojure.core.async :refer [chan close! go alt! timeout]]
    [com.walmartlabs.lacinia.schema :as schema]
    [clojure.edn :as edn]
    [io.pedestal.http :as http]
    [com.walmartlabs.lacinia.util :as util]
    [io.pedestal.log :as log]))

(defonce server nil)

(defn ticks-streamer
  [_ args source-stream]
  (let [abort-ch (chan)]
    (log/debug :ticks-streamer args :source source-stream)
    (go
      (loop [countdown (-> args :count dec)]
        (if (<= 0 countdown)
          (do
            (log/debug :count countdown)
            (source-stream {:count countdown :timeMs (System/currentTimeMillis)})

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
                 (util/inject-resolvers {:Query/hello (constantly "Welcome to Lacinia-Pedestal")
                                         :Tick/timeMs (fn [_ _ tick]
                                                        (log/debug :render-tick tick)
                                                        (-> tick :timeMs str))})
                 (util/inject-streamers {:Subscription/ticks ticks-streamer})
                 schema/compile
                 #_ (lp/service-map {:graphiql true
                                  :path "/"
                                  :ide-path "/ui"
                                  :ide-headers {"apikey" "mean mister mustard"}
                                  :ide-connection-params {:moon-base :alpha}
                                  :subscriptions true
                                  :subscriptions-path "/ws"})
                 (lp2/default-service {:ide-headers {"apikey" "mean mister mustard"}
                                       :ide-connection-params {:moon-base :alpha}})
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
  ;; Open browser to: http://localhost:8888/ide

  (stop)
  )
