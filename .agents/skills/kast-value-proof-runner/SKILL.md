---
name: kast-value-proof-runner
description: Run the consolidated Kast evaluation benchmark end-to-end for the current repository when the user explicitly asks to benchmark the current code, run the value proof suite, compare with and without Kast, or generate the executive summary. Reuse the repo-level evaluation scripts and grader/analyzer assets instead of duplicating benchmark logic.
---

# Kast Value Proof Runner

Run the benchmark workflow in `evaluation/` and present the resulting benchmark plus executive summary. The headline metric is `outcome_pass_rate` over `applicability='both'` outcome assertions; never quote raw `pass_rate` from `summary` for cross-config comparison — it is inflated by tautological "Uses kast_X" assertions.

## Workflow

1. Treat the current repository as the target. Keep transient artifacts outside the framework tree under `.benchmarks/evaluation/<timestamp>/`.
2. Bindings:
   - Look for `evaluation/bindings/<repo-name>.json`.
   - If missing, load `references/bindings.md`, copy `evaluation/bindings/template.json`, fill every slot, **and populate every `expected` block** (ground-truth oracles for recall/precision/compile checks). Without `expected`, outcome assertions cannot be graded — they fall back to the LLM grader and the headline becomes unreliable.
   - Record the target's current git SHA into `bindings/<repo>.json:git_sha` so reproducibility lives with the bindings file.
3. Prefer `evaluation/scripts/run_evaluation.py` as the one-command orchestrator. Use the phase-specific scripts below only when you need to inspect or override a step.
4. Render the catalog with `evaluation/scripts/render_prompts.py`. The renderer fails loud if any `{{...}}` placeholder survives — fix the bindings, do not paper over.
5. Scaffold the iteration workspace with `evaluation/scripts/run_value_proof.py`. **Default is now 5 runs per (eval × config)** to give the paired Wilcoxon test enough power; do not override unless you have a reason.
6. Dispatch every run with `evaluation/scripts/dispatch_runs.py`:
   - `with_skill`: Kast skill and `kast_*` tools enabled.
   - `without_skill`: Kast skill AND `kast_*` tools disabled at the harness level. Do not rely on prompt-level inhibition. The dispatcher will auto-run `parse_tool_calls.py` after every successful run, and the aggregator will FLAG the iteration if any `without_skill` run shows a non-zero `kast_calls` count.
   - For `safe-mutations-chain` runs, perform `git stash --include-untracked` before the chain and `git stash pop` (or `git checkout -- .`) after. Chain runs mutate the workspace and contaminate later evals if not isolated. Record the pre/post SHA on the run's `integrity` block.
7. Save each full transcript verbatim to `outputs/transcript.md`. The dispatcher records authoritative executor timing in `timing.json` and retries empty/failed runs.
8. Grade each run with `.agents/skills/skill-creator/agents/grader.md`. Then **always** run `evaluation/scripts/finalize_grading.py --run-dir <run> --workspace-root <target-checkout>` — it merges dispatcher timing, replaces grader-reported tool counts with the deterministic transcript-parsed counts, marks expectations whose applicability does not match the run config as `skipped`, and detects contradictions (`passed=true` with evidence containing `= 0` / `missing` / `not present`).
9. Aggregate with `evaluation/scripts/value_proof_aggregate.py`. Do **not** use the generic `skill-creator/scripts/aggregate_benchmark.py` for this repo-level value benchmark — it lacks the applicability split and the paired Wilcoxon.
10. Run the analyzer with `.agents/skills/skill-creator/agents/analyzer.md` over `benchmark.json`. Then generate `executive-summary.md` plus `executive-summary.html` with `evaluation/scripts/generate_executive_summary.py`.
11. Return a concise summary: `outcome_pass_rate` delta with the Wilcoxon p-value, `kast_calls` and `grep_or_find_calls` deltas (these are the honest "did the skill change behaviour" signal), the count of integrity violations (contradictions, isolation), and the paths to `benchmark.json`, `benchmark.md`, `executive-summary.md`, and `executive-summary.html`.

## Integrity contract — fail loudly

If any of the following are true, STOP and surface to the user before reporting numbers:

- The host cannot create an honest `without_skill` run (no harness-level kast disabling).
- The aggregator reports any `baseline_violations` entry — at least one `without_skill` run touched a kast tool.
- The aggregator reports `contradictions` — at least one `passed=true` verdict had self-contradicting evidence.
- The bindings file lacks `expected` blocks. Outcome grading silently degrades to LLM judgment and the headline number is no longer paired with ground truth.

These are not warnings; they invalidate the run.

## Resources

- `references/bindings.md`: only load when the repo does not already have a bindings file.
- `evaluation/scripts/run_evaluation.py`
- `evaluation/scripts/render_prompts.py`
- `evaluation/scripts/run_value_proof.py`
- `evaluation/scripts/dispatch_runs.py`
- `evaluation/scripts/parse_tool_calls.py`
- `evaluation/scripts/finalize_grading.py`
- `evaluation/scripts/value_proof_aggregate.py`
- `evaluation/scripts/generate_executive_summary.py`
- `.agents/skills/skill-creator/agents/grader.md`
- `.agents/skills/skill-creator/agents/analyzer.md`
