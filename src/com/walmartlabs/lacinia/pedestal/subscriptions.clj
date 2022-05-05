; Copyright (c) 2017-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.pedestal.subscriptions
  "Support for GraphQL subscriptions using Jetty WebSockets, following the design
  of the Apollo client and server."
  {:added "0.3.0"}
  (:require
    [com.walmartlabs.lacinia :as lacinia]
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
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [com.walmartlabs.lacinia.pedestal.spec :as spec]
    [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors])
  (:import
    (org.eclipse.jetty.websocket.api UpgradeRequest)
    (org.eclipse.jetty.websocket.api UpgradeResponse)))

(when (-> *clojure-version* :minor (< 9))
  (require '[clojure.future :refer [pos-int?]]))

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

(defprotocol Ws-Sub-Protocol
  (sub-protocol-name [self]
    "The name of sub-protocol.")
  (connection-loop [self session keep-alive-ms ws-data-ch response-data-ch context]
    "A loop started for each connection.")
  (handle-parse-error [self session t response-data-ch]
    "Handle a json parse error.")
  (->data-message [self id response]
    "Converter for `data` type message.")
  (->complete-message [self id]
    "Converter for `complete` type message.")
  (->error-message [self id payload]
    "Converter for `error` type message."))

(def ^:private graphql-ws
  "Server side implementation for old `graphql-ws` sub-protocol.
  specified by [subscriptions-transport-ws](https://github.com/apollographql/subscriptions-transport-ws/blob/v0.11.0/PROTOCOL.md)"
  (reify Ws-Sub-Protocol
    (sub-protocol-name [_] "graphql-ws")
    (connection-loop [_ _ keep-alive-ms ws-data-ch response-data-ch context]
      (let [cleanup-ch (chan 1)]
        ;; Keep track of subscriptions by (client-supplied) unique id.
        ;; The value is a shutdown channel that, when closed, triggers
        ;; a cleanup of the subscription.
        (go-loop [connection-state {:subs {} :connection-params nil}]
          (alt!
            cleanup-ch
            ([id]
             (log/debug :event ::cleanup-ch :id id)
             (recur (update connection-state :subs dissoc id)))

            ;; TODO: Maybe only after connection_init?
            (async/timeout keep-alive-ms)
            (do
              (log/debug :event ::timeout)
              (>! response-data-ch {:type :ka})
              (recur connection-state))

            ws-data-ch
            ([data]
             (if (nil? data)
               ;; When the client closes the connection, any running subscriptions need to
               ;; shutdown and cleanup.
               (do
                 (log/debug :event ::client-close)
                 (run! close! (-> connection-state :subs vals)))
               ;; Otherwise it's a message from the client to be acted upon.
               (let [{:keys [id payload type]} data]
                 (case type
                   "connection_init"
                   (when (>! response-data-ch {:type :connection_ack})
                     (recur (assoc connection-state :connection-params payload)))

                   ;; TODO: Track state, don't allow start, etc. until after connection_init

                   "start"
                   (if (contains? (:subs connection-state) id)
                     (do
                       (log/debug :event ::ignoring-duplicate :id id)
                       (recur connection-state))
                     (do
                       (log/debug :event ::start :id id)
                       (let [merged-context (assoc context :connection-params (:connection-params connection-state))
                             sub-shutdown-ch (execute-query-interceptors id payload response-data-ch cleanup-ch merged-context)]
                         (recur (assoc-in connection-state [:subs id] sub-shutdown-ch)))))

                   "stop"
                   (do
                     (log/debug :event ::stop :id id)
                     (when-some [sub-shutdown-ch (get-in connection-state [:subs id])]
                       (close! sub-shutdown-ch))
                     (recur connection-state))

                   "connection_terminate"
                   (do
                     (log/debug :event ::terminate)
                     (run! close! (-> connection-state :subs vals))
                     ;; This shuts down the connection entirely.
                     (close! response-data-ch))

                   ;; Not recognized!
                   (let [response (cond-> {:type :error
                                           :payload {:message "Unrecognized message type."
                                                     :type type}}
                                          id (assoc :id id))]
                     (log/debug :event ::unknown-type :type type)
                     (>! response-data-ch response)
                     (recur connection-state))))))))))
    (handle-parse-error [_ _ t response-data-ch]
      (put! response-data-ch {:type :connection_error
                              :payload (util/as-error-map t)}))
    (->data-message [_ id response] {:type :data
                                     :id id
                                     :payload response})
    (->complete-message [_ id] {:type :complete
                                :id id})
    (->error-message [_ id payload] {:type :error
                                     :id id
                                     :payload payload})))

(def ^:private graphql-transport-ws
  "Server side implementation for `graphql-transport-ws` sub-protocol.
  specified by [graphql-ws](https://github.com/enisdenjo/graphql-ws/blob/v5.7.0/PROTOCOL.md)"
  (reify Ws-Sub-Protocol
    (sub-protocol-name [_] "graphql-transport-ws")
    (connection-loop [self session _ ws-data-ch response-data-ch context]
      (let [cleanup-ch (chan 1)]
        ;; Keep track of subscriptions by (client-supplied) unique id.
        ;; The value is a shutdown channel that, when closed, triggers
        ;; a cleanup of the subscription.
        (go-loop [connection-state {:subs {} :connection-params nil :initialized? false}]
          (alt!
            cleanup-ch
            ([id]
             (log/debug :event ::cleanup-ch :id id)
             (recur (update connection-state :subs dissoc id)))

            ws-data-ch
            ([data]
             (if (nil? data)
               ;; When the client closes the connection, any running subscriptions need to
               ;; shutdown and cleanup.
               (do
                 (log/debug :event ::client-close)
                 (run! close! (-> connection-state :subs vals)))
               ;; Otherwise it's a message from the client to be acted upon.
               (let [{:keys [id payload type]} data]
                 (case type
                   "connection_init"
                   (if (:initialized? connection-state)
                     (do
                       (log/debug :event ::error-too-many-init)
                       (run! close! (-> connection-state :subs vals))
                       (.close session 4429 "Too many initialisation requests."))
                     (when (>! response-data-ch {:type :connection_ack})
                       (recur (assoc connection-state :connection-params payload :initialized? true))))

                   "ping"
                   (when (>! response-data-ch {:type :pong})
                     (recur connection-state))

                   "pong"
                   ;; Accept but ignore pong message
                   (recur connection-state)

                   ;; TODO: Track state, don't allow start, etc. until after connection_init

                   "subscribe"
                   (if (contains? (:subs connection-state) id)
                     (do
                       (log/debug :event ::error-duplicate-id :id id)
                       (run! close! (-> connection-state :subs vals))
                       (.close session 4409 (str "Subscriber for " id " already exists")))
                     (do
                       (log/debug :event ::start :id id)
                       (let [merged-context (assoc context :connection-params (:connection-params connection-state))
                             sub-shutdown-ch (execute-query-interceptors id payload response-data-ch cleanup-ch merged-context)]
                         (recur (assoc-in connection-state [:subs id] sub-shutdown-ch)))))

                   "complete"
                   (do
                     (log/debug :event ::stop :id id)
                     (when-some [sub-shutdown-ch (get-in connection-state [:subs id])]
                       (close! sub-shutdown-ch))
                     (recur connection-state))

                   ;; Not recognized!
                   (do
                     (log/debug :event ::unknown-type :type type)
                     (run! close! (-> connection-state :subs vals))
                     (.close session 4400 "Unrecognized message type."))))))))))
    (handle-parse-error [_ session t response-data-ch]
      (.close session 4400 "Invalid JSON.")
      (close! response-data-ch))
    (->data-message [_ id response] {:type :next
                                     :id id
                                     :payload response})
    (->complete-message [_ id] {:type :complete
                                :id id})
    (->error-message [_ id payload] {:type :error
                                     :id id
                                     :payload payload})))

(defn ^:private ws-parse-loop
  "Parses text messages sent from the client into Clojure data with keyword keys,
  which is passed along to the output-ch.

  Parse errors are converted into connection_error messages sent to the response-ch."
  [sub-protocol session input-ch output-ch response-data-ch]
  (go-loop []
    (when-some [text (<! input-ch)]
      (when-some [parsed (try
                           (cheshire/parse-string text true)
                           (catch Throwable t
                             (log/debug :event ::malformed-text :message text)
                             (handle-parse-error sub-protocol session t response-data-ch)))]
        (>! output-ch parsed))
      (recur))))

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
                            (keep :message)
                            distinct)
          locations (->> (mapcat :locations errors)
                         (remove nil?)
                         distinct
                         seq)]

    (seq parse-errors)
    (cond-> {:message (str "Failed to parse GraphQL query. "
                           (->> parse-errors
                                (keep fix-up-message)
                                (str/join "; "))
                           ".")}
      locations (assoc :locations locations))

    ;; Apollo spec only has room for one error, so just use the first

    (seq errors)
    (cond-> (first errors)
      locations (assoc :locations locations))

    :else
    ;; Strip off the exception added by Pedestal and convert
    ;; the message into an error map
    (cond-> {:message (to-message t)}
      locations (assoc :locations locations))))

