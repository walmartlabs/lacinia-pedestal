Response
========

The response to a GraphQL request is ``application/json``.

The response will include a ``errors`` key if there are fatal or
non-fatal errors.

Fatal errors are those that occur before the query is executed;
these represents parse failures or validation errors.

HTTP Status
-----------

The normal HTTP status is ``200 OK``.

If there was a fatal error (such as a query parse error), the HTTP status will be ``400 Bad Request``.

Status Conversion
-----------------

Field resolvers may return error maps.
If an error map contains a ``:status`` key, this value will be used
as the overall HTTP status of the response.

When multiple error maps contains ``:status``, the numerically largest
value is used.

The ``:status`` key is removed from all error maps.

