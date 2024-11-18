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
            [net.lewisship.build :refer [requiring-invoke]]))

(def lib 'com.walmartlabs/lacinia-pedestal)
(def version "1.3.1")

(def jar-params {:project-name lib
                 :version version})

(def class-dir "target/classes")

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
  (let [{:keys [exit] :as process-result}
        (b/process {:command-args ["npm" "ci"] 
                    :dir "node"})]
    (when-not (zero? exit)
      (throw (ex-info "npm install failed"
                      process-result)))
    (doseq [[node-path resource-name] graphiql-files
            :let [in-path (str "node/node_modules/" node-path)
                  out-path (str class-dir "/graphiql/" resource-name)]]
      (b/copy-file {:src in-path :target out-path}))))

(defn clean
  [_params]
  (b/delete {:path "target"}))

(defn jar
  [_params]
  (clean nil)
  (prep nil)
  (requiring-invoke net.lewisship.build.jar/create-jar jar-params))

(defn deploy
  [_params]
  (requiring-invoke net.lewisship.build.jar/deploy-jar (jar nil)))

(defn codox
  [_params]
  (requiring-invoke net.lewisship.build.codox/generate
                    {:project-name lib
                     :version version
                     :aliases [:dev]}))
