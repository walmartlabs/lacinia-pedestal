; Copyright (c) 2021-present Walmart, Inc.
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

;; clj -T:build <var>

(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as d]))

(def lib 'com.walmartlabs/lacinia-pedestal)
(def version "1.1")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def copy-srcs ["src" "resources"])

(defn clean
  [_]
  (b/delete {:path "target"}))

(def ^:private graphiql-files
  {"graphiql/graphiql.min.js" "graphiql.min.js"
   "graphiql/graphiql.min.css" "graphiql.min.css"
   "es6-promise/dist/es6-promise.auto.min.js" "es6-promise.auto.min.js"
   "react/umd/react.production.min.js" "react.min.js"
   "react-dom/umd/react-dom.production.min.js" "react-dom.min.js"
   "subscriptions-transport-ws/browser/client.js" "subscriptions-transport-ws-browser-client.js"})

(defn prep
  "Runs `npm install` and copies necessary files into class-dir."
  [_]
  (let [{:keys [exit out err] :as process-result}
        (b/process {:command-args ["npm" "ci"]
                    :dir "node"})]
    (when-not (zero? exit)
      (throw (ex-info "npm install failed"
                      process-result)))
    (doseq [[node-path resource-name] graphiql-files
            :let [in-path (str "node/node_modules/" node-path)
                  out-path (str class-dir "/graphiql/" resource-name)]]
      (b/copy-file {:src in-path :target out-path}))))

(defn jar
  [_params]
  (prep nil)
  (let [basis (b/create-basis)]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-pom "templates/pom.xml"
                  :scm {:url "https://github.com/walmartlabs/lacinia-pedestal"
                        :connection "scm:git:git://github.com/walmartlabs/lacinia-pedestal.git"
                        :developerConnection "scm:git:ssh://git@github.com/walmartlabs/lacinia-pedestal.git"
                        :tag version}
                  :src-dirs ["src"]
                  :resource-dirs ["resources"]})
    (b/copy-dir {:src-dirs copy-srcs
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file}))
  (println "Created:" jar-file))

(defn deploy
  [_params]
  (clean nil)
  (jar nil)
  (d/deploy {:installer :remote
             :artifact jar-file
             :pom-file (b/pom-path {:lib lib :class-dir class-dir})
             :sign-releases? true
             :sign-key-id (or (System/getenv "CLOJARS_GPG_ID")
                              (throw (RuntimeException. "CLOJARS_GPG_ID environment variable not set")))}))

(defn codox
  [_params]
  (let [basis (b/create-basis {:extra '{:deps {codox/codox {:mvn/version "0.10.8"
                                                            :exclusions [org.ow2.asm/asm-all]}}}
                               :aliases [:dev]})
        expression `(do
                      ((requiring-resolve 'codox.main/generate-docs)
                       {:metadata {:doc/format :markdown}
                        :source-uri "https://github.com/walmartlabs/lacinia-pedestal/blob/master/{filepath}#L{line}"
                        :name ~(str lib)
                        :version ~version
                        :description "Clojure-native implementation of GraphQL"})
                      nil)
        process-params (b/java-command
                         {:basis basis
                          :main "clojure.main"
                          :main-args ["--eval" (pr-str expression)]})]
    (b/process process-params)))
