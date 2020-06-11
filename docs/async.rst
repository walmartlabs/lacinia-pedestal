Async
=====

By default, Lacinia-Pedestal blocks the Pedestal request thread
while executing the query. The default
pedestal interceptor stack includes a synchronous query execution handler,
``com.walmartlabs.lacinia.pedestal2/query-executor-handler``.

Lacinia also provides an asynchronous query execution handler:
``com.walmartlabs.lacinia.pedestal2/async-query-executor-handler``.

When used in the interceptor stack, execution starts on a Pedestal request
processing thread, but (at the discretion of individual field resolver
functions) may continue on other threads.

Further, the return value from the asychronous handler is a channel, forcing Pedestal to
switch to async mode.

Lacinia-Pedestal does not impose any restrictions on the number of requests it will
attempt to process concurrently; normally, this is gated by the number of Pedestal
request processing threads available.

When using the asynchronous query execution handler, you should provide application-specific
interceptors to rate limit requests, or risk saturating your server.
