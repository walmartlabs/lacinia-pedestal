# com.walmartlabs/pedestal-lacinia

A library that adds the Pedestal underpinnings needed when exposing
[Lacinia](https://github.com/walmartlabs/lacinia) as an HTTP endpoint.

## Usage

For a basic Pedestal server, simply supply a compiled Lacinia schema to
the `com.walmart.lacinia.pedestal/pedestal-service` function to
generate a service, then invoke `io.pedestal.http/start`.

Lacinia will handle GET and POST requests at the `/graphql` endpoint.

When the `:graphiql` option is supplied, then a
[GraphiQL](https://github.com/graphql/graphiql) IDE will be available at `/`.

Alternately, you can build you own stack and re-use the individual pieces
supplied here as building blocks.
The steps for processing a GraphQL query are broken into multiple steps:
- Extracting the query string and query variables from the request
- Verifying that a query was included in the request
- Converting the query string to a parsed query
- Executing the parsed query
- Setting the response status
- Encoding the response body as JSON

Each of these steps is its own Pedestal interceptor.

## License

Copyright © 2017 Walmart

Distributed under the Apache Software License 2.0.
