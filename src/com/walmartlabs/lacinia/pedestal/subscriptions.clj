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
  (:require [com.walmartlabs.lacinia.pedestal.internal :as internal]
            [clojure.core.async :refer [chan put! close!]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.websocket :as ws]
            [io.pedestal.log :as log]
            [clojure.spec.alpha :as s]
            [com.walmartlabs.lacinia.pedestal.spec :as spec]
            [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors])
  (:import (jakarta.websocket EndpointConfig Session)))

;; We try to keep the interceptors here and in the main namespace as similar as possible, but
;; there are distinctions that can't be readily smoothed over.

(def exception-handler-interceptor
  "An interceptor that implements the :error callback, to send an \"error\" message to the client."
  (interceptor
    {:name  ::exception-handler
     :error internal/error-exception-handler}))

(def send-operation-response-interceptor
  "Interceptor responsible for the :response key of the context (set when a request
  is either a query or mutation, but not a subscription). The :response data
  is packaged up as the payload of a \"data\" message to the client,
  followed by a \"complete\" message."
  (interceptor
    {:name ::send-operation-response
     :leave internal/leave-send-operation-response}))

(defn query-parser-interceptor
  "An interceptor that parses the query and places a prepared and validated
  query into the :parsed-lacinia-query key of the request.

  On exit (on leave, or on error) the key is removed from the request.

  `compiled-schema` may be the actual compiled schema, or a no-arguments function
  that returns the compiled schema."
  [compiled-schema]
  (interceptor
    {:name ::query-parser
     :enter (internal/enter-subscription-query-parser compiled-schema)
     :leave internal/on-leave-query-parser
     :error internal/on-error-query-parser}))

(def execute-operation-interceptor
  "Executes a mutation or query operation and sets the :response key of the context,
  or executes a long-lived subscription operation."
  (interceptor
    {:name ::execute-operation
     :enter internal/enter-execute-operation}))

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

  :context-initializer
  : An optional function `(fn [context session] -> context)` invoked once per WebSocket
    connection, at open time. It receives the base subscription context and the
    jakarta.websocket.Session for the handshake, and the returned context becomes the
    per-connection context (carried into every operation on the connection). This is the
    hook for establishing connection-scoped state -- for example, authenticating the
    handshake (URL token, headers) and attaching the resulting principal so downstream
    resolvers can authorize.

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
  (let [{:keys [keep-alive-ms app-context send-buffer-or-n response-chan-fn values-chan-fn session-initializer
                context-initializer]
         :or {keep-alive-ms 25000
              send-buffer-or-n 10
              response-chan-fn #(chan 10)
              values-chan-fn #(chan 1)}} options
        interceptors (or (:subscription-interceptors options)
                         (default-subscription-interceptors compiled-schema app-context))
        base-context (-> {::internal/values-chan-fn values-chan-fn}
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
                        ;; Build the per-connection context: let the application initialize it from
                        ;; the handshake Session (authentication, etc.), and record the negotiated
                        ;; subprotocol -- authoritative here, straight from the container -- so
                        ;; responses use the right message type ("next" vs. legacy "data").
                        context (cond-> base-context
                                  (fn? context-initializer) (context-initializer session)
                                  :always (assoc ::internal/subprotocol (.getNegotiatedSubprotocol session)))]
                    (internal/response-encode-loop response-data-ch send-ch)
                    (internal/ws-parse-loop session-id ws-text-ch ws-data-ch response-data-ch)
                    (internal/connection-loop session-id keep-alive-ms ws-data-ch response-data-ch context)
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
        (assoc :subprotocols ["graphql-transport-ws" "graphql-ws"]
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
                                                      ::context-initializer
                                                      ::response-ch-fn
                                                      ::values-chan-fn
                                                      ::send-buffer-or-n]))

(s/def ::keep-alive-ms pos-int?)
(s/def ::context-initializer fn?)
(s/def ::subscription-interceptors ::spec/interceptors)
(s/def ::init-context fn?)
(s/def ::response-chan-fn fn?)
(s/def ::values-chan-fn fn?)
(s/def ::send-buffer-or-n ::spec/buffer-or-n)
