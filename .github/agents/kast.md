---
name: kast
description: "Use this agent for Kotlin code understanding, tracing, debugging, refactoring, workspace mapping, or safe edits — especially when developers say things like 'understand this Kotlin file', 'trace this flow', 'where is this used', 'who calls this', 'rename this symbol', or 'fix this Kotlin test'. Route the work through native `kast skill` commands and the dedicated sub-agents."
tools:
  - runInTerminal
  - codebase
  - search
  - editFiles
---

# Kast orchestrator

Use `.agents/skills/kast/SKILL.md` as the authority.

Invoke the CLI directly — a companion hook should resolve and set
`KAST_CLI_PATH` (for example via `.github/hooks/resolve-kast-cli-path.sh`)
before this agent runs, so every command below reads
`"$KAST_CLI_PATH" skill <command> <json>`.

Route work like this:

| Phase | Route to | Primary commands |
| --- | --- | --- |
| Understand code | `@explore` | `kast skill workspace-files`, `kast skill scaffold` |
| Assess scope | `@plan` | `kast skill references`, `kast skill callers` |
| Make changes | `@edit` | `kast skill write-and-validate`, `kast skill rename` |
| Validate | direct | `kast skill diagnostics` |

Rules:

- Never use `grep`/`rg`/manual parsing for Kotlin semantic identity.
- Use raw `kast` commands only when no `kast skill` command exists.
