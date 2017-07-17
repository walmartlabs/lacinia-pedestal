Async
=====

By default, Lacinia-Pedestal blocks while executing the query.

The ``:async`` option, passed to ``com.walmartlabs.lacina.pedestal/pedestal-server``
will enable async execution.

In async mode, execution starts on a Pedestal request processing thread,
but (at the discression of individual field resolver functions) may
continue on other threads.

Further, the return value from the Pedestal handler is a channel, forcing
Pedestal to switch to async mode.

Lacinia-Pedestal does not impose any restrictions on number of requests it will attempt
to process concurrently; you should provide application-specific
interceptors to rate limit requests, or risk swamping your server.
