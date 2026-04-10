# Kast agent guide
ALways TDD with tracer bullets, use the skill

Kast is a Kotlin analysis tool with one line-delimited JSON-RPC contract and
two supported operator paths: the repo-local `kast` CLI manages a standalone
JVM daemon for local automation and CI, and the IntelliJ plugin backend runs
inside a running IntelliJ IDEA instance.

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

- `analysis-api`: shared contract, serializable models, JSON-RPC wire types,
  descriptor discovery helpers, standalone option parsing, errors, file edit
  validation, descriptor schema, and disk edit helpers
- `kast-cli`: shared CLI control plane, command parsing, JSON output, install
  flows, runtime orchestration, and the native-image entrypoint
- `kast`: JVM shell, wrapper packaging, portable distribution layout, and the
  real `internal daemon-run` implementation
- `analysis-server`: JSON-RPC dispatch, local socket and stdio transport,
  request limits, and descriptor lifecycle
- `backend-standalone`: standalone host, Analysis API session bootstrap,
  Gradle workspace discovery, PSI/K2-backed analysis helpers, and runtime
  startup
- `backend-intellij`: IntelliJ IDEA plugin backend, project-level service,
  plugin lifecycle, and IDE-hosted analysis server
- `backend-shared`: shared analysis utilities consumed by both backend
  runtimes via compileOnly IntelliJ platform dependencies
- `shared-testing`: fake backend fixtures and shared contract assertions for
  tests
- `build-logic`: Gradle convention plugins, runtime-lib sync, wrapper
  generation, and shared build configuration
- `docs`: Zensical source docs, published usage guidance, and implementation
  notes
- `site`: generated static site output for GitHub Pages

## Mandatory tool routing

Agents must use kast wrapper scripts for all Kotlin semantic operations.

| Operation        | Tool                      | Fallback |
|-----------------|---------------------------|----------|
| Resolve symbol  | `kast-resolve.sh`         | None     |
| Find references | `kast-references.sh`      | None     |
| Call hierarchy  | `kast-callers.sh`         | None     |
| Impact analysis | `kast-impact.sh`          | None     |
| Diagnostics     | `kast-diagnostics.sh`     | None     |
| Rename symbol   | `kast-rename.sh`          | None     |

**Prohibited substitutions:** `grep`, `rg`, `ast-grep`, `cat` + manual
parsing must NOT be used for symbol identity, reference finding, or call
hierarchy. These tools lack semantic understanding and produce incorrect
results for overloaded symbols, inherited members, and cross-module
references.

**Text search whitelist:** `grep`/`rg` may be used for finding file paths,
searching non-Kotlin files, and searching string literals or comments.

## Agent hooks

`.agents/hooks.json` is the authoritative source for agent-level hooks.
Hooks are additive across the scope hierarchy: repo → agent → skill. Skills
must not redeclare any hook already defined at the agent level.

## Skill composition

| Phase                | Primary skill     | Supporting skill     |
|---------------------|-------------------|---------------------|
| Understand the code | `kast`            | —                   |
| Plan a change       | `kast` (impact)   | —                   |
| Make the change     | Agent (direct edit)| `kotlin-standards` |
| Validate the change | `kast` (diagnostics)| `kotlin-gradle-loop`|
| Document the change | `docs-writer`     | —                   |

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
- Respect the current architecture: `kast-cli` owns the operator-facing
  control plane and native entrypoint, `kast` owns the JVM shell and wrapper
  packaging, `analysis-server` owns transport and descriptor plumbing,
  `backend-standalone` owns headless runtime behavior, `backend-intellij` owns
  IDE-hosted runtime behavior, and `shared-testing` stays out of production
  code paths.
- Treat `docs/` plus `zensical.toml` as the documentation source of truth.
  `site/` is generated output and should be rebuilt, not hand-edited.
- Prefer repo-root packaging entry points for shipped artifacts: `./build.sh`
  builds the standalone portable distribution; `./gradlew buildIntellijPlugin`
  builds the IntelliJ plugin zip.
- Verify with the narrowest Gradle task that proves the change. Broaden the
  scope when you touch shared contracts, build logic, or cross-module behavior.
