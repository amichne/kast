---
name: kast-value-proof-runner
description: Run the existing Kast value-proof benchmark end-to-end for the current repository when the user explicitly asks to benchmark the current code, run the value proof suite, compare with and without Kast, or generate the executive summary. Reuse the existing value-proof scripts and grader/analyzer assets instead of duplicating benchmark logic.
---

# Kast Value Proof Runner

Run the benchmark workflow in `.agents/skills/kast/value-proof/` and present the resulting benchmark plus executive summary.

## Workflow

1. Treat the current repository as the target. Keep transient artifacts outside the skill tree under `.benchmarks/kast-value-proof/<timestamp>/`.
2. Look for `.agents/skills/kast/value-proof/bindings/<repo-name>.json`, where `<repo-name>` is the current repo directory name. Reuse it if present.
3. If the bindings file is missing, load `references/bindings.md`, create the bindings from `../kast/value-proof/bindings/template.json`, and validate that `scripts/render_prompts.py` can render the catalog successfully before moving on.
4. Render the catalog with `.agents/skills/kast/value-proof/scripts/render_prompts.py`.
5. Scaffold a fresh iteration workspace with `.agents/skills/kast/value-proof/scripts/run_value_proof.py`, then read `manifest.json`.
6. Dispatch every run with `.agents/skills/kast/value-proof/scripts/dispatch_runs.py`, providing the child-session command template for the current host:
   - `with_skill`: Kast skill and `kast_*` tools enabled
   - `without_skill`: do not use the Kast skill or `kast_*` tools
7. Save each full transcript to `outputs/transcript.md`; the dispatcher records parent-side executor timing in `timing.json` and retries missing or empty transcripts. Do not fake baseline isolation; if the host cannot create an honest `without_skill` run, stop and report that blocker.
8. Grade every run with `.agents/skills/skill-creator/agents/grader.md` and write `grading.json`.
9. Aggregate with `.agents/skills/skill-creator/scripts/aggregate_benchmark.py`, analyze the resulting `benchmark.json` with `.agents/skills/skill-creator/agents/analyzer.md`, and then generate `executive-summary.md` plus `executive-summary.html` with `.agents/skills/kast/value-proof/scripts/generate_executive_summary.py`.
10. Return a concise summary with pass-rate, token, and time deltas plus the paths to `benchmark.json`, `benchmark.md`, `executive-summary.md`, and `executive-summary.html`.

## Resources

- `references/bindings.md`: only load when the repo does not already have a bindings file.
- `.agents/skills/kast/value-proof/scripts/render_prompts.py`
- `.agents/skills/kast/value-proof/scripts/run_value_proof.py`
- `.agents/skills/kast/value-proof/scripts/generate_executive_summary.py`
- `.agents/skills/skill-creator/agents/grader.md`
- `.agents/skills/skill-creator/agents/analyzer.md`
- `.agents/skills/skill-creator/scripts/aggregate_benchmark.py`
