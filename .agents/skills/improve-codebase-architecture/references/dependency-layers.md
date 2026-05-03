# Dependency layers

Use this reference when architecture work introduces a new module, moves
behavior between modules, or changes production dependencies. It complements the
deepening vocabulary in `LANGUAGE.md`: deepening decides where a module's
**interface** and **seam** belong, while layers decide which direction
dependencies may point.

## Layers

Dependencies must point to the same or a lower-numbered layer.

- **L0: Pure Types** — Kotlin interfaces, ADTs, value classes, enums, type
  aliases, validation results, derivation states, error types, and pure
  functions over those types. Production external dependencies require an
  explicit allow-list entry.
- **L1: Internal Libraries** — owned side-effect-free computation, data
  transformation, and domain logic. These modules may depend on L0 only.
- **L2: External Libraries** — quarantined wrappers around third-party
  libraries so replaceable details don't leak into core modules.
- **L3: Host-Specific Code** — file I/O, workspace discovery, background work,
  lifecycle, runtime configuration, and host-specific PSI/K2 helpers.
- **L4: Integration and Adapters** — CLI command parsing, JSON-RPC transport,
  IntelliJ plugin lifecycle, Gradle/build integration, and agent extensions.
- **L5: Tests and Fixtures** — unit tests, integration tests, parity tests,
  fakes, and shared test utilities.
- **L6: Documentation and Site** — source documentation, architecture notes,
  published guidance, and generated site output.

## How to use layers during deepening

Apply layers after identifying a deepening candidate.

1. Name the candidate module's **interface** and **seam**.
2. Place reusable behavior in the lowest layer that can own it without knowing
   about higher-layer hosts, adapters, frameworks, or tests.
3. Keep adapters at the higher-layer side of the seam.
4. Treat a lower-to-higher production dependency as a design smell. Either move
   the behavior lower, introduce a real adapter on the higher side, or record a
   deliberate exception in the repository's layer manifest.
5. Test through the module's interface. L5 may depend on production layers, but
   production layers must not depend on L5 fixtures.

## Kast enforcement

In this repository, `.github/architecture-layers.json` records the current
Gradle project placement. The static checker at
`.github/extensions/architecture-layers/check-architecture-layers.py` validates
that every Gradle project is classified, production project dependencies point
to the same or a lower layer, and L0/L1 production external dependencies are
allow-listed.

Run one of these checks after changing Gradle project dependencies or layer
placement:

```console
check_architecture_layers
./gradlew checkArchitectureLayers
```
