# Repository scripts

This directory owns executable checks and the tests that prove them.

- Prefer Python's standard library so hooks and CI can run before project
  dependencies are installed.
- Keep checks deterministic, accept an explicit repository root, and use Git
  plumbing rather than parsing human-formatted command output.
- Expected failures must map to a finite result and a nonzero exit status with
  an actionable message.
- `agents_md_turn_refresh.py` computes guide work but does not synthesize
  semantic Markdown. The active turn must inspect the changed files and own the
  resulting guidance.
- `test_agents_md_turn_refresh.py` is the focused authority for prompt-start
  isolation, reverse breadth-first ordering, guide outcomes, and convergence.

Run:

```console
python3 .github/scripts/test_agents_md_turn_refresh.py
python3 -m py_compile .github/scripts/agents_md_turn_refresh.py .github/scripts/test_agents_md_turn_refresh.py
```
