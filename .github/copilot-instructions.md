# Copilot instructions

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
