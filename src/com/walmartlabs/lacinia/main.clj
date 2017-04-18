(ns com.walmartlabs.lacinia.main
  (:require
   [clojure.java.io :as io]
   [io.pedestal.http :as http]
   [com.walmartlabs.lacinia.pedestal :as lp]
   [com.walmartlabs.lacinia.db :as db]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia.util :as util]
   [clojure.edn :as edn]))

(defn ^:private parse-schema
  ([] (parse-schema "star-wars-schema.edn"))
  ([schema-file]
   (-> (io/resource schema-file)
       slurp
       (edn/read-string))))

(defn star-wars-schema
  "Construct a simple schema that we can use."
  []
  (-> (parse-schema)
      (util/attach-resolvers {:resolve-hero    db/resolve-hero
                              :resolve-human   db/resolve-human
                              :resolve-droid   db/resolve-droid
                              :resolve-friends db/resolve-friends})
      schema/compile))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nStarting your server at http://localhost:8888/")
  (let [test-schema (star-wars-schema)
        service (lp/pedestal-service test-schema {:graphiql true})]
    (http/start service)))
