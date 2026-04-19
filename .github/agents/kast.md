---
name: kast
description: "Kast-first Kotlin semantic analysis orchestrator. Routes tasks to @explore, @plan, or @edit and uses native `kast skill` subcommands."
tools:
  - runInTerminal
  - codebase
  - search
  - editFiles
agents:
  - explore
  - plan
  - edit
---

# Kast orchestrator

Use `.agents/skills/kast/SKILL.md` as the authority.

Invoke the CLI directly — a companion hook sets `KAST_CLI_PATH`
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
