# com.walmartlabs/lacinia-pedestal

[![Clojars Project](https://img.shields.io/clojars/v/com.walmartlabs/lacinia-pedestal.svg)](https://clojars.org/com.walmartlabs/lacinia-pedestal)

A library that adds the
[Pedestal](https://github.com/pedestal/pedestal) underpinnings needed when exposing
[Lacinia](https://github.com/walmartlabs/lacinia) as an HTTP endpoint.

[API Documentation](http://walmartlabs.github.io/lacinia-pedestal/)

## Usage

For a basic Pedestal server, simply supply a compiled Lacinia schema to
the `com.walmartlabs.lacinia.pedestal/pedestal-service` function to
generate a service, then invoke `io.pedestal.http/start`.

```clojure
;; This example is based off of the code generated from the template
;;  `lein new pedestal-service graphql-demo`

(ns graphql-demo.server
  (:require [io.pedestal.http :as server]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]))

(def hello-schema (schema/compile
                   {:queries {:hello
                              ;; String is quoted here; in EDN the quotation is not required
                              {:type 'String 
                               :resolve (constantly "world")}}}))

(def service (lacinia/pedestal-service hello-schema {:graphiql true}))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server service))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (server/start runnable-service))
```

Lacinia will handle GET and POST requests at the `/graphql` endpoint.

```
$ curl localhost:8888/graphql -X POST -H "content-type: application/graphql" -d '{ hello }'
{"data":{"hello":"world"}}
```

When the `:graphiql` option is true, then a
[GraphiQL](https://github.com/graphql/graphiql) IDE will be available at `/`.

Alternately, you can build you own stack and re-use the individual pieces
supplied here as building blocks.
The steps for processing a GraphQL query are broken into multiple stages:
- Extracting the query string and query variables from the request
- Verifying that a query was included in the request
- Converting the query string to a parsed query
- Defining the application context for executing the query
- Executing the parsed query (possibly, asynchronously)
- Setting the response status
- Encoding the response body as JSON

Each of these steps is its own Pedestal interceptor.

#### Clients

For GET requests, query parameter `query` should be the query to execute.

For POST requests, the content type should be `application/graphql` and the
body of the request should be the query to execute.

In both cases, query variables may be supplied.  The `variables`
query parameter should be the string-ified JSON containing the variables.

The response type will be `application/json`.

#### Response Status

Response status is normally 200.

If the :data key is missing, that indicates a bad request (a failure
during query parse or prepare), and so
the response status is set to 400.

However, you can get more precise control over the response status.
A field resolver function may use the `resolve-as` function to return
an error map.
An error map with a :status key is used to set the overall response
status.
The :status value is expected to be numeric.

Typically, you would make use of this feature when a field resolver utilizes an external
resource (such as a database, or another web service) and that resource
fails; the field resolver can determine an appropriate HTTP status
and include it with an error map.

The response status is the maximum of any :status value of any
error map.

The :error key is `dissoc`'ed from any error maps in the response.

### GraphiQL

The GraphiQL packaged inside the library is built using `npm`, from
version `0.9.3`.

## License

Copyright © 2017 Walmart

Distributed under the Apache Software License 2.0.

GraphiQL has its own [license](https://raw.githubusercontent.com/graphql/graphiql/master/LICENSE).
