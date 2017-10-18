(ns com.walmartlabs.lacinia.pedestal.interceptors-test
  (:require
    [clojure.test :refer [deftest is]]
    [com.walmartlabs.lacinia.pedestal.interceptors
     :refer [ordered-after order-by-dependency as-dependency-map dependencies splice]]))

(defn ^:private ordered-names
  [dependency-map]
  (->> dependency-map order-by-dependency (map :name)))

(deftest orders-by-dependency
  (let [dm (as-dependency-map [(ordered-after {:name :later} [:earlier])
                               {:name :earlier}])]
    (is (= [:earlier :later]
           (ordered-names dm)))))

(deftest splice-maintains-order
  (let [dm (as-dependency-map [{:name :moe}
                               (ordered-after {:name :larry} [:moe])
                               (ordered-after {:name :curly} [:moe :larry])])
        dm' (splice dm :curly (ordered-after {:name :shemp} [:harpo]))]
    ;; Explicitly OK that :harpo doesn't exist, for better or worse.
    (is (= [:moe :larry :shemp]
           (ordered-names dm')))
    (is (= #{:harpo :moe :larry}
           (-> dm' :curly dependencies)))))

(deftest spliced-interceptor-must-exist
  (let [dm (as-dependency-map [{:name :moe}
                               (ordered-after {:name :larry} [:moe])
                               (ordered-after {:name :curly} [:moe :larry])])
        e (is (thrown? Throwable (splice dm :groucho {})))]
    (is (= "Unknown interceptor for splice." (.getMessage e)))
    (is (= {:dependency-map dm
            :interceptor-name :groucho}
           (ex-data e)))))
