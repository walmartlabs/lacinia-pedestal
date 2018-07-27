Request Format
==============

Clients may send either a HTTP GET or HTTP POST request to execute a query.

In both cases, the request path will (by default) be ``/graphql``.

GET
---

The GraphQL query document must be provided as query parameter ``query``.

POST (application/json)
-----------------------

When using POST with the ``application/json`` content type, the body of the request may contain the following keys:

.. glossary::

  ``query``
    Required: The GraphQL query document, as a string.

  ``variables``
    Optional: A JSON object of variables (as defined and referenced in the query document).

  ``operationName``
    Optional: The name of the specific operation to execute, when the query document defines
    more than one named operation.


POST (application/graphql)
--------------------------

.. warning::

  This format is fully supported, but represents a legacy format used internally
  at Wal-Mart, prior to the GraphQL community identifying an over-the-wire format.
  The ``application/json`` format is preferred.

The body of the request should be the GraphQL query document.

If the query document defines variables, they must be specified as the ``variables`` query parameter, as
a string-ified JSON object.






