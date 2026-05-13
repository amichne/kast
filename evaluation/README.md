# Kast evaluation framework

`evaluation/` is the repo-level source of truth for proving Kast's value on Kotlin/JVM work. It consolidates the
catalog, schemas, bindings, scripts, and fixtures needed to run and extend the benchmark workflow without coupling that
infrastructure to any one SKILL.md.

## What this framework proves

The framework is for **value justification**, not progression-gate maintenance.
`benchmark.json` is the sole authoritative artifact for system-level evaluation, and `benchmark.schema.json` is its
contract.

The benchmark fixes four primary dimensions:

- `task_completion` — did the system complete the requested task shape
- `accuracy` — did it produce the correct answer or edit set
- `reliability` — did it remain internally consistent and error-free
- `scope_control` — did it avoid unnecessary changes or over-broad results

Efficiency is required supporting evidence, not the headline ranking surface. It remains part of every run and every
configuration summary via transcript size, tool counts, search counts, elapsed time, and execution errors.

Headline evidence:

- paired deltas on the four primary dimensions
- paired Wilcoxon significance objects for each primary dimension
- invalid-run isolation (baseline contamination, contradictions, ungraded runs)
- supporting efficiency deltas for cost and scope tradeoffs

## Layout

- `catalog.json`: canonical value-justification cases
- `catalog.schema.json`: schema for the catalog contract
- `bindings/`: repo-specific slot bindings plus templates
- `bindings.schema.json`: schema for the bindings contract
- `grading.schema.json`: normalized per-run grading contract
- `benchmark.schema.json`: authoritative final benchmark contract
- `scripts/`: render, scaffold, dispatch, finalize, aggregate, and orchestration helpers
- `fixtures/`: scratch or smoke-test fixture assets used to validate the framework itself

## Running an evaluation

### One-command workflow

Use `scripts/run_evaluation.py` when you want one orchestrator to render the catalog, scaffold the iteration workspace,
dispatch runs, finalize grading, and aggregate the benchmark.

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

The command templates are intentionally pluggable. `run_evaluation.py` handles the durable workspace layout; your
runner/grader handle transcript production and raw grading.

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
    - `kind`, `dimension`, `applicability`, and `graded_by` on each expectation
    - oracle paths when script grading can verify the result
2. Add or update the slot data in the relevant `bindings/<repo>.json`
3. Re-render the catalog and run at least one evaluation iteration
4. Inspect `benchmark.json`, `benchmark.md`, and the executive summary to confirm the new case discriminates between
   configs

## Interpreting results

- Prefer `paired_analysis.statistics.score_metrics` over raw counts
- Treat any entry in `paired_analysis.issues.invalid_runs` as excluded from the headline
- Use `paired_analysis.pairs` to see which evals moved each primary dimension
- Use `summary.by_configuration.*.efficiency` and paired efficiency deltas to judge tradeoffs, not just wins

## Migration note

This framework replaces the old `.agents/skills/kast/value-proof/` tree as the canonical repo-level location for
value-justification benchmarking. Durable assets now live in `evaluation/`; transient run workspaces belong under
`.benchmarks/`.
