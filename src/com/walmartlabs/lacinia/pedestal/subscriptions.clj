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
    [io.pedestal.websocket :as ws]
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
  (:import (jakarta.websocket EndpointConfig Session)))

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

(defn ^:private ws-parse-loop
  "Parses text messages sent from the client into Clojure data with keyword keys,
  which is passed along to the output-ch.

  Parse errors are converted into connection_error messages sent to the response-ch."
  [session-id input-ch output-ch response-data-ch]
  (go-loop []
    (when-some [text (<! input-ch)]
      (when-some [parsed (try
                           (cheshire/parse-string text true)
                           (catch Throwable t
                             (log/trace :event ::malformed-text :message text :session-id session-id)
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
  [session-id keep-alive-ms ws-data-ch response-data-ch context]
  (let [cleanup-ch (chan 1)]
    ;; Keep track of subscriptions by (client-supplied) unique id.
    ;; The value is a shutdown channel that, when closed, triggers
    ;; a cleanup of the subscription.
    (go-loop [connection-state {:subs {} :connection-params nil}]
      (alt!
        cleanup-ch
        ([id]
         (log/trace :event ::cleanup-ch :session-id session-id :id id)
         (recur (update connection-state :subs dissoc id)))

        ;; TODO: Maybe only after connection_init?
        (async/timeout keep-alive-ms)
        (do
          (log/trace :event ::timeout :session-id session-id)
          (>! response-data-ch {:type :ka})
          (recur connection-state))

        ws-data-ch
        ([data]
         (if (nil? data)
           ;; When the client closes the connection, any running subscriptions need to
           ;; shutdown and cleanup.
           (do
             (log/trace :event ::client-close :session-id session-id)
             (run! close! (-> connection-state :subs vals)))
           ;; Otherwise it's a message from the client to be acted upon.
           (let [{:keys [id payload type]} data]
             (case type
               "ping"
               (when (>! response-data-ch {:type :pong})
                 (recur connection-state))
               "connection_init"
               (when (>! response-data-ch {:type :connection_ack})
                 (recur (assoc connection-state :connection-params payload)))

               ;; TODO: Track state, don't allow start, etc. until after connection_init

               ("start" "subscribe")
               (if (contains? (:subs connection-state) id)
                 (do
                   (log/trace :event ::ignoring-duplicate :id id)
                   (recur connection-state))
                 (do
                   (log/trace :event ::start :session-id session-id :id id)
                   (let [merged-context (assoc context :connection-params (:connection-params connection-state))
                         sub-shutdown-ch (execute-query-interceptors id payload response-data-ch cleanup-ch merged-context)]
                     (recur (assoc-in connection-state [:subs id] sub-shutdown-ch)))))

               ("stop" "complete")
               (do
                 (log/trace :event ::stop :id id)
                 (when-some [sub-shutdown-ch (get-in connection-state [:subs id])]
                   (close! sub-shutdown-ch))
                 (recur connection-state))

               "connection_terminate"
               (do
                 (log/trace :event ::terminate :id id)
                 (run! close! (-> connection-state :subs vals))
                 ;; This shuts down the connection entirely.
                 (close! response-data-ch))

               ;; Not recognized!
               (let [response (cond-> {:type :error
                                       :payload {:message "Unrecognized message type."
                                                 :type type}}
                                      id (assoc :id id))]
                 (log/trace :event ::unknown-type :type type :session-id session-id :id id)
                 (>! response-data-ch response)
                 (recur connection-state))))))))))

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
              (let [{:keys [id response-data-ch]} (:request context)
                    ;; Strip off the wrapper exception added by Pedestal
                    payload (construct-exception-payload (.getCause t))]
                (put! response-data-ch {:type :error
                                        :id id
                                        :payload payload})
                (close! response-data-ch)))}))

(defn ^:private protocol-response-type
  [context]
  (case (::subprotocol context)
    ("graphql-transport-ws") :next
    :data))

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
                  (put! response-data-ch {:type (protocol-response-type context)
                                          :id id
                                          :payload response})
                  (put! response-data-ch {:type :complete
                                          :id id})
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
  (let [{:keys [::values-chan-fn request]} context
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
        ;; A streamer *must* succeed and return a cleanup function.  If there's a problem with the arguments,
        ;; it may pass the source-stream a ResolverResult that wraps an error.
        cleanup-fn (executor/invoke-streamer app-context source-stream)
        ;; Track how many streamed values are currently executing queries
        *execution-count (atom 0)
        ;; Track when the streamer has passed a nil to shut down the subscription cleanly
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
             (log/trace :stream-value value :id id)
             (-> app-context
                 (assoc ::executor/resolved-value value)
                 executor/execute-query
                 (resolve/on-deliver! (fn [response]
                                        (log/trace :response response :id id)
                                        (put! response-data-ch
                                              {:type (protocol-response-type context)
                                               :id id
                                               :payload response})
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
        ;; that the subscription is exhausted.  response-data-ch is only closed
        ;; after any currently executing queries have first put their
        ;; responses on it.
        streamer-shutdown-ch
        (do
          (>! response-data-ch {:type :complete
                                :id id})
          (close! response-data-ch)
          (cleanup-fn)
          (log/trace :event :streamer-shutdown :id id))))

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


(defn subscription-websocket-endpoint
  "A factory for the websocket endpoint map.

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

  :idle-timeout-ms Sets the idle timeout on the websocket connection; if omitted, the container default is used.

  :session-initializer Passed the jakarta.websocket.Session to perform any additional initialization.

  :keep-alive-ms (default: 25000)
  : The interval at which keep alive messages are sent to the client.
    Note that configuring this timeout to be at or above 30s conflicts with a default Jetty timeout
    closing websockets after 30s of idle time.

  :app-context
  : The base application context provided to Lacinia when executing a query.

  :subscription-interceptors
  : A seq of interceptors for processing queries.  The default is
    derived from [[default-subscription-interceptors]].

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
  (let [{:keys [keep-alive-ms app-context send-buffer-or-n response-chan-fn values-chan-fn session-initializer context-initializer]
         :or {keep-alive-ms 25000
              send-buffer-or-n 10
              response-chan-fn #(chan 10)
              values-chan-fn #(chan 1)}} options
        interceptors (or (:subscription-interceptors options)
                         (default-subscription-interceptors compiled-schema app-context))
        base-context (-> {::values-chan-fn values-chan-fn
                          }
                         (chain/terminate-when :response)
                         (chain/enqueue interceptors))
        on-open (fn [^Session session ^EndpointConfig config]
                  (let [session-id (.getId session)
                        _ (do
                            (log/trace :event ::connected :id session-id)
                            (when session-initializer
                              (session-initializer session config)))
                        ; server data -> client
                        response-data-ch (response-chan-fn)
                        send-ch (ws/start-ws-connection session {:send-buffer-or-n send-buffer-or-n})
                        ; client text -> server
                        ws-text-ch (chan 1)
                        ; client text -> client data
                        ws-data-ch (chan 10)
                        base-context (as-> base-context context
                                       (if-not (fn? context-initializer) context
                                         (context-initializer context session))
                                       (assoc context ::subprotocol (.getNegotiatedSubprotocol session)))]
                    (response-encode-loop response-data-ch send-ch)
                    (ws-parse-loop session-id ws-text-ch ws-data-ch response-data-ch)
                    (connection-loop session-id keep-alive-ms ws-data-ch response-data-ch base-context)
                    {:response-data-ch response-data-ch
                     :ws-text-ch ws-text-ch
                     :ws-data-ch ws-data-ch
                     :session-id session-id}))
        on-text (fn [{:keys [ws-text-ch]} s]
                  (put! ws-text-ch s))
        on-close (fn [{:keys [response-data-ch ws-data-ch session-id]} _session reason]
                   (log/trace :event ::closed :reason reason :session-id session-id)
                   (close! response-data-ch)
                   (close! ws-data-ch))
        on-error (fn [{:keys [session-id]} _ t]
                   (log/error :event ::error :session-id session-id :exception t))]
    (-> options
        (select-keys [:idle-timeout-ms])
        (assoc :subprotocols ["graphql-ws" "graphql-transport-ws"]
               :on-open on-open
               :on-close on-close
               :on-text on-text
               :on-error on-error))))

(s/fdef subscription-websocket-endpoint
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
