# com.walmartlabs/lacinia-pedestal

[![Clojars Project](https://img.shields.io/clojars/v/com.walmartlabs/lacinia-pedestal.svg)](https://clojars.org/com.walmartlabs/lacinia-pedestal)
[![CI](https://github.com/walmartlabs/lacinia-pedestal/actions/workflows/config.yml/badge.svg)](https://github.com/walmartlabs/lacinia-pedestal/actions/workflows/config.yml)

A library that adds the
[Pedestal](https://github.com/pedestal/pedestal) underpinnings needed when exposing
[Lacinia](https://github.com/walmartlabs/lacinia) as an HTTP endpoint.

Lacinia-Pedestal also supports GraphQL subscriptions, using the same protocol
as [Apollo GraphQL](https://github.com/apollographql/subscriptions-transport-ws).

[Lacinia-Pedestal Manual](http://lacinia-pedestal.readthedocs.io/en/latest/) |
[API Documentation](http://walmartlabs.github.io/apidocs/lacinia-pedestal/)

## Usage

For a basic Pedestal server, simply supply a compiled Lacinia schema to
the `com.walmartlabs.lacinia.pedestal2/default-service` function to
generate a service, then invoke `io.pedestal.http/create-server` and `/start`.

```clojure
;; This example is based off of the code generated from the template
;;  `lein new pedestal-service graphql-demo`

(ns graphql-demo.server
  (:require [io.pedestal.http :as http]
            [com.walmartlabs.lacinia.pedestal2 :as lp]
            [com.walmartlabs.lacinia.schema :as schema]))

(def hello-schema 
  (schema/compile
    {:queries 
      {:hello
        ;; String is quoted here; in EDN the quotation is not required 
        ;; You could also use :String
        {:type 'String
         :resolve (constantly "world")}}}))

;; Use default options:
(def service (lp/default-service hello-schema nil))

;; This is an adapted service map, that can be started and stopped.
;; From the REPL you can call http/start and http/stop on this service:
(defonce runnable-service (http/create-server service))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (http/start runnable-service))
```

Lacinia will handle POST requests at the `/api` endpoint:

```
$ curl localhost:8888/api -X POST -H "content-type: application/json" -d '{"query": "{ hello }"}'
{"data":{"hello":"world"}}
```

You can also access the GraphQL IDE at `http://localhost:8888/ide`.

## Development Mode

When developing an application, it is desirable to be able to change the schema
without restarting.
Lacinia-Pedestal supports this: in the above example, the schema passed to
`default-service` could be a _function_ that returns the compiled schema.
It could even be a Var containing the function that returns the compiled schema.

In this way, the Pedestal stack continues to run, but each request rebuilds
the compiled schema based on the latest code you've loaded into the REPL.

## Beyond default-server

`default-server` is intentionally limited, and exists only to help you get started.
Once you start adding anything more complicated, such as authentication, or supporting
multiple schemas (or schema versions) at different paths, 
you will want to simply create your routes and servers in your own code,
using the building-blocks provided by `com.walmartlabs.lacinia.pedestal2`.

### GraphiQL

The GraphiQL packaged inside the library is built using `npm`, from
version `1.4.7`.

If you are including lacinia-pedestal via Git coordinate (rather than a published version
of the library by using a :mvn/version coordinate), then the library will need to be prepped for use 
via `clj -X:deps prep`.
 
The prep action for lacinia-pedestal requires that you have `npm` installed.  
The prep action generates the CSS and JavaScript files that are used
to execute GraphiQL.

## License

Copyright © 2017-2022 Walmart

Distributed under the Apache Software License 2.0.

GraphiQL has its own [license](https://raw.githubusercontent.com/graphql/graphiql/master/LICENSE).
