; Copyright (c) 2025-present Walmart, Inc.
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

(ns com.walmartlabs.lacinia.pedestal.graphql-ws-test
  "graphql-transport-ws protocol support and the :context-initializer hook for subscriptions2.

  Runs its own subscription server on a separate port so the upstream pedestal3-test fixture
  stays untouched, and opens raw WebSocket connections to control the subprotocol, headers, and
  query string of the upgrade."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.pedestal.subscriptions2 :as subscriptions2]
            [com.walmartlabs.lacinia.test-utils :as tu
             :refer [*ping-context *echo-context *session*
                     send-data send-init <message!! *subscriber-id]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.jetty :as jetty]
            [charred.api :as json]
            [hato.websocket :as ws]
            [clojure.core.async :refer [chan put!]]
            [io.pedestal.log :as log]
            [io.pedestal.connector :as conn]))

(def port 8889)                         ; coexists with pedestal3-test on 8888
(def ws-url (str "ws://localhost:" port "/ws"))

;; --- Authentication ---------------------------------------------------------
;; A :context-initializer reads a credential off the upgrade request and stashes the resulting
;; principal on the connection; an interceptor then merges it into every resolver's app-context.
;; The library has no opinion about "user context" -- this is all application code.

(def ^:private auth-header "authorization")

