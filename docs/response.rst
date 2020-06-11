Response
========

The response to a GraphQL request is a ``application/json`` object.

The response will include a ``errors`` key if there are fatal or
non-fatal errors.

Fatal errors are those that occur before the query is executed;
these represents parse failures or validation errors.

Response Format
---------------

The response format is always ``application/json``.

The response body is the result map from executing the query (e.g., has ``data`` and/or ``errors`` keys).

In cases where there is a problem parsing or preparing the query, the response will still be in the
regular format, e.g.::

  {"errors": [{"message": "Request body is empty."}]}


GraphQL supports a third key, ``extensions``, but does not define what content goes there; it is for application-specific
extensions.

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

The ``:status`` key is removed from all error maps before the response is streamed to the client.

