# Copilot instructions

## Repo-specific tooling

- For Kotlin code, search, references, callers, diagnostics, or edits, use the native `kast_*` tools first. If a bash
  fallback is genuinely necessary, call
  `kast rpc '<jsonrpc-request>'` directly instead of relying on
  exported shell state across tool calls.
- `.github/extensions/kast/extension.mjs` is the primary Copilot extension entrypoint. It resolves the `kast` CLI once
  per session, exposes the native
  `kast_*` tools, and soft-warns when generic tools target `.kt` or `.kts`
  files.
- Read `AGENTS.md` at the repo root first, then any deeper `AGENTS.md` in the module you touch. The narrower file
  overrides the root guide.

## Build, test, package, and docs commands

| Task                                                     | Command                                                                                                    |
|----------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| Full repo build/test                                     | `./gradlew build --offline`                                                                                |
| Run one module's tests                                   | `./gradlew :kast-cli:test --offline`                                                                       |
| Run a single test class                                  | `./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisServerSocketTest --offline` |
| Run a single build-logic test                            | `./gradlew -p build-logic test --tests DefaultTestTagSelectionTest --offline`                              |
| Validate `.github/` or packaged Copilot resource changes | `./gradlew :kast-cli:processResources :kast-cli:test --offline`                                            |
| Build the CLI portable zip                               | `./gradlew buildCliPortableZip --offline`                                                                  |
| Build the IntelliJ plugin zip                            | `./gradlew buildIntellijPlugin --offline`                                                                  |
| Verify plugin compatibility                              | `./gradlew :backend-intellij:verifyPlugin --offline`                                                       |
| Build shipped artifacts via the repo wrapper             | `./kast.sh build`                                                                                          |
| Regenerate API reference pages                           | `./gradlew :analysis-api:generateDocPages --offline`                                                       |
| Build the docs site                                      | `pip install -r requirements-docs.txt && zensical build --clean`                                           |

Focused reruns follow the normal Gradle pattern:
`./gradlew :<module>:test --tests <fully.qualified.ClassName>[.<methodName>] --offline`.

Default test runs exclude the `concurrency`, `performance`, and `parity` tags unless you opt in with
`-PincludeTags=...`.

## High-level architecture

- `analysis-api` is the host-agnostic contract layer. It defines
  `AnalysisBackend`, request/response models, capability enums, descriptor types, and edit-plan semantics shared by
  every runtime.
- `analysis-server` wraps `AnalysisBackend` in the line-delimited JSON-RPC transport. `AnalysisDispatcher` is the method
  router that enforces capability checks, pagination limits, and request decoding for socket and stdio servers.
- `kast-cli` is the operator-facing control plane. `WorkspaceRuntimeManager`
  inspects descriptor files, reports workspace status, starts or stops the standalone daemon, and packages the CLI,
  wrapper metadata, and embedded Copilot resources.
- `backend-standalone` is the headless runtime for terminal, CI, and agent use.
  `StandaloneAnalysisSession` owns Gradle workspace discovery, PSI/K2 session lifecycle, workspace refresh, and
  background indexing.
- `backend-intellij` is the IDE-hosted runtime. `KastPluginService` starts a local server inside IntelliJ or Android
  Studio, reuses the IDE project model, and coordinates indexing against the shared SQLite store.
- `index-store` owns the SQLite-backed source index and workspace cache.
  `SqliteSourceIndexStore` persists declarations, references, manifest state, generations, and workspace-discovery
  snapshots used by both runtimes.
- `shared-testing` provides fake backends and reusable fixtures. `parity-tests`
  check that standalone and IntelliJ backends stay behaviorally aligned.
  `build-logic` owns the shared Gradle conventions and wrapper/runtime-lib packaging tasks.

Operationally, the CLI and both backends speak the same JSON-RPC contract. The CLI prefers a servable IntelliJ backend
for the workspace, otherwise a servable standalone backend. `kast rpc` auto-ensures the selected daemon for machine
requests, while `kast up`, `kast status`, and `kast stop` are the human lifecycle commands.

## Key conventions

- Treat `AnalysisBackend`, the `kast rpc` JSON-RPC method surface, embedded skill resources, and packaged
  Copilot-extension resources as contract surfaces. If one changes, update its consumers together: `docs/openapi.yaml`, `.agents/skills/kast/**`,
  `.github/extensions/kast/**`, `.github/hooks/**`, `kast.sh`/`install.sh`, and the related tests.
- Any `AnalysisBackend` operation change must land in **both**
  `backend-standalone` and `backend-intellij`. Update `parity-tests` and keep advertised capabilities honest.
- In this repo, "indexing" means real K2/PSI-backed symbol extraction into the SQLite source index, not file walking.
  The standalone runtime does this in two phases: a fast identifier index followed by a deeper reference index.
- Runtime cleanup must be explicit. When code owns background threads or daemons, call `interrupt()` first and then
  `join(timeout)` in `close()` or shutdown paths; otherwise macOS `@TempDir` cleanup races show up in tests.
- `docs/` plus `zensical.toml` are the documentation source of truth. `site/`
  and generated `docs/reference/*.md` output are build artifacts and should not be hand-edited.
- `.github/hooks/hooks.json` is the authoritative hook manifest. For Copilot packaging work, inspect
  `kast-cli/build/resources/main/` rather than assuming the packaged bundle exactly matches the source `.github/` tree.
- Source `.github/hooks/skill-shadowing.json` intentionally keeps repo-local entries, while the packaged Copilot bundle
  filters it down to portable entries backed by `shadowingExtensionId`.
- Use `kast` terminology in commands, docs, and packaging targets. `analysis-cli`
  is legacy naming and should not receive new references.
- CI runs on both ubuntu and macOS. IntelliJ path filtering should use
  `GlobalSearchScope.projectScope(project)` instead of `project.basePath`
  string comparisons.
- Keep ephemeral benchmark and evaluation workspaces under `.benchmarks/`
  inside the repo rather than `/tmp/`.
