# Installed IDE-hosted acceptance

This directory owns the external-system KVP-034 harness. It may invoke only the installed public
`kast` command and observe the already-running exact-root IntelliJ Project and local process/filesystem
state. It must fail closed when the hosted v2 descriptor, compatible plugin, installed provenance,
metric authority, four-operation result, or Project-retirement observation is unavailable.

The harness receives task identity and metric requirements from generated graph projections. It must
not start an IDE, import a build, refresh VFS, launch an indexer, create an IDEA home, or fall back.
