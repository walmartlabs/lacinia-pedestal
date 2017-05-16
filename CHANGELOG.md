## 0.2.0 -- UNRELEASED

Updated to Lacinia 0.16.0.

Added an option to execute the GraphQL request asynchronously; when enabled,
the handler returns a channel that conveys the Pedestal context containing
the response, once the query has finished executing.

Introduced function `com.walmartlabs.lacinia.pedestal/inject-app-context-interceptor` and
converted `query-executor-handler` from a function to constant.

## 0.1.1 -- 19 Apr 2017

Update dependency on Lacinia to latest, 0.15.0.

## 0.1.0 -- 19 Apr 2017

First release.
