---
name: plan
description: "Assess change scope and produce a change plan using native `kast skill` subcommands."
tools:
  - runInTerminal
  - codebase
user-invocable: true
---

# Plan sub-agent

Use `.agents/skills/kast/SKILL.md` as the authority.

The companion hook guarantees `KAST_CLI_PATH` points at the kast
binary before this agent runs. Invoke it directly.

Planning sequence:

1. `"$KAST_CLI_PATH" skill scaffold '{...}'`
2. `"$KAST_CLI_PATH" skill references '{...}'`
3. `"$KAST_CLI_PATH" skill callers '{...}'`

Every plan must report the target symbol, affected files, affected symbols,
edit order, and any bounded/truncated results.
