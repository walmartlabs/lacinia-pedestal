## 0.9.0 -- 21 Jun 2018

Invalid JSON sent to lacinia-pedestal now results in proper 400 reponse,
rather than throwing an exception (and a 500 response).

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/8?closed=1)

## 0.8.0 -- 6 Jun 2018

The `com.walmartlabs.lacinia.pedestal.interceptors` namespaces has been
removed outright, and with it, support for interceptor dependency maps.
A scattering of other functions related to dependency maps have also been removed.

Added a number of options to control the creation of core.async channels used
to manage the flow of data for subscriptions.

Dependencies have been updated to latest.

For subscription requests, the connection parameters are now captured at connection time,
and available as the `:com.walmartlabs.lacinia/connection-params` key of the application context.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/7?closed=1)

## 0.7.0 -- 2 Mar 2018

The `com.walmartlabs.lacina.pedestal.interceptors` namespace has been deprecated; it will be removed
in the 0.8.0 release.
Instead, use the new `inject` function, which adds (or replaces) an interceptor to a seq of
interceptors.

The `interceptors` namespace, and other functions related to interceptor maps, will be removed
in the 0.8.0 release.  All functions scheduled for removal have been so marked in their docstrings.

The :api-key option has been removed, replaced with the :ide-headers option.
This is a more general approach that supports multiple headers with arbitrary names.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/6?closed=1)

## 0.6.0 -- 30 Jan 2018

It is now possible to configure the paths used to access the GraphQL endpoint and
the GraphiQL IDE.

A few functions that were inadventently made public have been made private.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/5?closed=1)

## 0.5.0 -- 5 Dec 2017

New function `com.walmartlabs.lacinia.pedestal/service-map` is now preferred
over `pedestal-service` (which has been deprecated).
`service-map` does *not* create the server; that is now the responsibility
of the calling code,
which makes it far easier to customize the service map before creating a server
from it.

Pedestal 0.5.3 includes a `Content-Security-Policy` header by default that disables
GraphiQL.
This header is now disabled when GraphiQL is enabled.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/4?closed=1)

## 0.4.0 -- 24 Oct 2017

The compiled-schema passed to `com.walmartlabs.lacinia.pedestal/pedestal-service` may now
be a function that _returns_ the compiled schema, which is useful when during testing
and development.

Added `com.walmartlabs.lacinia.pedestal.interceptors/splice`, which is
used to substitute an existing interceptor with a replacement.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/3?closed=1)

## 0.3.0 -- 7 Aug 2017

Added support for GraphQL subscriptions!
Subscriptions are patterned after [Apollo GraphQL](http://dev.apollodata.com/tools/graphql-subscriptions/index.html).
lacinia-pedestal should be a drop-in replacement for Apollo GraphQL server.

The default interceptor stack has been reordered, slightly.
New functions have been added to get the interceptors as a map
annotated with dependency meta-data, and to extract
the ordered interceptors from such a map.

`com.walmartlabs.lacinia.pedestal/graphql-routes` has been refactored:
it can be used as before, but its logic has been largely refactored
into smaller, reusable functions that are used when the default
interceptor stack must be modified for the application.

Error during request processing are now reported, in the response, in a more
consistent fashion.

This version of lacinia-pedestal only works with com.walmartlabs/lacinia **0.19.0** and above.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/2?closed=1)

## 0.2.0 -- 1 Jun 2017

The library and GitHub project have been renamed from `pedestal-lacinia` to
`lacinia-pedestal`.

Updated to com.walmartlabs/lacinia dependency to version 0.17.0.

Added an option to execute the GraphQL request asynchronously; when enabled,
the handler returns a channel that conveys the Pedestal context containing
the response, once the query has finished executing.

Introduced function `com.walmartlabs.lacinia.pedestal/inject-app-context-interceptor` and
converted `query-executor-handler` from a function to constant.

A new namespace, `com.walmartlabs.lacinia.async`, includes functions to adapt
field resolvers that return clojure.core.async channels to Lacinia.

## 0.1.1 -- 19 Apr 2017

Update dependency on com.walmartlabs/lacinia to latest version, 0.15.0.

## 0.1.0 -- 19 Apr 2017

First release.

