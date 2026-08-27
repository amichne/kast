# Repository scripts

This directory owns executable checks and the tests that prove them.

- Prefer Python's standard library so hooks and CI can run before project
  dependencies are installed.
- Keep checks deterministic, accept an explicit repository root, and use Git
  plumbing rather than parsing human-formatted command output.
- Expected failures must map to a finite result and a nonzero exit status with
  an actionable message.
- `check-repository-shape.py` exempts only the two native IntelliJ descriptor roots from the
  retired plugin-root text marker: the private indexer descriptor and the independently installed
  hosted plugin descriptor. All other occurrences remain violations.
- `agents_md_turn_refresh.py` computes guide work but does not synthesize
  semantic Markdown. The active turn must inspect the changed files and own the
  resulting guidance.
- `scaffold_agents_md_turn_guides.py` creates inheritance guides only for
  directories that directly own files. Run it with `--write`, or use
  `--write --refresh` to retarget generated guides to the nearest substantive
  owner. Use `--prune-empty-owners` to preview exact generated inheritance
  templates that no longer own files, and add `--write` to remove exactly that
  previewed set. A guide with any substantive extension is never generated.
- `test_agents_md_turn_refresh.py` is the focused authority for prompt-start
  isolation, reverse breadth-first ordering, guide outcomes, and convergence.
- `verify_pr633_program.py` and `collect_pr633_delivery_evidence.py` use only the standard library
  and bind program, registry, cleanup, and exact-head CI evidence to closed checks.

Run:

```console
python3 .github/scripts/test_agents_md_turn_refresh.py
python3 -m py_compile .github/scripts/agents_md_turn_refresh.py .github/scripts/test_agents_md_turn_refresh.py
```
