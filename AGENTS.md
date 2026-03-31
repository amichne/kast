# Kast agent guide

Kast is a Kotlin analysis server with one HTTP/JSON contract and two runtime
hosts: an IntelliJ plugin for local development and a standalone JVM process
for CI and headless workflows.

Subdirectory `AGENTS.md` files narrow these rules for their own units. When a
rule exists in both places, follow the deeper file.

## North stars

Carry these principles into every change in this repository.

We admire innovation and admonish adherents. We view simplicity as the truest
form of excellence. We know without the ability to communicate our ideas we're
a boat adrift, hopeless and helpless. These are your north stars, no matter the
context.

Do not express positive or negative opinions unless they pass this gate: the
object of evaluation is clear, the criteria are appropriate, the evidence is
sufficient, a baseline has been considered, and confidence is calibrated. If
those conditions are not met, narrow the claim or state that a firm judgment is
not justified.

## Unit map

Use this map to choose the narrowest unit that owns a change.

- `analysis-api`: shared contract, serializable models, errors, file edit
  validation, and disk edit helpers
- `analysis-common`: shared PSI and K2 Analysis API utilities used by both
  runtime hosts
- `analysis-server`: Ktor transport, capability gating, auth, request limits,
  and descriptor lifecycle
- `backend-intellij`: IntelliJ-hosted backend, PSI-backed analysis, plugin
  lifecycle, and project service startup
- `backend-standalone`: standalone host, CLI parsing, and the scaffolded
  headless backend
- `shared-testing`: fake backend fixtures for tests
- `build-logic`: Gradle convention plugins and shared build configuration
- `docs`: Zensical source docs, ADRs, operator guidance, and implementation
  notes
- `site`: generated static site output for GitHub Pages

## Working rules

Apply these rules across the repo before local unit rules add more detail.

- Change the smallest unit that owns the behavior. Pull shared semantics down
  into `analysis-api` only when multiple hosts or transports need them.
- Keep host-specific dependencies out of shared units. `analysis-api` and
  `analysis-server` must stay free of IntelliJ-only APIs.
- Keep shared PSI and K2 Analysis API helpers in `analysis-common`. Host
  startup, threading, transport, and CLI behavior still belong in the
  `backend-*` units.
- Treat API model changes as contract changes. Preserve schema compatibility,
  absolute-path invariants, and capability advertising unless the behavior is
  intentionally changing across the stack.
- Keep capability gating honest. A route or backend must not advertise support
  for work it cannot actually perform.
- Respect the current architecture: `analysis-server` owns HTTP transport,
  `backend-*` modules own runtime behavior, and `shared-testing` stays out of
  production code paths.
- Treat `docs/` plus `zensical.toml` as the documentation source of truth.
  `site/` is generated output and should be rebuilt, not hand-edited.
- Verify with the narrowest Gradle task that proves the change. Broaden the
  scope when you touch shared contracts, build logic, or cross-module behavior.
