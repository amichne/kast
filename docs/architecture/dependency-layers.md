---
title: Dependency layers
description: How kast keeps pure types, core logic, host runtimes, adapters,
  tests, and documentation moving in one direction.
icon: lucide/git-branch
---

# Dependency layers

The dependency layer model gives contributors a statically checkable way to
place new modules and dependencies. It complements the deepening language in
`improve-codebase-architecture`: deepening decides where a module's interface
and seam belong, while layers decide which direction dependencies may point.

## Layer map

Each layer has a clear purpose. Production dependencies must point to the same
or a lower-numbered layer, so higher layers adapt lower layers instead of
pulling host, framework, or test concerns into core code.

| Layer | Name | Owns | Dependency rule |
| --- | --- | --- | --- |
| L0 | Pure Types | Kotlin interfaces, ADTs, value classes, enums, type aliases, validation results, derivation states, error types, and pure functions over those types | No production project dependencies; production external dependencies require an explicit allow-list entry |
| L1 | Internal Libraries | Owned side-effect-free computation, data transformation, and domain logic | May depend on L0; production external dependencies require an explicit allow-list entry |
| L2 | External Libraries | Quarantined wrappers around third-party libraries such as persistence, logging, configuration, and utility libraries | May depend on L0-L1 and external libraries |
| L3 | Host-Specific Code | File I/O, workspace discovery, background work, lifecycle, and standalone PSI/K2 helpers | May depend on L0-L2 |
| L4 | Integration and Adapters | CLI command parsing, JSON-RPC transport, IntelliJ plugin lifecycle, Gradle/build integration, and agent extensions | May depend on L0-L3 and peer adapters when the current module still combines seams |
| L5 | Tests and Fixtures | Unit tests, integration tests, parity tests, fakes, and shared test utilities | May depend on all production layers |
| L6 | Documentation and Site | Source docs, published usage guidance, implementation notes, and generated site output | Documents all layers; does not own executable production logic |

The checker treats L0 and L1 as stricter than the current Gradle module shape.
Existing exceptions are recorded in `.github/architecture-layers.json` with a
rationale so future work can split them deliberately instead of adding hidden
coupling.

## Current Gradle project placement

The manifest maps the current repository shape to the layer model. Use this as
an inventory, not as permission to keep mixed modules mixed forever.

| Gradle project | Layer | Reason |
| --- | --- | --- |
| `:analysis-api` | L0 | Shared contract models and typed request/response surfaces are the current pure-type seam. Existing config and docs helpers are recorded as allow-listed exceptions. |
| `:index-store` | L2 | SQLite persistence is the repository-owned quarantine around an external storage library. |
| `:backend-shared` | L3 | Shared analysis helpers compile against PSI/K2 APIs supplied by host runtimes. |
| `:backend-standalone` | L4 | The standalone backend currently combines host lifecycle with daemon and server integration concerns. |
| `:analysis-server` | L4 | JSON-RPC transport and descriptor dispatch adapt protocol requests to lower-layer semantics. |
| `:backend-intellij` | L4 | The IntelliJ plugin adapts IDE lifecycle, project services, and plugin packaging to the shared contract. |
| `:kast-cli` | L4 | The CLI adapts operator commands and distribution workflows to the shared contract. |
| `:shared-testing` | L5 | Fake backend fixtures and shared contract assertions are test-only surfaces. |
| `:parity-tests` | L5 | Parity tests verify behavior across implementations without production ownership. |

When a project feels like it belongs in two layers, prefer the lower-layer seam
for the reusable behavior and keep the adapter in the higher layer. If the split
is too large for the current change, record the mixed placement in the manifest
with a rationale and avoid adding new callers to the higher-layer surface.

## How this complements deepening

Deepening and layering answer different design questions. Use both before
introducing a new module or dependency.

- **Deepening asks where the interface should live.** A deep module hides a lot
  of behavior behind a small interface and gives callers leverage.
- **Layering asks what that interface may know about.** A lower-layer module
  must not depend on adapters, hosts, frameworks, or test fixtures above it.
- **Seams stay honest.** A seam that crosses layers needs real adapters on the
  higher side; don't move lower-layer rules upward just to make a caller easier
  to write.
- **Tests exercise interfaces.** L5 code can depend on production layers, but
  production layers must not depend on L5 fixtures.

Use the deletion test from `improve-codebase-architecture` before adding a new
layer seam. If deleting the module would only move complexity into callers, the
module is probably shallow. If deleting it would scatter lower-layer invariants
across adapters, the module is earning its keep.

## Static enforcement

The repository includes a checker and an agent extension so the layer model is
not only prose.

Run the checker directly when you change Gradle project dependencies or the
layer manifest:

```console
python3 .github/extensions/architecture-layers/check-architecture-layers.py \
  --repo /home/runner/work/kast/kast
```

The root Gradle `check` task also depends on `checkArchitectureLayers`, which
runs the same script. The checker validates these facts:

1. Every project in `settings.gradle.kts` appears in
   `.github/architecture-layers.json`.
2. Production project dependencies point to the same or a lower layer.
3. L0 and L1 production external dependencies have explicit allow-list entries.
4. Test configurations are ignored because L5 is allowed to depend on all
   production layers.

The Copilot extension at `.github/extensions/architecture-layers/extension.mjs`
exposes `check_architecture_layers` and reminds agents to run it after edits to
layer-affecting files.

## Adding or moving code

Use this sequence when a change introduces a new module, moves behavior between
modules, or adds a production dependency.

1. Name the module's interface and seam using the vocabulary from
   `improve-codebase-architecture`.
2. Choose the lowest layer that can own the behavior without depending on host,
   adapter, framework, or test concerns above it.
3. Put third-party libraries behind L2 or higher modules unless the manifest
   records a deliberate L0/L1 allow-list exception.
4. Update `.github/architecture-layers.json` when a Gradle project is added,
   removed, or intentionally reclassified.
5. Run `check_architecture_layers` or `./gradlew checkArchitectureLayers` before
   finishing.

## Next steps

- [How kast works](how-it-works.md) explains the runtime and module ownership
  story.
- [ADT boundaries](adt-boundaries.md) shows how typed outcomes keep lower-layer
  contracts explicit.
- `improve-codebase-architecture` provides the deepening vocabulary used to
  design the interfaces that sit inside these layers.