(def exception-handler-interceptor
  "An interceptor that implements the :error callback, to send an \"error\" message to the client."
  (interceptor
    {:name ::exception-handler
     :error (fn [context ^Throwable t]
              (let [{:keys [::sub-protocol]} context
                    {:keys [id response-data-ch]} (:request context)
                    ;; Strip off the wrapper exception added by Pedestal
                    payload (construct-exception-payload (.getCause t))]
                (put! response-data-ch (->error-message sub-protocol id payload))
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
                (let [sub-protocol (::sub-protocol context)
                      {:keys [id response-data-ch]} (:request context)]
                  (put! response-data-ch (->data-message sub-protocol id response))
                  (put! response-data-ch (->complete-message sub-protocol id))
                  (close! response-data-ch)))
              context)}))

(defn ^:private on-leave-query-parser
  [context]
  (update context :request dissoc :parsed-lacinia-query))

(defn ^:private add-error
  [context exception]
  (assoc context ::chain/error exception))

(defn ^:private on-error-query-parser
  [context exception]
  (-> (on-leave-query-parser context)
      (add-error exception)))


(defn query-parser-interceptor
  "An interceptor that parses the query and places a prepared and validated
  query into the :parsed-lacinia-query key of the request.

  On exit (on leave, or on error) the key is removed from the request.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema."
  [compiled-schema]
  (interceptor
    {:name ::query-parser
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
                  (assoc-in context [:request :parsed-lacinia-query] prepared))))
     :leave on-leave-query-parser
     :error on-error-query-parser}))

