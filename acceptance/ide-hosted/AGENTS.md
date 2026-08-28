# Installed IDE-hosted acceptance

This directory owns the external-system KVP-034 installed-journey harness and KVP-037 closed
failure-matrix harness. They may invoke only the installed public `kast` command and observe the
exact-root endpoint plus local process/filesystem state. They must fail closed when hosted identity,
installed provenance, named evidence, or lifecycle observations are unavailable.

The harness receives task identity and metric requirements from generated graph projections. It must
not start an IDE, import a build, refresh VFS, launch an indexer, create an IDEA home, or fall back.
KVP-037 additionally requires the endpoint to be absent while it observes installed missing-plugin
behavior; its other matrix rows are authorized only by the focused typed tests run by its Gradle
legal-path task.
