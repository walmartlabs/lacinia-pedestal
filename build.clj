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
  (:require [clj-commons.ansi :refer [pout]]
            [net.lewisship.build :refer [requiring-invoke]]))

(def lib 'com.walmartlabs/lacinia-pedestal)
(def version "1.3.1")

(def jar-params {:project-name lib
                 :version      version})

(defn jar
  [_params]
  (requiring-invoke net.lewisship.build.jar/create-jar jar-params))

(defn deploy
  [_params]
  (requiring-invoke net.lewisship.build.jar/deploy-jar (jar nil)))

(defn codox
  [_params]
  (requiring-invoke net.lewisship.build.codox/generate
                    {:project-name lib
                     :version      version
                     :aliases      [:dev]}))

(defn lint
  "Lint source files using clj-kondo."
  [opts]
  (let [lint-options (merge {:lint ["src" "test"]
                             :config
                             {:linters
                              {:unresolved-symbol
                               {:exclude '[(clojure.test/is [match?])]}}}}
                            opts)
        kondo-run!   (requiring-resolve 'clj-kondo.core/run!)
        kondo-print! (requiring-resolve 'clj-kondo.core/print!)
        results      (kondo-run! lint-options)]
    (kondo-print! results)
    (when (pos? (get-in results [:summary :errors] 0))
      (pout [:red [:bold "ERROR"] ": clj-kondo found errors 😢"])
      (System/exit -1))
    (pout [:bold.green "clj-kondo approves ☺️"])))
