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

Bootstrap:

```bash
KAST="$(bash .agents/skills/kast/scripts/resolve-kast.sh)"
```

Planning sequence:

1. `"$KAST" skill scaffold '{...}'`
2. `"$KAST" skill references '{...}'`
3. `"$KAST" skill callers '{...}'`

Every plan must report the target symbol, affected files, affected symbols,
edit order, and any bounded/truncated results.
