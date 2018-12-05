# com.walmartlabs/lacinia-pedestal

[![Clojars Project](https://img.shields.io/clojars/v/com.walmartlabs/lacinia-pedestal.svg)](https://clojars.org/com.walmartlabs/lacinia-pedestal)

[![CircleCI](https://circleci.com/gh/walmartlabs/lacinia-pedestal.svg?style=svg)](https://circleci.com/gh/walmartlabs/lacinia-pedestal)

A library that adds the
[Pedestal](https://github.com/pedestal/pedestal) underpinnings needed when exposing
[Lacinia](https://github.com/walmartlabs/lacinia) as an HTTP endpoint.

Lacinia-Pedestal also supports GraphQL subscriptions, using the same protocol
as [Apollo GraphQL](https://github.com/apollographql/subscriptions-transport-ws).

[Lacinia-Pedestal Manual](http://lacinia-pedestal.readthedocs.io/en/latest/) |
[API Documentation](http://walmartlabs.github.io/apidocs/lacinia-pedestal/)

## Usage

For a basic Pedestal server, simply supply a compiled Lacinia schema to
the `com.walmartlabs.lacinia.pedestal/service-map` function to
generate a service, then invoke `io.pedestal.http/create-server` and `/start`.

```clojure
;; This example is based off of the code generated from the template
;;  `lein new pedestal-service graphql-demo`

(ns graphql-demo.server
  (:require [io.pedestal.http :as http]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]))

(def hello-schema (schema/compile
                   {:queries {:hello
                              ;; String is quoted here; in EDN the quotation is not required
                              {:type 'String
                               :resolve (constantly "world")}}}))

(def service (lacinia/service-map hello-schema {:graphiql true}))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (http/create-server service))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (http/start runnable-service))
```

Lacinia will handle GET and POST requests at the `/graphql` endpoint.

```
$ curl localhost:8888/graphql -X POST -H "content-type: application/graphql" -d '{ hello }'
{"data":{"hello":"world"}}
```

## Development Mode

When developing an application, it is desirable to be able to change the schema
without restarting.
Lacinia-Pedestal supports this: in the above example, the schema passed to
`pedestal-service` could be a _function_ that returns the compiled schema.
It could even be a Var containing the function that returns the compiled schema.

In this way, the Pedestal stack continues to run, but each request rebuilds
the compiled schema based on the latest code you've loaded into the REPL.

### GraphiQL

The GraphiQL packaged inside the library is built using `npm`, from
version `0.12.0`.

## License

Copyright © 2017 Walmart

Distributed under the Apache Software License 2.0.

GraphiQL has its own [license](https://raw.githubusercontent.com/graphql/graphiql/master/LICENSE).