(defn ^:private principal-for-token
  "Maps a bearer token to a principal, or nil if unrecognized."
  [token]
  (case token
    "token-alice" {:user "alice" :roles #{:admin}}
    "token-bob"   {:user "bob"   :roles #{:viewer}}
    nil))

(defn ^:private request-token
  "Bearer token from the upgrade request: the Authorization header (non-browser clients) or an
  ?access_token=… query param (the channel browsers use, as they cannot set headers)."
  [request]
  (or (get-in request [:headers auth-header])
      (some->> (:query-string request)
               (re-find #"(?:^|&)access_token=([^&]+)")
               second)))

(defn ^:private authenticate
  "Resolves an upgrade request to a principal, or nil for an anonymous connection."
  [request]
  (principal-for-token (request-token request)))

(def ^:private principal-key ::principal)

(defn ^:private inject-principal-interceptor
  "The app-context injector, additionally exposing the connection's principal to resolvers. The
  principal comes from the handshake (:context-initializer) or, failing that, from a token in the
  connection_init payload — the fallback browser clients rely on, since :context-initializer runs
  before that payload arrives. A nil (anonymous) principal merges to nothing."
  []
  (interceptor
    {:name  ::inject-principal
     :enter (fn [context]
              (let [principal (or (get context principal-key)
                                  (principal-for-token (:authToken (:connection-params context))))]
                (assoc-in context [:request :lacinia-app-context]
                          (merge {:request (:request context)} principal))))}))

(defn ^:private auth-subscription-interceptor
  "subscriptions2 endpoint that authenticates the upgrade and exposes the principal to resolvers."
  [schema]
  (subscriptions2/subscription-interceptor
    schema
    {:keep-alive-ms       200
     :context-initializer (fn [context request]
                            (assoc context principal-key (authenticate request)))
     :subscription-interceptors
     [subscriptions2/exception-handler-interceptor
      subscriptions2/send-operation-response-interceptor
      (subscriptions2/query-parser-interceptor schema)
      (inject-principal-interceptor)
      subscriptions2/execute-operation-interceptor]}))

(defn server-fixture
  [f]
  (let [routes    (table/table-routes
                    [["/ws" :get (auth-subscription-interceptor (tu/compile-schema))
                      :route-name ::subscriptions]])
        connector (-> (conn/default-connector-map port)
                      (conn/with-routes routes)
                      (jetty/create-connector nil)
                      (conn/start!))]
    (try
      (f)
      (finally (conn/stop! connector)))))

(use-fixtures :once server-fixture)

;; --- WebSocket helpers ------------------------------------------------------

(defn open-connection
  "Opens a raw WebSocket. `opts` may set :subprotocols, :headers, and :query-string."
  [{:keys [subprotocols headers query-string]
    :or   {subprotocols ["graphql-ws"]}}]
  (let [messages-ch (chan 10)
        url         (cond-> ws-url query-string (str "?" query-string))
        session     @(ws/websocket
                       url
                       (cond-> {:subprotocols subprotocols
                                :on-message (fn [_ msg _last?]
                                              (put! messages-ch (json/read-json (str msg) :key-fn keyword)))
                                :on-error   (fn [_ error]
                                              (log/error :reason ::ws-error :exception error))}
                         headers (assoc :headers headers)))]
    {:session session :messages-ch messages-ch}))

(defmacro with-connection
  "Opens a connection for `opts`, binds the test-utils session/channel vars to it, runs `body`,
  then closes it."
  [opts & body]
  `(let [conn# (open-connection ~opts)]
     (binding [*session*        (:session conn#)
               tu/*messages-ch* (:messages-ch conn#)]
       (try ~@body
            (finally (ws/close! (:session conn#)))))))

(defn <non-ka!!
  "Next message, skipping keep-alives."
  ([] (<non-ka!! 250))
  ([timeout-ms]
   (loop []
     (let [message (<message!! timeout-ms)]
       (if (= message {:type "ka"}) (recur) message)))))

(defn init!
  "Sends connection_init (with optional payload) and asserts the ack."
  ([] (init! nil))
  ([payload]
   (send-init payload)
   (is (= {:type "connection_ack"} (<non-ka!!)))))

(defn start!
  "Starts an operation (op is :start or :subscribe) on a fresh id; returns the id."
  [op query]
  (let [id (swap! *subscriber-id inc)]
    (send-data {:id id :type op :payload {:query query}})
    id))

(defn echo!
  "Runs an echo query and drains both reply messages (data + complete), so the resolver has
  captured its context and the channel is left clean for the next operation."
  []
  (start! :start "{ echo(value: \"ws\") { value }}")
  (<non-ka!!)                            ; data
  (<non-ka!!))                           ; complete

;; --- graphql-transport-ws protocol ------------------------------------------

(deftest transport-ws-ping-pong
  (with-connection {:subprotocols ["graphql-transport-ws"]}
    (init!)
    (send-data {:type :ping})
    (is (= {:type "pong"} (<non-ka!!)))))

(deftest transport-ws-subscribe-and-complete
  ;; "subscribe"/"complete" verbs, with "next" replies rather than the legacy "data".
  (with-connection {:subprotocols ["graphql-transport-ws"]}
    (init!)
    (let [id (start! :subscribe "subscription { ping(message: \"modern\", count: 2) { message }}")]
      (is (= {:id id :type "next" :payload {:data {:ping {:message "modern #1"}}}} (<non-ka!!)))
      (is (= {:id id :type "next" :payload {:data {:ping {:message "modern #2"}}}} (<non-ka!!)))
      (is (= {:id id :type "complete"} (<non-ka!!))))))

(deftest transport-ws-query-uses-next
  ;; A plain query is delivered as "next" then "complete".
  (with-connection {:subprotocols ["graphql-transport-ws"]}
    (init!)
    (let [id (start! :subscribe "{ echo(value: \"modern\") { value }}")]
      (is (= {:id id :type "next" :payload {:data {:echo {:value "modern"}}}} (<non-ka!!)))
      (is (= {:id id :type "complete"} (<non-ka!!))))))

;; --- :context-initializer — who is the connected user? ----------------------
;; The principal is established once at the handshake, from one of three channels, and must be
;; visible to every resolver. The test resolvers capture their app-context into the
;; *echo-context / *ping-context atoms.

(deftest principal-from-authorization-header
  (with-connection {:headers {auth-header "token-alice"}}
    (init!)
    (echo!)
    (is (= {:user "alice" :roles #{:admin}} (select-keys @*echo-context [:user :roles])))))

(deftest principal-from-url-token
  ;; Browsers can't set headers, so they pass the token as ?access_token=… ; the
  ;; :context-initializer reads it from the upgrade request's :query-string. (This also
  ;; confirms pedestal3 surfaces the query-string on the upgrade.)
  (with-connection {:query-string "access_token=token-alice"}
    (init!)
    (echo!)
    (is (= {:user "alice" :roles #{:admin}} (select-keys @*echo-context [:user :roles])))))

(deftest principal-reaches-subscription-streamer
  (with-connection {:headers {auth-header "token-alice"}}
    (init!)
    (start! :start "subscription { ping(message: \"hi\", count: 1) { message }}")
    (<non-ka!!)
    (is (= {:user "alice" :roles #{:admin}} (select-keys @*ping-context [:user :roles])))))

(deftest principal-survives-every-operation
  ;; Per-message app-context injection must not drop the principal after the first operation.
  (with-connection {:headers {auth-header "token-bob"}}
    (init!)
    (dotimes [_ 2]
      (reset! *echo-context nil)
      (echo!)
      (is (= "bob" (:user @*echo-context))))))

(deftest anonymous-connection-has-no-principal
  (with-connection {}
    (init!)
    (echo!)
    (is (nil? (:user @*echo-context)))))

;; --- connection_init payload (::lacinia/connection-params) ------------------
;; The other in-band channel — and, besides the URL, the only one a browser has: a
;; client-supplied connection_init payload, surfaced to resolvers as ::lacinia/connection-params.
;; It must keep working through this server's custom interceptor, independent of the principal.

(deftest connection-init-params-reach-resolver
  (with-connection {}
    (init! {:authToken "abc"})
    (echo!)
    (is (= {:authToken "abc"} (::lacinia/connection-params @*echo-context)))))

(deftest principal-from-connection-init-token
  ;; The browser auth flow end-to-end: the token rides in the connection_init payload (not a
  ;; header or the URL), is authenticated per operation — not by :context-initializer, which
  ;; runs before the payload exists — and the resulting principal reaches the resolver.
  (with-connection {}
    (init! {:authToken "token-alice"})
    (echo!)
    (is (= {:user "alice" :roles #{:admin}} (select-keys @*echo-context [:user :roles])))))

(deftest principal-and-connection-init-params-coexist
  (with-connection {:headers {auth-header "token-alice"}}
    (init! {:locale "en"})
    (echo!)
    (is (= "alice" (:user @*echo-context)))
    (is (= {:locale "en"} (::lacinia/connection-params @*echo-context)))))
