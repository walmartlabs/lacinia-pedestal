Overview
========

The main function used in Lacinia-Pedestal is ``com.walmartlabs.lacinia.pedestal2/default-service``.

... warning::

  The newer com.walmartlabs.lacinia.pedestal2 namespace is recommended over the
  now-deprecated com.walmartlabs.lacinia.pedestal namespace. The new ns was
  introduced to maintain backwards compatibility, but be aware of which you use,
  as the default urls served differ with each namespace. This guide assumes you
  are using the latest lacinia and the pedestal2 defaults.

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

The GraphQL endpoint will be at ``http://localhost:8888/api`` and the GraphIQL client will be at
``http://localhost:8888/ide``.

The options map provided to ``default-service`` allow a number of features of Lacinia-Pedestal
to be configured or customized.

Lacinia-Pedestal supports GET and POSTs in a number of different formats.
