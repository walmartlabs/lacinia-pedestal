Interceptors
============

Both `com.walmartlabs.lacinia.pedestal3 <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal3.html>`_
and `com.walmartlabs.lacinia.pedestal2 <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal2.html>`_
define Pedestal `interceptors <http://pedestal.io/reference/interceptors>`_ and supporting code.

The `inject <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal.html#var-inject>`_ function
(added in 0.7.0) adds (or replaces) an interceptor in a vector of interceptors.

Default Interceptor Pipeline
-----------------------------

Both ``pedestal3`` and ``pedestal2`` provide a ``default-interceptors`` function that returns
the same ordered pipeline:

* ``::initialize-tracing`` — captures the earliest possible start time for tracing
* ``::json-response`` — converts a map response body to JSON
* ``::error-response`` — returns a 500 response for unhandled exceptions
* ``::body-data`` — reads the POST body; rejects non-``application/json`` requests with 400
* ``::graphql-data`` — extracts ``:graphql-query``, ``:graphql-vars``, ``:graphql-operation-name`` from the body
* ``::status-conversion`` — promotes ``:status`` from error extension maps to the HTTP status code
* ``::missing-query`` — rejects with 400 if the query is missing or blank
* ``::query-parser`` — parses the query document; accepts an optional ``ParsedQueryCache``
* ``::disallow-subscriptions`` — rejects subscription requests at the HTTP endpoint
* ``::prepare-query`` — injects variables and validates the parsed query
* ``::inject-app-context`` — puts ``:lacinia-app-context`` into the request
* ``::enable-tracing`` — enables tracing if the ``lacinia-tracing`` header is present
* ``::query-executor`` — executes the query (synchronous; last in chain)

.. note::

  ``pedestal2`` also provides ``async-query-executor-handler`` as an alternative to
  ``query-executor-handler``. This variant is not available in ``pedestal3``.

Difference: ``:body`` vs ``:json-body``
-----------------------------------------

The one behavioral difference between ``pedestal2`` and ``pedestal3`` is where
``body-data-interceptor`` stores the raw request body string:

* ``pedestal2`` writes to ``:body``, overwriting the original request body input stream.
* ``pedestal3`` writes to ``:json-body``, leaving ``:body`` as a consumed input stream.

This matters if you have custom interceptors that inspect the raw request body after
``body-data-interceptor`` has run.

Example
-------

.. literalinclude:: _examples/custom-setup.edn
   :language: clojure

There's a lot to process in this more worked example:

- We're using `Component <https://github.com/stuartsierra/component>`_ to organize our code and dependencies.

- The schema is provided by a source component (in the next listing), injected as a dependency into the ``Server`` component.

- We're building our Pedestal connector explicitly using ``io.pedestal.connector``, rather than using ``default-service``.

The interceptor is responsible for putting the user info *into* the request, and then
it's simple to get that data inside a resolver function:

.. literalinclude:: _examples/schema-setup.edn
   :language: clojure

Again, it's a little sketchy because we don't know what the ``user-info`` data is, how its
stored in the request, or what is done with it ... but the ``:user-info`` put in place
by the interceptor is a snap to gain access to in any resolver function.

.. tip::
   The ``inject`` function is useful for making one or two small additions to the default interceptors,
   but any more than that will likely lead to confusion about what order the items in the interceptor
   pipeline are in; better to duplicate the code from ``default-interceptors`` directly.