(defn ^:private execute-operation
  [context parsed-query]
  (let [ch (chan 1)]
    (-> context
        (get-in [:request :lacinia-app-context])
        (assoc
          ::lacinia/connection-params (:connection-params context)
          constants/parsed-query-key parsed-query)
        executor/execute-query
        (resolve/on-deliver! (fn [response]
                               (put! ch (assoc context :response response))))
        ;; Don't execute the query in a limited go block thread
        thread)
    ch))

(defn ^:private execute-subscription
  [context parsed-query]
  (let [{:keys [::sub-protocol ::values-chan-fn request]} context
        source-stream-ch (values-chan-fn)
        {:keys [id shutdown-ch response-data-ch]} request
        source-stream (fn accept-value [value]
                        (cond
                          (nil? value)
                          (close! source-stream-ch)

                          (resolve/is-resolver-result? value)
                          (resolve/on-deliver! value accept-value)

                          :else
                          (put! source-stream-ch value)))
        app-context (-> context
                        (get-in [:request :lacinia-app-context])
                        (assoc
                          ::lacinia/connection-params (:connection-params context)
                          constants/parsed-query-key parsed-query))
        cleanup-fn (executor/invoke-streamer app-context source-stream)
        ;; Track how many streamed values are currently executing queries
        *execution-count (atom 0)
        ;; Track when the streamer has passed a nil to shutdown the subscription cleanly
        *shutting-down? (atom false)
        ;; Closed when shutting down and execution count drops to 0
        streamer-shutdown-ch (chan)]
    (go-loop []
      (alt!

        ;; TODO: A timeout?

        ;; This channel is closed when the client sends a "stop" message;
        ;; any currently executing subscriptions (or executions of streamed
        ;; values) are discarded.
        shutdown-ch
        (do
          (close! response-data-ch)
          (cleanup-fn))

        source-stream-ch
        ([value]
         (cond

           (some? value)
           (do
             (swap! *execution-count inc)
             (-> app-context
                 (assoc ::executor/resolved-value value)
                 executor/execute-query
                 (resolve/on-deliver! (fn [response]
                                        (put! response-data-ch
                                              (->data-message sub-protocol id response))
                                        (let [new-count (swap! *execution-count dec)]
                                          (when (and @*shutting-down?
                                                     (zero? new-count))
                                            (close! streamer-shutdown-ch)))))
                 ;; Don't execute the query in a limited go block thread
                 thread))

           (= 0 @*execution-count)
           (close! streamer-shutdown-ch)

           :else
           (reset! *shutting-down? true))
         (recur))

        ;; This is a clean shutdown from a streamer that signaled (via passing a nil)
        ;; that the subscription is exhausted. response-data-ch is only closed
        ;; after any currently executing queries have first put their
        ;; responses on it.
        streamer-shutdown-ch
        (do
          (log/debug :event ::completed :id id)
          (>! response-data-ch (->complete-message sub-protocol id))
          (close! response-data-ch)
          (cleanup-fn))))

    ;; Return the context unchanged, it will unwind while the above process
    ;; does the real work.
    context))

