# Copilot instructions

- For any Kotlin work, prefer the `kast_*` tools registered by the
  `.github/extensions/kast/` extension: `kast_workspace_files`,
  `kast_scaffold`, `kast_resolve`, `kast_references`, `kast_callers`,
  `kast_metrics`, `kast_rename`, `kast_write_and_validate`,
  `kast_diagnostics`. They replace `view`/`grep`/`edit`/`create` for
  `.kt`/`.kts` source.
- Read `.agents/skills/kast/SKILL.md` for command shape and recovery rules.
  The bash `kast skill <name>` form remains as a fallback; `KAST_CLI_PATH`
  is announced in session-start context.
- TDD: write failing unit tests first. Every change must include tests that prove behavior and regressions are covered.
- Kotlin standards: follow Kotlin style, apply formatting and lints (ktlint/detekt/spotless), avoid platform-specific APIs in shared modules.
- Constitutional code: treat API/model changes as contract changes; preserve schema compatibility and capability advertising unless intentionally changing.
- Clean code: prefer small, single-responsibility units, clear names, and minimal surface area.
- Run all gradle scripts with `--offline` (falling back to normal if issues)

## Backend parity

Any change to an `AnalysisBackend` operation must be applied to **both** `backend-standalone` and `backend-intellij`. Never implement a feature on one backend without auditing the other for corresponding callsites. After changes, verify `parity-tests/` covers the modified operation.

## Resource lifecycle

Background threads, daemons, and resource cleanup must use explicit timeouts. In any `close()`, `shutdown()`, or cleanup method:
- Call `interrupt()` on background threads first
- Then call `join(timeoutMs)` to wait for them to actually terminate — **do not omit the join**
- JUnit `@TempDir` cleanup races with surviving daemon threads on macOS, causing `IOException at ForEachOps.java:184`. The join() ensures threads stop before the test method returns and temp dir is deleted.
- Example: `phase1Thread?.join(2000); phase2Thread?.join(2000)` after `interrupt()` calls.

## Contract surface inventory

Before modifying `EmbeddedSkillResources`, `WrapperOpenApiDocument`,
`AnalysisBackend`, or any packaged artifact manifest, enumerate all
consumers: `docs/openapi.yaml`, `.agents/skills/kast/SKILL.md`,
`.agents/skills/kast/references/*.md`,
`.agents/skills/kast/fixtures/maintenance/**/*`, `.agents/skills/kast/scripts/*`,
`.github/extensions/kast/extension.mjs`,
`kast-cli/build.gradle.kts`, and `kast.sh`/`install.sh`. These are contract
surfaces — a change without updating all consumers silently breaks the
distribution.

## Test path safety and CI cross-platform concerns

Tests run on both ubuntu and macOS in CI — local pass on one OS is not sufficient. Never declare a task complete without verifying CI is green on both platforms.

**Path handling (Linux-specific):** Never compare file paths using `project.basePath` string operations. Use `GlobalSearchScope.projectScope(project)` for IntelliJ scope filtering. `@TempDir` paths in Linux CI do not equal `project.basePath` — tests that pass on macOS will fail in CI.

**Resource race conditions (macOS-specific):** Parallel streams and Java I/O operations can race with JUnit `@TempDir` cleanup on macOS. If a test uses background threads or parallel streams touching filesystem, ensure proper `join()` and cleanup in `close()` — see Resource Lifecycle section below.

## Indexer semantics

"Indexing" in this codebase means **real K2/Analysis API/PSI traversal**, not file enumeration or simple walking. The SQLite source-index store (`.gradle/kast/cache/source-index.db`) is populated from actual K2 compiler symbols and PSI nodes via the `BackgroundIndexer`, not from filename lists. Distinguish between:
- **Indexing** — K2 compiler + PSI symbol extraction, async background thread, stores in SQLite
- **File listing** — simple enumeration, not indexing
- **Gradle workspace discovery** — project structure discovery from `settings.gradle.kts` and source sets

## Process

1. Use `kast_workspace_files` and `kast_scaffold` to understand the target code.
2. Assess impact with `kast_references` + `kast_callers` — but default to **executing changes when intent is clear**. Reserve planning-only for genuinely ambiguous scope. The user will review before merge.
3. Make the change with `kast_write_and_validate` or `kast_rename`.
4. `kast_diagnostics` must return `clean=true` before completing.
5. Run the narrowest Gradle task that proves the change.
6. Update `AGENTS.md`/docs when behavioral or contract rules change.
7. After committing, verify remote CI is green on **both ubuntu and macOS** using `gh pr checks --watch` or the `gh-fix-ci` skill. If `gh-fix-ci` is unavailable, use `gh run list --branch <branch>` + `gh run view <id> --log-failed` directly. Do not declare a task complete with CI red or unverified — local test pass is not sufficient.
