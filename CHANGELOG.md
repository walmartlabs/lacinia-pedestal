## 0.3.0 -- UNRELEASED

Added support for subscriptions.

The default interceptor stack has been reordered, slightly.

## 0.2.0 -- 1 Jun 2017

The library and GitHub project have been renamed from `pedestal-lacinia` to
`lacinia-pedestal`.

Updated to Lacinia 0.17.0.

Added an option to execute the GraphQL request asynchronously; when enabled,
the handler returns a channel that conveys the Pedestal context containing
the response, once the query has finished executing.

Introduced function `com.walmartlabs.lacinia.pedestal/inject-app-context-interceptor` and
converted `query-executor-handler` from a function to constant.

A new namespace, `com.walmartlabs.lacinia.async`, includes functions to adapt
field resolvers that return clojure.core.async channels to Lacinia.

## 0.1.1 -- 19 Apr 2017

Update dependency on Lacinia to latest, 0.15.0.

## 0.1.0 -- 19 Apr 2017

First release.