(def execute-operation-interceptor
  "Executes a mutation or query operation and sets the :response key of the context,
  or executes a long-lived subscription operation."
  (interceptor
    {:name ::execute-operation
     :enter (fn [context]
              (let [request (:request context)
                    parsed-query (:parsed-lacinia-query request)
                    operation-type (-> parsed-query parser/operations :type)]
                (if (= operation-type :subscription)
                  (execute-subscription context parsed-query)
                  (execute-operation context parsed-query))))}))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request.

  The key is removed on exit (on leave, or on error).

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically; for example, to extract authentication information
  from the request and expose that as app-context keys."
  {:added "0.14.0"}
  [app-context]
  (interceptor
    {:name ::inject-app-context
     :enter (interceptors/on-enter-app-context-interceptor app-context)
     :leave interceptors/on-leave-app-context-interceptor
     :error interceptors/on-error-app-context-interceptor}))

(defn default-subscription-interceptors
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

  * ::exception-handler -- [[exception-handler-interceptor]]
  * ::send-operation-response -- [[send-operation-response-interceptor]]
  * ::query-parser -- [[query-parser-interceptor]]
  * ::inject-app-context -- [[inject-app-context-interceptor]]
  * ::execute-operation -- [[execute-operation-interceptor]]

  Returns a vector of interceptors."
  [compiled-schema app-context]
  [exception-handler-interceptor
   send-operation-response-interceptor
   (query-parser-interceptor compiled-schema)
   (inject-app-context-interceptor app-context)
   execute-operation-interceptor])


