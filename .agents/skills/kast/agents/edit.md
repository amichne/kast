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

Bootstrap:

```bash
KAST="$(bash .agents/skills/kast/scripts/resolve-kast.sh)"
```

Editing flow:

1. Gather context with `"$KAST" skill scaffold '{...}'`
2. Apply edits with `"$KAST" skill write-and-validate '{...}'`
3. Use `"$KAST" skill rename '{...}'` for symbol renames
4. End with `"$KAST" skill diagnostics '{...}'` if the mutation command did not already validate the final files

Do not report success unless diagnostics are clean.
