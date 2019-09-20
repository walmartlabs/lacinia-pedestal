Interceptors
=============

`com.walmartlabs.lacinia.pedestal <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal.html>` defines Pedestal `interceptors <http://pedestal.io/reference/interceptors>` and supporting code.

The `inject <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal.html#var-inject>` function (added in 0.7.0) adds (or replaces) an interceptor to a seq of interceptors.

Example
--------

Example to inject an interceptor that adds a `:custom-user-info-key` to the Lacinia's app-context (for example with extracted authentication information from the request).

    (ns server
      (:require
       [com.stuartsierra.component :as component]
       [com.walmartlabs.lacinia.pedestal :as pedestal]
       [io.pedestal.http :as http]))
    
    (def user-info-interceptor
      {:enter (fn [{:keys [request] :as context}]
        ;; Retrieve information from for example the request
        (assoc-in context [:request :lacinia-app-context :custom-user-info-key] :some-value})
    
    (defn- inject-user-info-interceptor
      [interceptors]
      (pedestal/inject interceptors
                       user-info-interceptor
                       :after
                       ::pedestal/inject-app-context))
    
    (defn- interceptors [schema]
      (let [options {}
            default-interceptors (pedestal/default-interceptors schema options)]
        (inject-user-info-interceptor default-interceptors)))
    
    (defn- create-server [compiled-schema port]
      (let [options {:graphiql true
                     :interceptors (interceptors compiled-schema)
                     :port port}]
        (-> compiled-schema
            (pedestal/service-map options)
            http/create-server
            http/start)))
    
    (defrecord Server [schema-provider server port]
      component/Lifecycle
      (start [this]
        (let [compiled-schema (:schema schema-provider)
              server' (create-server compiled-schema port)]
          (assoc this :server server')))
      (stop [this]
        (http/stop server)
        (assoc this :server nil)))

Adding the above interceptor makes the `:custom-user-info-key` information available in for example a resolver.

    (defn- some-resolver
      [ds]
      (fn [{:keys [custom-user-info-key] :as context} _ _]
      ;; Do something with user-info
        ))
    
    (defn- resolver-map
      [ds]
      {:query/some-query (some-resolver ds)})
