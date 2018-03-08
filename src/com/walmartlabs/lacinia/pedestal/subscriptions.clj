(ns com.walmartlabs.lacinia.pedestal.subscriptions
  "Support for GraphQL subscriptions using Jetty WebSockets, following the design
  of the Apollo client and server."
  {:added "0.3.0"}
  (:require
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.internal-utils :refer [cond-let to-message]]
    [clojure.core.async :as async
     :refer [chan put! close! go-loop <! >! alt! thread]]
    [cheshire.core :as cheshire]
    [io.pedestal.interceptor :refer [interceptor]]
    [io.pedestal.interceptor.chain :as chain]
    [io.pedestal.http.jetty.websockets :as ws]
    [io.pedestal.log :as log]
    [com.walmartlabs.lacinia.parser :as parser]
    [com.walmartlabs.lacinia.validator :as validator]
    [com.walmartlabs.lacinia.executor :as executor]
    [com.walmartlabs.lacinia.constants :as constants]
    [com.walmartlabs.lacinia.resolve :as resolve]
    [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors
     :refer [ordered-after]]
    [clojure.string :as str]))

(defn ^:private xform-channel
  [input-ch output-ch xf]
  (go-loop []
    (if-some [input (<! input-ch)]
      (let [output (xf input)]
        (when (>! output-ch output)
          (recur)))
      (close! output-ch))))

(defn ^:private response-encode-loop
  "Takes values from the input channel, encodes them as a JSON string, and
  puts them into the output-ch."
  [input-ch output-ch]
  (xform-channel input-ch output-ch cheshire/generate-string))

(defn ^:private ws-parse-loop
  "Parses text messages sent from the client into Clojure data with keyword keys,
  which is passed along to the output-ch.

  Parse errors are converted into connection_error messages sent to the response-ch."
  [input-ch output-ch response-data-ch]
  (go-loop []
    (when-some [text (<! input-ch)]
      (when-some [parsed (try
                           (cheshire/parse-string text true)
                           (catch Throwable t
                             (log/debug :event ::malformed-text :message text)
                             (>! response-data-ch
                                 {:type :connection_error
                                  :payload (util/as-error-map t)})))]
        (>! output-ch parsed))
      (recur))))

(defn ^:private execute-query-interceptors
  "Executes the interceptor chain for an operation, and returns
  a channel used to shutdown and cleanup the operation."
  [id payload response-data-ch cleanup-ch context]
  (let [shutdown-ch (chan)
        response-spy-ch (chan 1)
        request (assoc payload
                       :id id
                       :shutdown-ch shutdown-ch
                       :response-data-ch response-spy-ch)]
    ;; When the spy channel is closed, we write the id
    ;; to the cleanup-ch; the containing CSP then removes the
    ;; shutdown-ch from its subs map.
    (go-loop []
      (let [message (<! response-spy-ch)]
        (if (some? message)
          (do
            (>! response-data-ch message)
            (recur))
          (>! cleanup-ch id))))

    ;; Execute the chain, for side-effects.
    (chain/execute (update context :request merge request))

    ;; Return a shutdown channel that the CSP can close to shutdown the subscription
    shutdown-ch))

(defn ^:private connection-loop
  "A loop started for each connection."
  [keep-alive-ms ws-data-ch response-data-ch context]
  (let [cleanup-ch (chan 1)]
    ;; Keep track of subscriptions by (client-supplied) unique id.
    ;; The value is a shutdown channel that, when closed, triggers
    ;; a cleanup of the subscription.
    (go-loop [subs {}]
      (alt!
        cleanup-ch
        ([id]
         (log/debug :event ::cleanup-ch :id id)
         (recur (dissoc subs id)))

        ;; TODO: Maybe only after connection_init?
        (async/timeout keep-alive-ms)
        (do
          (log/debug :event ::timeout)
          (>! response-data-ch {:type :ka})
          (recur subs))

        ws-data-ch
        ([data]
         (if (nil? data)
            ;; When the client closes the connection, any running subscriptions need to
            ;; shutdown and cleanup.
           (do
             (log/debug :event ::client-close)
             (run! close! (vals subs)))
            ;; Otherwise it's a message from the client to be acted upon.
           (let [{:keys [id payload type]} data]
             (case type
               "connection_init"
               (when (>! response-data-ch {:type :connection_ack})
                 (recur subs))

                ;; TODO: Track state, don't allow start, etc. until after connection_init

               "start"
               (if (contains? subs id)
                 (do (log/debug :event ::ignoring-duplicate :id id)
                     (recur subs))
                 (do (log/debug :event ::start :id id)
                     (recur (assoc subs id (execute-query-interceptors id payload response-data-ch cleanup-ch context)))))

               "stop"
               (do
                 (log/debug :event ::stop :id id)
                 (when-some [sub-shutdown-ch (get subs id)]
                   (close! sub-shutdown-ch))
                 (recur subs))

               "connection_terminate"
               (do
                 (log/debug :event ::terminate)
                 (run! close! (vals subs))
                  ;; This shuts down the connection entirely.
                 (close! response-data-ch))

                ;; Not recognized!
               (let [response (cond-> {:type :error
                                       :payload {:message "Unrecognized message type."
                                                 :type type}}
                                id (assoc :id id))]
                 (log/debug :event ::unknown-type :type type)
                 (>! response-data-ch response)
                 (recur subs))))))))))

