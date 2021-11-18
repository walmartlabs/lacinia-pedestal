(defproject com.walmartlabs/lacinia-pedestal "1.1-alpha-1"
  :description "Pedestal infrastructure supporting Lacinia GraphQL"
  :url "https://github.com/walmartlabs/pedestal-lacinia"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [com.walmartlabs/lacinia "1.1-alpha-6"]
                 [com.fasterxml.jackson.core/jackson-core "2.12.3"]
                 [io.pedestal/pedestal.service "0.5.8"]
                 [io.pedestal/pedestal.jetty "0.5.8"]
                 [com.rpl/proxy-plus "0.0.8"]]
  :profiles
  {:dev {:dependencies [[clj-http "3.12.3"]
                        [com.walmartlabs/test-reporting "1.1"]
                        [com.stuartsierra/component "1.0.0"]
                        [expound "0.8.10"]
                        [stylefruits/gniazdo "1.2.0"
                         :exclusions [org.eclipse.jetty.websocket/websocket-client]]
                        [io.aviso/logging "1.0"]]}}
  :jvm-opts ["-Xmx500m"]
  :plugins [[lein-codox "0.10.7"]
            [test2junit "1.3.0"]
            [lein-shell "0.5.0"]]
  :shell {:dir "resources/graphiql"}
  :prep-tasks [["shell" "./build"]]
  :jar-exclusions [#"graphiql/node_.*"
                   #"graphiql/build"
                   #"graphiql/package.json"
                   #".*/\.DS_Store"]
  :codox {:source-uri "https://github.com/walmartlabs/pedestal-lacinia/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}})
