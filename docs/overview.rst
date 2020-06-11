Overview
========

.. warning::

  The newer ``com.walmartlabs.lacinia.pedestal2 namespace`` is recommended over the
  now-deprecated ``com.walmartlabs.lacinia.pedestal`` namespace. The new namespace was
  introduced to maintain backwards compatibility, but be aware of which you use,
  as the default URLs served differ with each namespace. This guide assumes you
  are using the latest ``pedestal2`` namespace and its defaults.

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
to be configured or customized, though the intent of ``default-service`` is to just be
initial scaffolding - it should be replaced with application-specific code.
