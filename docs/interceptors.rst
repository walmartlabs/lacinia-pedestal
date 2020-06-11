Interceptors
=============

`com.walmartlabs.lacinia.pedestal2 <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal2.html>`_ defines Pedestal `interceptors <http://pedestal.io/reference/interceptors>`_ and supporting code.

The `inject <https://walmartlabs.github.io/apidocs/lacinia-pedestal/com.walmartlabs.lacinia.pedestal.html#var-inject>`_ function (added in 0.7.0) adds (or replaces) an interceptor to a vector of interceptors.

Example
-------

.. literalinclude:: _examples/custom-setup.edn
   :language: clojure


There's a lot to process in this more worked example:

- We're using `Component <https://github.com/stuartsierra/component>`_ to organize our code and dependencies.

- The schema is provided by a source component (in the next listing), injected as a dependency into the ``Server`` component.

- We're building our Pedestal service explicitly, rather than using ``default-service``.


The interceptor is responsible for putting the user info *into* the request, and then
it's simple to get that data inside a resolver function:


.. literalinclude:: _examples/schema-setup.edn
   :language: clojure

Again, it's a little sketchy because we don't know what the ``user-info`` data is, how its
stored in the request, or what is done with it ... but the ``:user-info`` put in place
by the interceptor is a snap to gain access to in any resolver function.