;; We try to keep the interceptors here and in the main namespace as similar as possible, but
;; there are distinctions that can't be readily smoothed over.

(defn ^:private fix-up-message
  [s]
  (when-not (str/blank? s)
    (-> s
        str/trim
        (str/replace #"\s*\.+$" "")
        str/capitalize)))

(defn ^:private ex-data-seq
  "Uses the exception root causes to build a sequence of non-nil ex-data from each
  exception in the exception stack."
  [t]
  (loop [stack []
         current ^Throwable t]
    (let [stack' (conj stack current)
          next-t (.getCause current)]
      ;; Sometime .getCause returns this, sometimes nil, when the end of the stack is
      ;; reached.
      (if (or (nil? next-t)
              (= current next-t))
        (keep ex-data stack')
        (recur stack' next-t)))))

(defn ^:private construct-exception-payload
  [^Throwable t]
  (cond-let
    :let [errors (->> t
                      ex-data-seq
                      (keep ::errors)
                      first)
          parse-errors (->> errors
                            (keep :parse-error)
                            distinct)]

    (seq parse-errors)
    {:message (str "Failed to parse GraphQL query. "
                   (->> parse-errors
                        (keep fix-up-message)
                        (str/join "; "))
                   ".")}

    ;; Apollo spec only has room for one error, so just use the first

    (seq errors)
    (first errors)

    :else
    ;; Strip off the exception added by Pedestal and convert
    ;; the message into an error map
    {:message (to-message t)}))

(def exception-handler-interceptor
  "An interceptor that implements the :error callback, to send an \"error\" message to the client."
  (interceptor
    {:name ::exception-handler
     :error (fn [context ^Throwable t]
              (let [{:keys [id response-data-ch]} (:request context)
                    ;; Strip off the wrapper exception added by Pedestal
                    payload (construct-exception-payload (.getCause t))]
                (put! response-data-ch {:type :error
                                        :id id
                                        :payload payload})
                (close! response-data-ch)))}))

(def send-operation-response-interceptor
  "Interceptor responsible for the :response key of the context (set when a request
  is either a query or mutation, but not a subscription). The :response data
  is packaged up as the payload of a \"data\" message to the client,
  followed by a \"complete\" message."
  (interceptor
    {:name ::send-operation-response
     :leave (fn [context]
              (when-let [response (:response context)]
                (let [{:keys [id response-data-ch]} (:request context)]
                  (put! response-data-ch {:type :data
                                          :id id
                                          :payload response})
                  (put! response-data-ch {:type :complete
                                          :id id})
                  (close! response-data-ch)))
              context)}))

(defn query-parser-interceptor
  "An interceptor that parses the query and places a prepared and validated
  query into the :parsed-lacinia-query key of the request.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema."
  [compiled-schema]
  (-> {:name ::query-parser
       :enter (fn [context]
                (let [{operation-name :operationName
                       :keys [query variables]} (:request context)
                      actual-schema (if (map? compiled-schema)
                                      compiled-schema
                                      (compiled-schema))
                      parsed-query (try
                                     (parser/parse-query actual-schema query operation-name)
                                     (catch Throwable t
                                       (throw (ex-info (to-message t)
                                                       {::errors (-> t ex-data :errors)}
                                                       t))))
                      prepared (parser/prepare-with-query-variables parsed-query variables)
                      errors (validator/validate actual-schema prepared {})]

                  (if (seq errors)
                    (throw (ex-info "Query validation errors." {::errors errors}))
                    (assoc-in context [:request :parsed-lacinia-query] prepared))))}
      interceptor
      (ordered-after [::exception-handler])))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically."
  [app-context]
  (interceptor
    {:name ::inject-app-context
     :enter (fn [context]
              (assoc-in context [:request :lacinia-app-context] app-context))}))

(defn ^:private execute-operation
  [context parsed-query]
  (let [ch (chan 1)]
    (-> context
        :request
        :lacinia-app-context
        (assoc constants/parsed-query-key parsed-query)
        executor/execute-query
        (resolve/on-deliver! (fn [response]
                               (put! ch (assoc context :response response))))
        ;; Don't execute the query in a limited go block thread
        thread)
    ch))

(defn ^:private execute-subscription
  [context parsed-query]
  (let [source-stream-ch (chan 1)
        {:keys [id shutdown-ch response-data-ch]} (:request context)
        source-stream (fn [value]
                        (if (some? value)
                          (put! source-stream-ch value)
                          (close! source-stream-ch)))
        app-context (-> context
                        :request
                        :lacinia-app-context
                        (assoc constants/parsed-query-key parsed-query))
        cleanup-fn (executor/invoke-streamer app-context source-stream)]
    (go-loop []
      (alt!

        ;; TODO: A timeout?

        ;; This channel is closed when the client sends a "stop" message
        shutdown-ch
        (do
          (close! response-data-ch)
          (cleanup-fn))

        source-stream-ch
        ([value]
         (if (some? value)
           (do
             (-> app-context
                 (assoc ::executor/resolved-value value)
                 executor/execute-query
                 (resolve/on-deliver! (fn [response]
                                        (put! response-data-ch
                                              {:type :data
                                               :id id
                                               :payload response})))
                  ;; Don't execute the query in a limited go block thread
                 thread)
             (recur))
           (do
              ;; The streamer has signalled that it has exhausted the subscription.
             (>! response-data-ch {:type :complete
                                   :id id})
             (close! response-data-ch)
             (cleanup-fn))))))

    ;; Return the context unchanged, it will unwind while the above process
    ;; does the real work.
    context))

(def execute-operation-interceptor
  "Executes a mutation or query operation and sets the :response key of the context,
  or executes a long-lived subscription operation."
  (-> {:name ::execute-operation
       :enter (fn [context]
                (let [request (:request context)
                      parsed-query (:parsed-lacinia-query request)
                      operation-type (-> parsed-query parser/operations :type)]
                  (if (= operation-type :subscription)
                    (execute-subscription context parsed-query)
                    (execute-operation context parsed-query))))}
      interceptor
      (ordered-after [::inject-app-context ::query-parser ::send-operation-response])))

(defn default-interceptors
  "Processing of operation requests from the client is passed through interceptor pipeline.
  The context for the pipeline includes special keys for the necessary channels.

  The :request key is the payload sent from the client, along with additional keys:

  :response-data-ch
  : Channel to which Clojure data destined for the client should be written.
  : This should be closed when the subscription data is exhausted.

  :shutdown-ch
  : This channel will be closed if the client terminates the connection.
    For subscriptions, this ensures that the subscription is cleaned up.

  :id
  : The client-provided string that must be included in the response.

  For mutation and query operations, a :response key is added to the context, which triggers
  a response to the client.

  For subscription operations, it's a bit different; there's no immediate response, but a new CSP
  will work with the streamer defined by the subscription to send a sequence of \"data\" messages
  to the client.

  * ::exception-handler [[exception-handler-interceptor]]
  * ::send-operation-response [[send-operation-response-interceptor]]
  * ::query-parser [query-parser-interceptor]]
  * ::inject-app-context [inject-app-context-interceptor]]
  * ::execute-operation [[execute-operation-interceptor]]

  Returns a map of interceptor ids to interceptors, with dependencies."
  [compiled-schema app-context]
  (let [interceptors [exception-handler-interceptor
                      send-operation-response-interceptor
                      (query-parser-interceptor compiled-schema)
                      (inject-app-context-interceptor app-context)
                      execute-operation-interceptor]]
    (interceptors/as-dependency-map interceptors)))

(defn listener-fn-factory
  "A factory for the function used to create a WS listener.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema.

  Options:

  :keep-alive-ms (default: 30000)
  : The interval at which keep alive messages are sent to the client.

  :app-context
  : The base application context provided to Lacinia when executing a query.

  :subscription-interceptors
  : A seq of interceptors for processing queries.  The default is
    derived from [[default-interceptors]].

  :init-context
  : A function returning the base context for the subscription-interceptors to operate on.
    The function takes the following arguments:
     - the minimal viable context for operation
     - the ServletUpgradeRequest that initiated this connection
     - the ServletUpgradeResponse to the upgrade request
    Defaults to returning the context unchanged.."
  [compiled-schema options]
  (let [{:keys [keep-alive-ms app-context init-context]
         :or {keep-alive-ms 30000
              init-context (fn [ctx & args] ctx)}} options
        interceptors (or (:subscription-interceptors options)
                         (interceptors/order-by-dependency (default-interceptors compiled-schema app-context)))
        base-context (chain/enqueue {::chain/terminators [:response]}
                                    interceptors)]
    (log/debug :event ::configuring :keep-alive-ms keep-alive-ms)
    (fn [req resp ws-map]
      (.setAcceptedSubProtocol resp "graphql-ws")
      (log/debug :event ::upgrade-requested)
      (let [response-data-ch (chan 10)                      ; server data -> client
            ws-text-ch (chan 1)                             ; client text -> server
            ws-data-ch (chan 10)                            ; text -> data
            on-close (fn [_ _]
                       (log/debug :event ::closed)
                       (close! response-data-ch)
                       (close! ws-data-ch))
            base-context (init-context base-context req resp)
            on-connect (fn [session send-ch]
                         (log/debug :event ::connected)
                         (response-encode-loop response-data-ch send-ch)
                         (ws-parse-loop ws-text-ch ws-data-ch response-data-ch)
                         (connection-loop keep-alive-ms ws-data-ch response-data-ch base-context))]
        (ws/make-ws-listener
          {:on-connect (ws/start-ws-connection on-connect)
           ;; TODO: Back-pressure?
           :on-text #(put! ws-text-ch %)
           :on-error #(log/error :event ::error :exception %)
           :on-close on-close})))))
