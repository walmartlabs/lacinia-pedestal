(ns com.walmartlabs.lacinia.test-utils
  (:require
    [clojure.test :refer [is]]
    [clj-http.client :as client]
    [io.pedestal.http :as http]
    [com.walmartlabs.lacinia.pedestal :as lp]
    [clojure.core.async :refer [timeout alt!! chan put!]]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.schema :as schema]
    [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
    [gniazdo.core :as g]
    [io.pedestal.log :as log]
    [cheshire.core :as cheshire]))

(def *echo-context (atom nil))

(defn ^:private resolve-echo
  [context args _]
  (reset! *echo-context context)
  (let [{:keys [value error]} args
        error-map (when error
                    {:message "Forced error."
                     :status error})
        resolved-value {:value value
                        :method (get-in context [:request :request-method])}]
    (resolve-as resolved-value error-map)))

(def *ping-subscribes (atom 0))
(def *ping-cleanups (atom 0))
(def *ping-context (atom nil))

(defn ^:private stream-ping
  [context args source-stream]
  (swap! *ping-subscribes inc)
  (reset! *ping-context context)
  (let [{:keys [message count]} args
        runnable ^Runnable (fn []
                             (dotimes [i count]
                               (source-stream {:message (str message " #" (inc i))
                                               :timestamp (System/currentTimeMillis)})
                               (Thread/sleep 50))

                             (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))

(defn make-service
  "The special option :indirect-schema wraps the schema in a function; this exercises some
  logic that allows a compiled schema to actually be a function that returns the compiled schema."
  [options options-builder]
  (let [schema (-> (io/resource "sample-schema.edn")
                   slurp
                   edn/read-string
                   (util/attach-resolvers {:resolve-echo resolve-echo})
                   (util/attach-streamers {:stream-ping stream-ping})
                   schema/compile
                   (cond-> (:indirect-schema options) constantly))
        options' (merge options
                        (options-builder schema))]
    (lp/service-map schema options')))

(defn test-server-fixture
  "Starts up the test server as a fixture."
  ([options]
   (test-server-fixture options (constantly {})))
  ([options options-builder]
   (fn [f]
     (reset! *ping-subscribes 0)
     (reset! *ping-cleanups 0)
     (let [service (-> (make-service options options-builder)
                       http/create-server
                       http/start)]
       (try
         (f)
         (finally
           (http/stop service)))))))


(defn get-url
  [path]
  (client/get (str "http://localhost:8888" path) {:throw-exceptions false}))

(defn send-request
  "Sends a GraphQL request to the server and returns the response."
  ([query]
   (send-request :get query))
  ([method query]
   (send-request method query nil))
  ([method query vars]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false}
       (cond->
         (= method :get)
         (assoc-in [:query-params :query] query)

         (= method :post)
         (assoc-in [:headers "Content-Type"] "application/graphql")

         (= method :post-json)
         (->
           (assoc-in [:headers "Content-Type"] "application/json")
           (assoc :method :post
                  :body query))

         ;; :post-bad is like :post, but without setting the content type
         (#{:post :post-bad} method)
         (assoc :body query
                :method :post)

         vars
         (assoc-in [:query-params :variables] (cheshire/generate-string vars)))
       client/request
       (update :body #(try
                        (cheshire/parse-string % true)
                        (catch Exception t
                          %))))))


(defn send-json-request
  ([method json]
   (send-json-request method json "application/json; charset=utf-8"))
  ([method json content-type]
   (send-json-request method "/graphql" json content-type))
  ([method path json content-type]
   (-> {:method method
        :url (str "http://localhost:8888" path)
        :throw-exceptions false
        :headers {"Content-Type" content-type}}
       (cond->
         json (assoc :body (cheshire/generate-string json)))
       client/request
       (update :body
               #(try
                  (cheshire/parse-string % true)
                  (catch Exception t %))))))

(defn send-json-string-request
  ([method json]
   (send-json-string-request method json "application/json; charset=utf-8"))
  ([method json content-type]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false
        :headers {"Content-Type" content-type}
        :body json}
       client/request
       (update :body
               #(try
                  (cheshire/parse-string % true)
                  (catch Exception t %))))))

(def ws-uri "ws://localhost:8888/graphql-ws")

(def ^:dynamic *messages-ch* nil)

(def ^:dynamic *session* nil)

(def *subscriber-id (atom 0))

(defn send-data
  [data]
  (log/debug :reason ::send-data :data data)
  (g/send-msg *session*
              (cheshire/generate-string data)))

(defn send-init
  ([]
   (send-init nil))
  ([payload]
   (send-data {:type :connection_init
               :payload payload})))


(defn <message!!
  ([]
   (<message!! 75))
  ([timeout-ms]
   (alt!!
     *messages-ch* ([message] message)

     (timeout timeout-ms) ::timed-out)))

(defmacro expect-message
  [expected]
  `(is (= ~expected
          (<message!!))))

(defn subscriptions-fixture
  [f]
  (log/debug :reason ::test-start)
  (let [messages-ch (chan 10)
        session (g/connect ws-uri
                           :on-receive (fn [message-text]
                                         (log/debug :reason ::receive :message message-text)
                                         (put! messages-ch (cheshire/parse-string message-text true)))
                           :on-connect (fn [_] (log/debug :reason ::connected))
                           :on-close #(log/debug :reason ::closed :code %1 :message %2)
                           :on-error #(log/error :reason ::unexpected-error
                                                 :exception %))]

    (binding [*session* session
              ;; New messages channel on each test as well, to ensure failed tests don't cause
              ;; cascading failures.
              *messages-ch* messages-ch]
      (try
        (f)
        (finally
          (log/debug :reason ::test-end)
          (g/close session))))))
