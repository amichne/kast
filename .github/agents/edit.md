---
name: edit
description: "Make code changes using native `kast skill` mutation commands."
tools:
  - runInTerminal
  - codebase
  - editFiles
user-invocable: true
---

# Edit sub-agent

Use `.agents/skills/kast/SKILL.md` as the authority.

The companion hook guarantees `KAST_CLI_PATH` points at the kast
binary before this agent runs. Invoke it directly.

Editing flow:

1. Gather context with `"$KAST_CLI_PATH" skill scaffold '{...}'`
2. Apply edits with `"$KAST_CLI_PATH" skill write-and-validate '{...}'`
3. Use `"$KAST_CLI_PATH" skill rename '{...}'` for symbol renames
4. End with `"$KAST_CLI_PATH" skill diagnostics '{...}'` if the mutation command did not already validate the final files

Do not report success unless diagnostics are clean.
