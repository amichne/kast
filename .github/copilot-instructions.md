# Copilot instructions

Kast is a Kotlin analysis tool exposing one line-delimited JSON-RPC contract over two operator paths: a standalone JVM daemon driven by the repo-local `kast` CLI, and an IntelliJ plugin backend. See `AGENTS.md` for the full agent guide; this file is the Copilot-specific quick reference.

## Architecture (big picture)

Gradle multi-module build (JDK 21, Kotlin) — the units form a layered contract:

- `analysis-api`: shared JSON-RPC wire types, descriptor schema, error model, edit validation. **Must stay free of IntelliJ-only APIs.** OpenAPI spec is *generated* into `docs/openapi.yaml` by `:analysis-api:generateOpenApiSpec` and drift-tested.
- `analysis-server`: JSON-RPC dispatch, local socket / stdio transport, descriptor lifecycle. Also IntelliJ-free.
- `backend-standalone` and `backend-intellij`: two implementations of `AnalysisBackend`. **Any change to a backend operation must be applied to both** and audited in `parity-tests/`. Shared analysis helpers live in `backend-shared` (compileOnly IntelliJ platform deps).
- `kast-cli`: operator-facing control plane, native-image entrypoint, wrapper packaging, portable distribution. Ships an embedded skill bundle (`EmbeddedSkillResources`) and a generated wrapper OpenAPI (`WrapperOpenApiDocument`, drift-tested against `.agents/skills/kast/references/wrapper-openapi.yaml`).
- `shared-testing`: fake backend fixtures — production code must not depend on it.
- `build-logic`: included build of Gradle convention plugins (validate with `./gradlew -p build-logic test`).
- `docs/` (Zensical source) → `site/` (generated; do not hand-edit). Build with `zensical build --clean` after `pip install -r requirements-docs.txt`.

JSON serialization uses `encodeDefaults = true` across server/CLI — default-valued fields are emitted on the wire.

## Build, test, run

- Portable distribution (CLI): `./kast.sh build` (or `./gradlew :kast-cli:portableDistZip`). `./kast.sh build [cli|plugin|backend]` for targets; `--all` for everything.
- IntelliJ plugin: `./gradlew :backend-intellij:buildPlugin` (verify with `:verifyPluginStructure`).
- Module tests: `./gradlew :analysis-api:test`, `:analysis-server:test`, `:backend-standalone:test`, `:kast-cli:test`, `:backend-intellij:test`. Run the **narrowest** task that proves the change.
- Single test: `./gradlew :<module>:test --tests 'fully.qualified.ClassName.testMethod'` (e.g. `./gradlew :analysis-api:test --tests '*AnalysisOpenApiDocumentTest*'`).
- Performance-tagged IntelliJ tests: `./gradlew :backend-intellij:test -PincludeTags=performance`.
- Regenerate checked-in API/OpenAPI docs: `./gradlew :analysis-api:generateDocPages :analysis-api:generateOpenApiSpec`.
- The `:kast-cli:test` task sets `KAST_RUNTIME_LIBS` to `backend-standalone/build/runtime-libs` so subprocess tests can launch the daemon — don't remove that wiring.

## Key conventions

- **Mandatory tool routing for Kotlin semantic ops:** use `kast skill <command>` (resolve, references, callers, diagnostics, rename, scaffold, write-and-validate, workspace-files). `grep`/`rg`/`ast-grep` are prohibited for symbol identity, references, or call hierarchy — they're fine for file paths, non-Kotlin files, string literals, comments.
- `kast skill diagnostics` must return `clean=true` before completing a change.
- **Backend parity:** every `AnalysisBackend` operation change touches both `backend-standalone` and `backend-intellij`, plus `parity-tests/`.
- **Contract surfaces** that must be updated together when their schema/manifest changes: `analysis-api` models, `EmbeddedSkillResources`, `WrapperOpenApiDocument`, `docs/openapi.yaml`, `.agents/skills/kast/SKILL.md` and `.agents/skills/kast/references/wrapper-openapi.yaml`, `evals/*.yaml`, and `kast.sh`. Skipping any consumer silently breaks the distribution.
- **Test path safety (IntelliJ):** never compare paths via `project.basePath` string ops — use `GlobalSearchScope.projectScope(project)`. `@TempDir` paths in Linux CI ≠ `project.basePath`, so tests passing on macOS can fail in CI.
- **TDD with tracer bullets** is the default workflow — write a failing test first; use the `tdd` skill.
- Use `kast` (not the historical `analysis-cli`) in commands, docs, and packaging.
- Prefer the smallest unit that owns the behavior; only push semantics into `analysis-api` when multiple hosts/transports need them.
- Capability advertising must be honest — a transport/backend must not claim support for work it can't perform.

## Repo-specific agents and hooks

`.github/agents/` defines four Copilot custom agents:

| Agent | Role |
|-------|------|
| `@kast` | Orchestrator; routes to sub-agents and validates with diagnostics |
| `@explore` | Navigate/understand code via `kast skill` |
| `@plan` | Assess change scope and produce a structured plan |
| `@edit` | Apply changes via `kast skill write-and-validate` / `rename` |

A hook in `.github/hooks/hooks.json` sets `KAST_CLI_PATH` so agents invoke `"$KAST_CLI_PATH" skill <command> <json>`.

## Process

- Use the `@kast` agent for all Kotlin semantic analysis tasks.
- Use `@explore` to navigate and understand Kotlin code semantically.
- Use `@plan` to assess change scope before editing.
- Use `@edit` to make code changes with built-in validation.
- TDD: write failing unit tests first. Every change must include tests that prove behavior and regressions are covered.
- Kotlin standards: follow Kotlin style, apply formatting and lints (ktlint/detekt/spotless), avoid platform-specific APIs in shared modules.
- Constitutional code: treat API/model changes as contract changes; preserve schema compatibility and capability advertising unless intentionally changing.
- Clean code: prefer small, single-responsibility units, clear names, and minimal surface area.

## Backend parity

Any change to an `AnalysisBackend` operation must be applied to **both** `backend-standalone` and `backend-intellij`. Never implement a feature on one backend without auditing the other for corresponding callsites. After changes, verify `parity-tests/` covers the modified operation.

## Contract surface inventory

Before modifying `EmbeddedSkillResources`, `WrapperOpenApiDocument`, `AnalysisBackend`, or any packaged artifact manifest, enumerate all consumers: `docs/openapi.yaml`, `evals/*.yaml`, `.agents/skills/kast/SKILL.md`, and `kast.sh`/`install.sh`. These are contract surfaces — a change without updating all consumers silently breaks the distribution.

## Test path safety

In backend tests, never compare file paths using `project.basePath` string operations. Use `GlobalSearchScope.projectScope(project)` for IntelliJ scope filtering. `@TempDir` paths in Linux CI do not equal `project.basePath` — tests that pass on macOS will fail in CI.

## Process

1. `@explore` to understand the target code.
2. `@plan` to assess impact and produce a change plan.
3. `@edit` to make the change with `kast skill write-and-validate` or `kast skill rename`.
4. `kast skill diagnostics` must return `clean=true` before completing.
5. Run the narrowest Gradle task that proves the change.
6. Update `AGENTS.md`/docs when behavioral or contract rules change.
7. After committing, verify remote CI is green using `gh pr checks --watch` or the `gh-fix-ci` skill. Do not declare a task complete with CI red or unverified — local test pass is not sufficient.
