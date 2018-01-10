(ns com.walmartlabs.lacinia.pedestal.interceptors-test
  (:require
    [clojure.test :refer [deftest is]]
    [com.walmartlabs.lacinia.pedestal.interceptors
     :refer [ordered-after order-by-dependency as-dependency-map dependencies splice add]]))

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
    ;; :curly has been replaced by :shemp; the key is still :curly, but the
    ;; interceptor name is :shemp.
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

(deftest can-splice-an-interceptor-without-dependencies
  (let [dm (-> {}
               (add {:name ::moe})
               (add {:name ::larry} ::moe)
               (add {:name ::curly})
               (splice ::curly (ordered-after {:name ::shemp} [::larry])))]
    (is (= [::moe ::larry ::shemp]
           (ordered-names dm)))
    (is (= #{::larry}
           (-> dm ::curly dependencies)))))

(deftest add-interceptor
  ;; Test adding both with and without dependencies
  (let [dm (-> {}
               (add {:name ::moe})
               (add {:name ::curly} ::moe))]
    (is (map? (::moe dm)))
    (is (nil? (-> dm ::moe dependencies)))
    (is (= #{::moe}
           (-> dm ::curly dependencies)))))
