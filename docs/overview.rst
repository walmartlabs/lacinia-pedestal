Overview
========

The main function used in Lacinia-Pedestal is ``com.walmartlabs.lacinia.pedestal/pedestal-service``.

You start with a schema file, :file:`resources/hello-world-schema.edn`, in this example:

.. literalinclude:: _examples/basic-setup-schema.edn
   :language: clojure

From there there are three steps:

* Load and compile the schema

* Create a Pedestal service around the schema

* Start the Pedestal service

.. literalinclude:: _examples/basic-setup.edn
   :language: clojure

At the end of this, an instance of `Jetty <http://www.eclipse.org/jetty/>`_ is launched on port 8888.

The GraphQL endpoint will be at ``http://localhost:8888/graphql`` and the GraphIQL client will be at
``http://localhost:8888/``.

The options map provided to ``pedestal-service`` allow any number of features of Lacinia-Pedestal
to be configured or customized.

Lacinia-Pedestal supports GET and POSTs in a number of different formats.
