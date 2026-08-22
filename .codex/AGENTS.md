# Codex lifecycle adapters

This directory maps Codex lifecycle events to repository-owned commands.

- Keep `hooks.json` declarative. Put traversal, mutation, and validation logic
  in the owning script outside this directory.
- Use commands rooted at the canonical workspace and keep hook timeouts explicit.
- Start turn-scoped state at `UserPromptSubmit`; enforce completion at `Stop`.
- Preserve the repository-shape check after guide maintenance so structural
  validation runs only after the guide queue converges.

Verify changes with:

```console
python3 -m json.tool .codex/hooks.json
python3 .github/scripts/test_agents_md_turn_refresh.py
```
