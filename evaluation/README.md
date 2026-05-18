# Kast evaluation framework

`evaluation/` is the repo-level source of truth for proving Kast's value on Kotlin/JVM work. It consolidates the
catalog, schemas, bindings, scripts, and fixtures needed to run and extend the benchmark workflow without coupling that
infrastructure to any one SKILL.md.

## What this framework proves

The framework is for **value justification**, not progression-gate maintenance.
`benchmark.json` is the sole authoritative artifact for system-level evaluation, and `benchmark.schema.json` is its
contract. The benchmark now preserves separate `mechanical_summary`, `llm_graded_summary`, and `combined_summary`
surfaces so deterministic evidence never gets blurred with rubric judgments.

The benchmark fixes four primary dimensions:

- `task_completion` — did the system complete the requested task shape
- `accuracy` — did it produce the correct answer or edit set
- `reliability` — did it remain internally consistent and error-free
- `scope_control` — did it avoid unnecessary changes or over-broad results

Efficiency is required supporting evidence, not the headline ranking surface. It remains part of every run and every
configuration summary via transcript size, tool counts, search counts, elapsed time, tokens, and execution errors.

Headline evidence:

- paired deltas on the four primary dimensions
- paired Wilcoxon significance objects for each primary dimension
- invalid-run isolation (baseline contamination, contradictions, ungraded runs)
- supporting efficiency deltas for cost and scope tradeoffs

## Layout

- `catalog.json`: canonical value-justification cases
- `catalog.schema.json`: schema for the catalog contract
- `provenance.json`: curated history coverage for canonical cases plus adjacent task archetypes
- `provenance.schema.json`: schema for curated provenance
- `bindings/`: repo-specific slot bindings plus templates
- `bindings.schema.json`: schema for the bindings contract
- `grading.schema.json`: normalized per-run grading contract
- `mechanical.schema.json`: deterministic per-run evidence contract
- `llm-grade.schema.json`: qualitative per-run grading contract
- `benchmark.schema.json`: authoritative final benchmark contract
- `scripts/`: render, scaffold, dispatch, finalize, aggregate, and orchestration helpers
- `runners/copilot-sdk/`: supported Copilot-backed runner using `@github/copilot-sdk`
- `fixtures/`: scratch assets plus non-canonical history-derived candidate cases

## Running an evaluation

### Copilot SDK Runner

Use the SDK runner for live `with_skill` versus `tool_only` versus `without_skill` benchmark runs.
It registers the same shared `kast_*` tool contract used by the Copilot
extension and avoids the older noninteractive Copilot CLI adapter.

```bash
bash evaluation/runners/copilot-sdk/run-benchmark.sh \
  --bindings evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot-sdk \
  --iteration iteration-001 \
  --runs-per-config 5 \
  --concurrency 4
```

For a one-case smoke run:

```bash
bash evaluation/runners/copilot-sdk/run-benchmark.sh \
  --bindings evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot-sdk-smoke \
  --iteration smoke \
  --runs-per-config 1 \
  --concurrency 1 \
  --timeout-ms 180000 \
  -- --case vp-disambiguate-member
```

For a mock-backend run that avoids the genuine KAST daemon:

```bash
bash evaluation/runners/copilot-sdk/run-benchmark.sh \
  --bindings evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot-sdk-mock \
  --iteration mock-smoke \
  --runs-per-config 1 \
  --concurrency 2 \
  --kast-backend mock
```

Mock payloads are generated from archived `sdk-events.jsonl` history when
`--history-root` is supplied, then completed from the catalog and bindings
oracles. This mode measures the agent path against presumed BAU KAST responses;
it is not evidence that the genuine backend is healthy.

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
runner handles SDK/runtime capture, the mechanical grader writes `mechanical.json` plus `llm-grade-input.json`, an
optional LLM grader writes `llm-grade.json`, and finalization merges them into `grading.json`.

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
   python3 evaluation/scripts/script_grader.py \
     --run-dir .benchmarks/evaluation/iteration-001/eval-XYZ/with_skill/run-1 \
     --bindings .benchmarks/evaluation/iteration-001/bindings.json

   # Optional qualitative grader writes llm-grade.json from llm-grade-input.json

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
   configs without conflating mechanical and LLM-graded evidence

## History-backed provenance

The canonical benchmark still lives in `catalog.json`, but the suite now keeps a separate curated provenance sidecar in
`provenance.json`.

Why the split:

- `catalog.json` stays runnable and oracle-focused
- `provenance.json` shows which canonical cases are grounded in real Copilot history
- raw session exports do **not** belong in git; only sanitized excerpts and rationales do

`provenance.json` covers two things:

1. `case_coverage`: one entry for every canonical `vp-*` case, marked either `matched` with sanitized session evidence
   or `gap` when history does not yet justify the case cleanly.
2. `novel_archetypes`: real task families seen in history that are not yet in the canonical catalog.

Validate the history assets with:

```bash
python3 evaluation/scripts/validate_history_assets.py
```

That validator checks that:

- every canonical case in `catalog.json` has provenance coverage
- provenance does not reference unknown canonical ids
- the staged history-derived candidate catalog in
  `evaluation/fixtures/staging/copilot-history-candidates.json` stays structurally sound and does not collide with canonical ids

## Seeding candidate cases from history

`evaluation/fixtures/staging/copilot-history-candidates.json` is a staging area for real task shapes mined from history that
are not yet stable enough to promote into the canonical catalog.

Use it when:

- a history-derived task is clearly valuable
- the current bindings/oracle surface does not yet support it cleanly
- you want to preserve the case shape now, then add better grading/oracles later

Promote a candidate into `catalog.json` only after its grading story is durable enough to produce meaningful benchmark
measurements.

## Interpreting results

- Prefer `paired_analysis.statistics.score_metrics` over raw counts
- Treat any entry in `paired_analysis.issues.invalid_runs` as excluded from the headline
- Use `paired_analysis.pairs` to see which evals moved each primary dimension
- Use `summary.by_configuration.*.efficiency` and paired efficiency deltas to judge tradeoffs, not just wins

## Migration note

This framework replaces the old `.agents/skills/kast/value-proof/` tree as the canonical repo-level location for
value-justification benchmarking. Durable assets now live in `evaluation/`; transient run workspaces belong under
`.benchmarks/`.

## Authoritative scope

The only artifacts that contribute to benchmark evidence are:

- `evaluation/catalog.json` — the canonical eval cases
- `evaluation/benchmark.schema.json` — the benchmark contract
- `evaluation/grading.schema.json` — the per-run grading contract
- `evaluation/scripts/` — rendering, grading, aggregation, and summary generation
- `evaluation/bindings/` — repo-specific slot bindings with ground-truth oracles

The following are explicitly **not** benchmark evidence:

- `evaluation/fixtures/staging/` — candidate cases without durable grading
- `.agents/skills/kast/evals/` — skill-level progression-gate evals (removed)
- `.agents/skills/kast/fixtures/maintenance/evals/` — old-format maintenance evals (removed)
- `.agents/skills/skill-creator/scripts/aggregate_benchmark.py` — generic aggregator without paired statistical testing
- Any file under `.agents/skills/*/history/` — progression-gate tracking artifacts

If an observer examining this repo finds eval-shaped artifacts outside `evaluation/`,
those artifacts do not participate in the canonical benchmark and their outputs should
not be cited as system-level performance claims.
