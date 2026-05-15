# Client-daemon architecture

Kast is organized around a lightweight native client and a long-lived JVM
daemon. This split is the main architectural bet behind the rest of the system.

## Summary

The client-daemon split keeps the expensive Kotlin analysis state warm while the
CLI stays quick to invoke. You pay a higher initial setup cost once, then reuse
the resident backend for later semantic queries and edits.

## What the wiki currently believes

- The CLI exists to keep user and agent ergonomics simple while the daemon owns
  expensive state.
- The transport layer is local-only and optimized for command-style reuse rather
  than multi-tenant remote serving.
- Descriptor files and instance management are part of the architecture, not an
  implementation detail, because daemon reuse depends on discoverability.

## Evidence and sources

These pages support the current architectural picture.

- [[sources/kast-overview]] - Introduces the client-daemon model at a high
  level.
- [[sources/architecture-and-module-structure]] - Maps the major modules onto
  that split.
- [[sources/analysis-server-json-rpc-transport-layer]] - Explains how the
  daemon is exposed locally.
- [[sources/getting-started]] - Shows the warm-up and reuse lifecycle from the
  user side.

## Related pages

These pages explain the main implications of the architecture.

- [[entities/kast-cli]]
- [[entities/analysis-server]]
- [[entities/backend-standalone]]
- [[concepts/installation-and-instance-management]]
- [[analyses/end-to-end-request-lifecycle]]

## Open questions

The current notes establish the shape of the system but not every tradeoff.

- What are the measured cold-start and warm-path latencies across representative
  projects?
- Which failure modes most often force a user to refresh or stop the daemon?
