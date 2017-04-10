# pedestal-lacinia

A library that adds the Pedestal underpinnings needed when exposing
[Lacinia](https://github.com/walmartlabs/lacinia) as an HTTP endpoint.

## Usage

For a basic Pedestal server, simply supply a compiled Lacinia schema to
the `com.walmart.lacinia.pedestal/pedestal-server` function to
generate a server, then invoke `io.pedestal.http/start`.

Lacinia will handle GET and POST requests at the `/graphql` endpoint.

When the `:graphiql` option is supplied, then a
[GraphiQL](https://github.com/graphql/graphiql) IDE will be available at `/`.

## License

Copyright © 2017 Walmart

Distributed under the Apache Software License 2.0.
