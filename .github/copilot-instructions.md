# Copilot instructions

- Use the `@kast` agent for all Kotlin semantic analysis tasks.
- Use `@explore` to navigate and understand Kotlin code semantically.
- Use `@plan` to assess change scope before editing.
- Use `@edit` to make code changes with built-in validation.
- TDD: write failing unit tests first. Every change must include tests that prove behavior and regressions are covered.
- Kotlin standards: follow Kotlin style, apply formatting and lints (ktlint/detekt/spotless), avoid platform-specific APIs in shared modules.
- Constitutional code: treat API/model changes as contract changes; preserve schema compatibility and capability advertising unless intentionally changing.
- Clean code: prefer small, single-responsibility units, clear names, and minimal surface area.

Process:
1. `@explore` to understand the target code.
2. `@plan` to assess impact and produce a change plan.
3. `@edit` to make the change with `kast skill write-and-validate` or `kast skill rename`.
4. `kast skill diagnostics` must return `clean=true` before completing.
5. Run the narrowest Gradle task that proves the change.
6. Update `AGENTS.md`/docs when behavioral or contract rules change.
7. Ensure local `./build.sh` and CI pass before merging.
