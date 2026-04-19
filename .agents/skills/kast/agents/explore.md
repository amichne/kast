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

Bootstrap:

```bash
KAST="$(bash .agents/skills/kast/scripts/resolve-kast.sh)"
```

Use these commands in order until you have enough context:

1. `"$KAST" skill workspace-files '{...}'`
2. `"$KAST" skill scaffold '{...}'`
3. `"$KAST" skill resolve '{...}'`
4. `"$KAST" skill references '{...}'`
5. `"$KAST" skill callers '{...}'`

Never claim completeness unless the JSON response supports it.
