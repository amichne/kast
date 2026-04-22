---
name: kast
description: >
  Semantic Kotlin/JVM navigation and safe refactoring via `kast skill`
  subcommands. Use it for Kotlin file understanding, flow tracing, usages,
  callers, ambiguous members, renames, failing tests, and safe edits—even when
  the user only says "where is this wired up" or "who sets this field". Never
  use grep/rg for Kotlin identity.
---

# Kast

Use Kast for Kotlin identity, cross-file navigation, and validated edits.

1. If `KAST_CLI_PATH` is empty or the shell says `command not found`, run
   `eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"` and retry.
   Do not start by reading `.kast-version` or `references/wrapper-openapi.yaml`.
2. Navigate with the smallest semantic command:
   `kast skill workspace-files`, `kast skill scaffold`,
   `kast skill resolve`, `kast skill references`, `kast skill callers`.
3. Mutate only with `kast skill rename`,
   `kast skill write-and-validate`, and `kast skill diagnostics`.
4. Requests use camelCase; responses use snake_case.
5. For ambiguous names or member properties, resolve first, then trace
   usages/callers.
6. If parsing a result fails, inspect a sample object or narrow the query. Stay
   on Kast.
7. Never replace a failed semantic query with `grep`, `rg`, `sed`, or
   hand-edits.
8. After mutation, `ok=false` or dirty diagnostics means the run failed.

Read `references/quickstart.md` for request snippets and recovery tips. Read
`references/wrapper-openapi.yaml` only for exact schema details.
