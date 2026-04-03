# Kast agent guide

Kast is a Kotlin analysis tool with one line-delimited JSON-RPC contract and
one supported operator path: the repo-local `kast` CLI manages a standalone
JVM daemon for local automation and CI.

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
  validation, descriptor schema, and disk edit helpers
- `kast`: repo-local CLI control plane, wrapper packaging, detached daemon
  management, runtime readiness checks, and request dispatch
- `analysis-server`: JSON-RPC dispatch, local socket and stdio transport,
  request limits, and descriptor lifecycle
- `backend-standalone`: standalone host, Analysis API session bootstrap,
  Gradle workspace discovery, PSI/K2-backed analysis helpers, and runtime
  startup
- `shared-testing`: fake backend fixtures and shared contract assertions for
  tests
- `build-logic`: Gradle convention plugins, runtime-lib sync, wrapper
  generation, and shared build configuration
- `docs`: Zensical source docs, published usage guidance, and implementation
  notes
- `site`: generated static site output for GitHub Pages

## Working rules

Apply these rules across the repo before local unit rules add more detail.

- Change the smallest unit that owns the behavior. Pull shared semantics down
  into `analysis-api` only when multiple hosts or transports need them.
- Keep host-specific dependencies out of shared units. `analysis-api` and
  `analysis-server` must stay free of IntelliJ-only APIs.
- Keep standalone PSI and K2 Analysis API helpers in `backend-standalone`
  unless another surviving runtime genuinely needs them.
- Use `kast` in commands, docs, and packaging targets. `analysis-cli` is a
  historical path and should not receive new references.
- Treat API model changes as contract changes. Preserve schema compatibility,
  absolute-path invariants, descriptor fields, and capability advertising
  unless the behavior is intentionally changing across the stack.
- Keep capability gating honest. A transport or backend must not advertise
  support for work it cannot actually perform.
- Respect the current architecture: `kast` owns the operator-facing
  control plane, `analysis-server` owns transport and descriptor plumbing,
  `backend-standalone` owns runtime behavior, and `shared-testing` stays out
  of production code paths.
- Treat `docs/` plus `zensical.toml` as the documentation source of truth.
  `site/` is generated output and should be rebuilt, not hand-edited.
- Prefer repo-root packaging entry points for shipped CLI artifacts: `./build.sh`
  is the sole entrypoint to build you should use.
- Verify with the narrowest Gradle task that proves the change. Broaden the
  scope when you touch shared contracts, build logic, or cross-module behavior.