(defn listener-fn-factory
  "A factory for the function used to create a WS listener.

  This function is invoked for each new client connecting to the service.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema.

  Once a subscription is initiated, the flow is:

  streamer -> values channel -> resolver -> response channel -> send channel

  The default channels are all buffered and non-lossy, which means that a very active streamer
  may be able to saturate the web socket used to send responses to the client.
  By introducing lossiness or different buffers, the behavior can be tuned.

  Each new subscription from the same client will invoke a new streamer and create a
  corresponding values channel, but there is only one response channel per client.

  Options:

  :keep-alive-ms (default: 25000)
  : The interval at which keep alive messages are sent to the client.
    Note that configuring this timeout to be at or above 30s conflicts with a default Jetty timeout
    closing websockets after 30s of idle time.
    Note that effective only for old `graphql-ws` sub-protocol.

  :app-context
  : The base application context provided to Lacinia when executing a query.

  :subscription-interceptors
  : A seq of interceptors for processing queries.  The default is
    derived from [[default-subscription-interceptors]].

  :init-context
  : A function returning the base context for the subscription-interceptors to operate on.
    The function takes the following arguments:
     - the minimal viable context for operation
     - the ServletUpgradeRequest that initiated this connection
     - the ServletUpgradeResponse to the upgrade request
    Defaults to returning the context unchanged.

  :response-chan-fn
  : A function that returns a new channel. Responses to be written to client are put into this
    channel. The default is a non-lossy channel with a buffer size of 10.

  :values-chan-fn
  : A function that returns a new channel. The channel conveys the values provided by the
    subscription's streamer. The values are executed as queries, then transformed into responses that are
    put into the response channel. The default is a non-lossy channel with a buffer size of 1.

  :send-buffer-or-n
  : Used to create the channel of text responses sent to the client. The default is 10 (a non-lossy
    channel)."
  [compiled-schema options]
  (let [{:keys [keep-alive-ms app-context init-context send-buffer-or-n response-chan-fn values-chan-fn]
         :or {keep-alive-ms 25000
              send-buffer-or-n 10
              response-chan-fn #(chan 10)
              values-chan-fn #(chan 1)
              init-context (fn [ctx & _args] ctx)}} options
        interceptors (or (:subscription-interceptors options)
                         (default-subscription-interceptors compiled-schema app-context))
        base-context (chain/enqueue {::chain/terminators [:response]
                                     ::values-chan-fn values-chan-fn}
                                    interceptors)]
    (log/debug :event ::configuring :keep-alive-ms keep-alive-ms)
    (fn [req resp _ws-map]
      (log/debug :event ::upgrade-requested)
      (let [sub-protocol (if (get (set (.getSubProtocols ^UpgradeRequest req)) "graphql-transport-ws")
                           graphql-transport-ws
                           graphql-ws)
            _ (log/debug :event ::set-sub-protocol :name (sub-protocol-name sub-protocol))
            _ (.setAcceptedSubProtocol ^UpgradeResponse resp (sub-protocol-name sub-protocol))
            response-data-ch (response-chan-fn)             ; server data -> client
            ws-text-ch (chan 1)                             ; client text -> server
            ws-data-ch (chan 10)                            ; client text -> client data
            on-close (fn [status-code _]
                       (log/debug :event ::closed :status status-code)
                       (close! response-data-ch)
                       (close! ws-data-ch))
            base-context' (-> base-context
                              (init-context req resp)
                              (assoc ::sub-protocol sub-protocol))
            on-connect (fn [session send-ch]
                         (log/debug :event ::connected)
                         (response-encode-loop response-data-ch send-ch)
                         (ws-parse-loop sub-protocol session ws-text-ch ws-data-ch response-data-ch)
                         (connection-loop sub-protocol session keep-alive-ms ws-data-ch response-data-ch base-context'))]
        (ws/make-ws-listener
          {:on-connect (ws/start-ws-connection on-connect send-buffer-or-n)
           :on-text #(put! ws-text-ch %)
           :on-error #(log/error :event ::error :exception %)
           :on-close on-close})))))

(s/fdef listener-fn-factory
  :args (s/cat :compiled-schema ::spec/compiled-schema
               :options (s/nilable ::listener-fn-factory-options)))

(s/def ::listener-fn-factory-options (s/keys :opt-un [::keep-alive-ms
                                                      ::spec/app-context
                                                      ::subscription-interceptors
                                                      ::init-context
                                                      ::response-ch-fn
                                                      ::values-chan-fn
                                                      ::send-buffer-or-n]))

(s/def ::keep-alive-ms pos-int?)
(s/def ::subscription-interceptors ::spec/interceptors)
(s/def ::init-context fn?)
(s/def ::response-chan-fn fn?)
(s/def ::values-chan-fn fn?)
(s/def ::send-buffer-or-n ::spec/buffer-or-n)
