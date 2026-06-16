Overview
========

.. note::

  As of 1.4, ``com.walmartlabs.lacinia.pedestal3`` is the recommended namespace;
  it uses the Pedestal 0.8 connector API (``io.pedestal.connector``).
  The older ``com.walmartlabs.lacinia.pedestal2`` namespace, which uses the
  now-deprecated ``io.pedestal.http`` API, remains supported but is not recommended
  for new projects.

You start with a schema file, :file:`resources/hello-world-schema.edn`, in this example:

.. literalinclude:: _examples/basic-setup-schema.edn
   :language: clojure

From there there are three steps:

* Load and compile the schema

* Create a Pedestal connector around the schema

* Start the Pedestal connector

Using ``pedestal3``
-------------------

With the Pedestal 0.8 connector API:

.. literalinclude:: _examples/basic-setup.edn
   :language: clojure

At the end of this, an instance of `Jetty <http://www.eclipse.org/jetty/>`_ is launched on port 8888.

The GraphQL endpoint will be at ``http://localhost:8888/api`` and the WebSocket subscription
endpoint will be at ``http://localhost:8888/ws``.

Unlike ``pedestal2``, ``pedestal3`` does not include a ``default-service`` convenience function
or built-in GraphiQL support. Applications are expected to construct the connector and routes
directly. ``default-interceptors`` and ``subscription-routes`` are intended as initial
scaffolding that should be replaced with application-specific code as the project matures.

Using ``pedestal2`` (legacy)
-----------------------------

With the legacy ``io.pedestal.http`` API::

  (ns graphql-demo.server
    (:require [io.pedestal.http :as http]
              [com.walmartlabs.lacinia.pedestal2 :as lp]))

  (defonce runnable-service
    (-> (lp/default-service compiled-schema nil)
        http/create-server))

  (http/start runnable-service)

At the end of this, an instance of `Jetty <http://www.eclipse.org/jetty/>`_ is launched on port 8888.

The GraphQL endpoint will be at ``http://localhost:8888/api`` and the GraphiQL client will be at
``http://localhost:8888/ide``.

The options map provided to ``default-service`` allow a number of features of Lacinia-Pedestal
to be configured or customized, though the intent of ``default-service`` is to just be
initial scaffolding - it should be replaced with application-specific code.
