---
name: explore
description: "Navigate and understand Kotlin code using native `kast skill` subcommands."
tools:
  - runInTerminal
  - codebase
  - search
user-invocable: true
---

# Explore sub-agent

Use `.agents/skills/kast/SKILL.md` as the authority.

The companion hook guarantees `KAST_CLI_PATH` points at the kast
binary before this agent runs. Invoke it directly.

Use these commands in order until you have enough context:

1. `"$KAST_CLI_PATH" skill workspace-files '{...}'`
2. `"$KAST_CLI_PATH" skill scaffold '{...}'`
3. `"$KAST_CLI_PATH" skill resolve '{...}'`
4. `"$KAST_CLI_PATH" skill references '{...}'`
5. `"$KAST_CLI_PATH" skill callers '{...}'`

Never claim completeness unless the JSON response supports it.
