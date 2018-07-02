Server-Side Queries
===================

Server-side queries are an optional optimization.

Traditionally, the client builds an entire the GraphQL query document, and passes this document,
as a string, in a request.
This document is rather large, compared to a traditional REST endpoint; the REST endpoint gets the majority
of its information from the URI, perhaps including HTTP query parameters.

By contrast, in GraphQL all requests filter into the same endpoint, and the details require parsing
the GraphQL query document and, optionally, applying GraphQL query variables.

With server-side queries, the client sends only the name of a query; the query document is stored
on the server.
In most cases, the client will also send up GraphQL query variables.

This results in a much smaller request; hundreds or even thousands of characters of
in-request GraphQL query reduced to a few tens of characters for the query name.
This can make a considerable difference for clients, especially mobile clients with
narrow upload bandwidth.

Further, the server can cache the parsed representation of the GraphQL query; clients that use
the same named query will see a modest (perhaps two or three millisecond) boost as the normal parsing stage can be
omitted.

Endpoint
--------

Named queries are processed by an alternate endpoint, by default ``/query``.
Requests should use the ``application/json`` content type.

The value for ``query`` is the name of the query, provided to the query store.

The ``variables`` and ``operationName`` values are the same as in a traditional GraphQL request.

Query Store
-----------

The query store, the source of GraphQL query documents, is represented as a simple function, provided
as an option to ``service-map``.

The function is passed the stored GraphQL query name.

The function returns the stored query (as a string) if found, or ``nil`` if not found.

Query Cache
-----------

A valid GraphQL query, once parsed, will be stored into an in-memory cache.

The cache is an instance of `clojure.core.cache <https://github.com/clojure/core.cache/>`_
CacheProtocol.

The default cache simply sets a 10 minute TTL (time to live); you will almost always want to override
this with something that makes more sense for your particular application.

Keeping the parsed representation in memory shaves a miniscule amount of time from
overall request processing time.
It may also decrease the amount of object creation churn (the creation of short-lived objects).

REPL Development
----------------

When performing REPL oriented development, a common trick is to provide a function that returns the
compiled schema, rather than the compiled schema itself, to the ``service-map`` function.

Typically, this means that the schema is read and compiled on each request, which is fine for development
and testing, but absolutely not the right thing to do in production.

However, it should be noted that a parsed GraphQL query internally has references to the compiled GraphQL
schema.
This is problematic when combined with a query cache, as the parsed and cached queries may be out of date
with respect to the latest GraphQL schema.
When combining server-side queries with REPL oriented development, you should reduce the cache TTL to a few milliseconds,
to force incoming requests for named queries to be parsed against the latest version of the
compiled schema.

Subscriptions
-------------

Named server-side queries are not yet supported for subscription requests.

