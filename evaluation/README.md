# Kast evaluation framework

`evaluation/` is the repo-level source of truth for proving Kast's value on Kotlin/JVM work. It consolidates the catalog, schemas, bindings, scripts, and fixtures needed to run and extend the benchmark workflow without coupling that infrastructure to any one SKILL.md.

## What this framework proves

The framework is for **value justification**, not progression-gate maintenance. The headline metric is `outcome_pass_rate` over `applicability='both'` outcome assertions, paired between `with_skill` and `without_skill` configurations. That keeps the comparison focused on whether Kast improves results rather than on tautological process assertions like "used kast_*".

Key signals:

- `outcome_pass_rate`: fair cross-config quality delta
- paired Wilcoxon p-value: whether the per-eval delta is likely signal rather than noise
- `kast_calls` and `grep_or_find_calls`: whether the skill changed tool-routing behavior
- integrity checks: baseline-isolation violations, grading contradictions, retries/flakiness

## Layout

- `catalog.json`: canonical value-justification cases
- `catalog.schema.json`: schema for the catalog contract
- `bindings/`: repo-specific slot bindings plus templates
- `bindings.schema.json`: schema for the bindings contract
- `grading.schema.json`: normalized per-run grading contract
- `scripts/`: render, scaffold, dispatch, finalize, aggregate, and orchestration helpers
- `fixtures/`: scratch or smoke-test fixture assets used to validate the framework itself

## Running an evaluation

### One-command workflow

Use `scripts/run_evaluation.py` when you want one orchestrator to render the catalog, scaffold the iteration workspace, dispatch runs, finalize grading, and aggregate the benchmark.

```bash
python3 evaluation/scripts/run_evaluation.py \
  --catalog evaluation/catalog.json \
  --bindings evaluation/bindings/kast.json \
  --workspace .benchmarks/evaluation \
  --iteration iteration-001 \
  --runs-per-config 5 \
  --dispatch-command-template 'your-runner --instructions {run_dir}/run_instructions.md --transcript {transcript}' \
  --grade-command-template 'your-grader --run-dir {run_dir} --output {grading}'
```

The command templates are intentionally pluggable. `run_evaluation.py` handles the durable workspace layout; your runner/grader handle transcript production and raw grading.

### Manual phases

If you want to inspect each step separately:

1. Render the catalog:

   ```bash
   python3 evaluation/scripts/render_prompts.py \
     --catalog evaluation/catalog.json \
     --bindings evaluation/bindings/kast.json \
     --output .benchmarks/evaluation/iteration-001-rendered-catalog.json
   ```

2. Scaffold the iteration:

   ```bash
   python3 evaluation/scripts/run_value_proof.py \
     --catalog .benchmarks/evaluation/iteration-001-rendered-catalog.json \
     --workspace .benchmarks/evaluation \
     --runs-per-config 5 \
     --iteration iteration-001
   ```

3. Dispatch runs:

   ```bash
   python3 evaluation/scripts/dispatch_runs.py \
     .benchmarks/evaluation/iteration-001 \
     --command-template 'your-runner --instructions {instructions} --transcript {transcript}'
   ```

4. Grade and finalize each run, then aggregate:

   ```bash
   python3 evaluation/scripts/finalize_grading.py \
     --run-dir .benchmarks/evaluation/iteration-001/eval-XYZ/with_skill/run-1 \
     --workspace-root /absolute/path/to/target/checkout

   python3 evaluation/scripts/value_proof_aggregate.py \
     .benchmarks/evaluation/iteration-001 \
     --skill-name kast-value-proof \
     --bindings .benchmarks/evaluation/iteration-001/bindings.json \
     --catalog .benchmarks/evaluation/iteration-001/rendered-catalog.json
   ```

5. Generate the executive summary:

   ```bash
   python3 evaluation/scripts/generate_executive_summary.py \
     .benchmarks/evaluation/iteration-001
   ```

## Adding a new case

1. Add a case to `catalog.json` with:
   - a durable prompt
   - explicit `expectations`
   - `kind`, `applicability`, and `graded_by` on each expectation
   - oracle paths when script grading can verify the result
2. Add or update the slot data in the relevant `bindings/<repo>.json`
3. Re-render the catalog and run at least one evaluation iteration
4. Inspect `benchmark.json`, `benchmark.md`, and the executive summary to confirm the new case discriminates between configs

## Interpreting results

- Prefer `run_summary.delta.outcome_pass_rate` over raw `pass_rate`
- Treat any `paired_stats.baseline_violations` or `paired_stats.contradictions` entry as an invalid run
- Use `paired_stats.eval_deltas` to see which cases actually moved the benchmark
- Use transcript/time/tool-call deltas to judge tradeoffs, not just wins

## Migration note

This framework replaces the old `.agents/skills/kast/value-proof/` tree as the canonical repo-level location for value-justification benchmarking. Durable assets now live in `evaluation/`; transient run workspaces belong under `.benchmarks/`.
