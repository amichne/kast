# `build-logic/src/main/kotlin/support/tasks` guide

This directory owns production sources under `build-logic/src/main/kotlin/support/tasks`. Follow [the nearest owner guide](../../../../../AGENTS.md) for boundaries, invariants, and verification.

## Local scope

- Keep changes within the parent guide's ownership.
- Add local rules only when this directory gains a distinct durable boundary.
- `vfspassive/` owns the KVP-032 source, bytecode, graph, firewall, and transitive-classpath
  composition task; follow its local guide for that typed report boundary.
- `verification/` owns reusable distribution-layout and generated-serialization verification
  task types.
