# `cli/src/test/kotlin/io/github/amichne/kast/cli` guide

This directory owns test sources and fixtures under `cli/src/test/kotlin/io/github/amichne/kast/cli`. Follow [the nearest owner guide](../../../../../../../../AGENTS.md) for boundaries, invariants, and verification.

## Local scope

- KVP-026 endpoint-admission case names must exactly match the canonical graph's named misuse and
  legal path so the Gradle evidence task can bind executed JUnit evidence without manual fields.
- Keep compatibility, root, socket, process, capability, and reachability fixtures deterministic.
