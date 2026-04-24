---
name: kast
description: >
  Semantic Kotlin/JVM navigation and safe refactoring via `kast skill`
  subcommands. Use when a task needs Kotlin symbol resolution, file
  understanding, flow tracing, usages, callers, ambiguous member lookup,
  renames, failing-test diagnosis, or safe edits. Never use grep/rg for Kotlin
  identity.
---

# Kast

Use Kast for Kotlin identity, cross-file navigation, and validated edits.

1. Start with the smallest semantic operation that can answer the request:
   `workspace-files` to find scope, `scaffold` to understand a file/type,
   `resolve` to pin a declaration, `references` for usages, and `callers` for
   flow.
2. If `KAST_CLI_PATH` is empty or the shell says `command not found`, run
   `eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"` and retry.
   Do not start by reading `.kast-version` or the wrapper OpenAPI fixture.
3. Navigate only with `kast skill workspace-files`, `kast skill scaffold`,
   `kast skill resolve`, `kast skill references`, and `kast skill callers`.
4. Mutate only with `kast skill rename`,
   `kast skill write-and-validate`, and `kast skill diagnostics`.
5. Requests use camelCase; responses use snake_case.
6. For ambiguous names or member properties, resolve first, then trace
   usages/callers.
7. If parsing a result fails, inspect a sample object or narrow the query. Stay
   on Kast.
8. Never replace a failed semantic query with `grep`, `rg`, `sed`, or
   hand-edits.
9. After mutation, `ok=false` or dirty diagnostics means the run failed.

Read `references/quickstart.md` for request snippets and recovery tips. Skill
maintenance fixtures live under `fixtures/maintenance`; do not load them during
normal Kotlin navigation.
