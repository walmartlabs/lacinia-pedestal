(defproject com.walmartlabs/lacinia-pedestal "0.4.0"
  :description "Pedestal infrastructure supporting Lacinia GraphQL"
  :url "https://github.com/walmartlabs/pedestal-lacinia"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.walmartlabs/lacinia "0.22.0"]
                 [com.fasterxml.jackson.core/jackson-core "2.9.2"]
                 [io.pedestal/pedestal.service "0.5.3"]
                 [io.pedestal/pedestal.jetty "0.5.3"]
                 [com.stuartsierra/dependency "0.2.0"]]
  :profiles
  {:dev {:dependencies [[clj-http "2.3.0"]

                        ;; Overrides to match version of Jetty via Pedestal:
                        [org.eclipse.jetty.websocket/websocket-client "9.4.0.v20161208"]
                        [stylefruits/gniazdo "1.0.1"
                         :exclusions [org.eclipse.jetty.websocket/websocket-client]]

                        [io.aviso/logging "0.2.0"]]}}
  :jvm-opts ["-Xmx500m"]
  :plugins [[lein-codox "0.10.3"]
            [test2junit "1.3.0"]]
  :jar-exclusions [#".*/\.DS_Store"]
  :codox {:source-uri "https://github.com/walmartlabs/pedestal-lacinia/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}})
