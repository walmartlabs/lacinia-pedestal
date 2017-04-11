(defproject pedestal-lacinia "0.1.0-SNAPSHOT"
  :description "Pedestal infrastructure supporting Lacinia GraphQL"
  :url "https://github.com/walmartlabs/pedestal-lacinia"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.walmartlabs/lacinia "0.14.0"]
                 [com.fasterxml.jackson.core/jackson-core "2.5.3"]
                 [io.pedestal/pedestal.service "0.5.2"]
                 [io.pedestal/pedestal.jetty "0.5.2"]]
  :jvm-opts ["-Xmx500m"]
  :plugins [[lein-codox "0.10.2"]
            [test2junit "1.2.5"]]
  :codox {:source-uri "https://github.com/walmartlabs/pedestal-lacinia/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}})
