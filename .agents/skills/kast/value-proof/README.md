# Kast value-proof suite

This suite proves the practical value of the Kast skill against a baseline
that lacks the current skill instructions. Use it for skill rewrites, command
routing changes, and any claim that Kast improves Kotlin/JVM work over raw
navigation.

Keep this material out of normal Kast invocations. The active `SKILL.md` points
here only for maintenance and benchmarking.

## Iteration contract

Each iteration compares a candidate skill with a baseline in the same
workspace. For an existing skill rewrite, snapshot the old skill before
editing and use that snapshot as the baseline. For a brand-new value-proof run,
use `without_skill` only when no previous skill exists.

Every completed iteration must produce:

- `manifest.json` mapping durable eval ids to on-disk `eval-*` directories;
- `eval_metadata.json` for each eval case (carrying the catalog's structured
  expectations: `id`, `kind`, `applicability`, `graded_by`, `oracle`);
- one run directory per configuration and run number (default: 5 runs);
- `outputs/transcript.md` for each run;
- `outputs/tool_calls.jsonl` produced by `parse_tool_calls.py` (deterministic;
  do NOT trust the LLM grader's tool counts);
- `timing.json` with dispatcher-recorded executor duration and attempt count;
- `grading.json` v2 — produced by the LLM grader and then **always**
  finalised with `finalize_grading.py`, which merges authoritative timing,
  injects deterministic tool-call counts, marks non-applicable expectations
  as `skipped`, computes `outcome_pass_rate` (the only fair cross-config
  metric), and records `integrity.contradictions` /
  `integrity.baseline_isolation_violation` / `integrity.attempts`;
- `benchmark.json` and `benchmark.md` produced by `value_proof_aggregate.py`,
  which adds paired Wilcoxon p-values, Tukey outlier flags, and per-eval
  deltas;
- a row appended to `history/progression.json` for cross-iteration trend;
- an analyzer note set that calls out discriminating assertions, weak
  assertions, variance, and token or timing tradeoffs;
- a generated `eval-viewer` HTML review artifact before the next rewrite.

Do not record a promoted case in `history/progression.json` until the benchmark
has real transcripts, grading, and aggregation evidence — and never with
`integrity.contradictions` or `integrity.baseline_isolation_violation`
non-empty.

## Quick start with the default binding

The default binding targets `konditional`. Update
`bindings/konditional.json` first if the target checkout or symbol names have
changed.

1. Render concrete prompts from the parameterized catalog:

   ```bash
   python3 scripts/render_prompts.py \
     --catalog catalog.json \
     --bindings bindings/konditional.json \
     --output rendered-catalog.json
   ```

2. Scaffold the iteration workspace (5 runs per config is the default —
   keep it; 3 is too few for paired Wilcoxon to discriminate):

   ```bash
   python3 scripts/run_value_proof.py \
     --catalog rendered-catalog.json \
     --workspace ../kast-value-proof-workspace \
     --runs-per-config 5 \
     --iteration iteration-001
   ```

3. Dispatch each run from its `run_instructions.md`.

   `manifest.json` maps eval ids to their generated directories, including
   any `chain_id` that must execute serially. `run_manifest.json` still lists
   every generated instruction file for manual inspection.

   ```bash
   python3 scripts/dispatch_runs.py \
     ../kast-value-proof-workspace/iteration-001 \
     --command-template 'your-runner --instructions {instructions} --transcript {transcript}'
   ```

   The dispatcher parallelizes independent runs, serializes runs that share a
   `chain_id`, records parent-side timing, and retries missing or empty
   transcripts. For candidate runs, use the candidate Kast skill. For baseline
   runs on an existing skill rewrite, use a snapshot of the old skill. Save the
   complete transcript to `outputs/transcript.md`.

4. Capture timing and token data in each run's `timing.json`.

   If a runner reports `total_tokens` and `duration_ms` only once, copy it
   immediately. Missing timing is acceptable only when the executor did not
   expose it; mark that explicitly in the file.

5. Grade each run with the skill-creator grading schema, then finalise:

   Use `/Users/amichne/.agents/skills/skill-creator/agents/grader.md` as the
   grading guide. After grading lands its raw `grading.json`, run:

   ```bash
   python3 scripts/finalize_grading.py \
     --run-dir ../kast-value-proof-workspace/iteration-001/eval-XYZ/with_skill/run-1 \
     --workspace-root /absolute/path/to/target/checkout \
     --strict
   ```

   This step is mandatory — it merges authoritative dispatcher timing,
   injects the deterministic tool-call counts produced by
   `parse_tool_calls.py`, marks `with_skill_only` expectations as `skipped`
   in `without_skill` runs (so the headline pass-rate is fair), and rejects
   self-contradictory verdicts. `--strict` fails the script when integrity
   issues are detected; remove it only if you want to inspect the issues
   first.

6. Aggregate the benchmark with the value-proof-aware aggregator:

   ```bash
   python3 scripts/value_proof_aggregate.py \
     ../kast-value-proof-workspace/iteration-001 \
     --skill-name kast-value-proof \
     --bindings bindings/konditional.json \
     --catalog ../kast-value-proof-workspace/iteration-001/rendered-catalog.json
   ```

   Use this rather than `aggregate_benchmark.py` from skill-creator. The
   value-proof aggregator splits process vs outcome assertions, restricts
   the cross-config delta to `applicability='both'` outcome assertions
   (kills the tautology-inflated headline), runs paired Wilcoxon, flags
   Tukey outliers, surfaces baseline-isolation violations and grading
   contradictions, and appends to `history/progression.json` so iteration
   trends are persisted.

7. Run the analyzer pass against `benchmark.json`.

   Use `/Users/amichne/.agents/skills/skill-creator/agents/analyzer.md` and
   write the findings into `benchmark.json` notes or a sibling analysis file.

8. Generate the executive summary:

   ```bash
   python3 scripts/generate_executive_summary.py \
     --benchmark ../kast-value-proof-workspace/iteration-001/benchmark.json \
     --bindings bindings/konditional.json \
     --output ../kast-value-proof-workspace/iteration-001/executive-summary.md
   ```

9. Generate the review artifact before the next rewrite:

   ```bash
   python3 /Users/amichne/.agents/skills/skill-creator/eval-viewer/generate_review.py \
     ../kast-value-proof-workspace/iteration-001 \
     --skill-name kast \
     --benchmark ../kast-value-proof-workspace/iteration-001/benchmark.json \
     --static ../kast-value-proof-workspace/iteration-001/review.html
   ```

For iteration 2 and later, add
`--previous-workspace ../kast-value-proof-workspace/iteration-00N` to the
review command so the human reviewer can compare against the prior iteration.

## Using another Kotlin/JVM codebase

1. Copy `bindings/template.json` to `bindings/<project>.json`.
2. Fill every slot with real symbols from the target checkout.
3. Render the catalog with that binding.
4. Run the same iteration contract.

Choose symbols that make raw text search tempting but wrong: ambiguous member
names, overloaded function names, cross-module classes, sealed hierarchies,
large structural files, and safe rename targets.

## Files

- `bindings.schema.json`: Schema for mapping abstract eval slots to concrete
  codebase symbols. Each slot supports an optional `expected` ground-truth
  block (recall/precision oracles, expected file lists, compile commands,
  module file counts).
- `bindings/konditional.json`: Default binding for the `konditional`
  repository, with populated ground truth.
- `catalog.json`: Parameterized eval cases. Each expectation declares
  `kind` (`outcome` | `process`), `applicability`
  (`both` | `with_skill_only` | `without_skill_only`), `graded_by`
  (`script` | `llm`), and an optional `oracle` reference into the bindings
  ground truth.
- `grading.schema.json`: v2 grading contract. Adds `outcome_pass_rate` (the
  fair cross-config metric), structured `tool_calls`, `kast_calls`,
  `grep_or_find_calls`, and an `integrity` block.
- `scripts/render_prompts.py`: Hydrates `{{SLOT.field}}` template variables;
  fails loud on any unresolved placeholder.
- `scripts/run_value_proof.py`: Creates iteration workspaces,
  `manifest.json`, `run_manifest.json`, and per-run instructions. Default
  runs-per-config is 5.
- `scripts/dispatch_runs.py`: Dispatches scaffolded runs with concurrency,
  chain serialization, parent-side timing, transcript guards, and an
  automatic `parse_tool_calls.py` step on each successful run.
- `scripts/parse_tool_calls.py`: Deterministic transcript → JSONL tool-call
  log. Counts kast tool invocations and grep/find/ls invocations by
  inspecting fenced JSON tool blocks, XML tool blocks, inline call markers,
  and fenced bash blocks. Pure prose mentions are explicitly NOT counted.
- `scripts/finalize_grading.py`: Normalises a raw grader output by merging
  authoritative dispatcher timing, replacing grader-reported tool counts
  with deterministic counts, marking non-applicable expectations as
  skipped, computing the outcome pass-rate, and detecting contradictions
  + baseline-isolation violations.
- `scripts/value_proof_aggregate.py`: Iteration aggregator with paired
  Wilcoxon, applicability-aware pass-rate, Tukey outlier detection, and
  history append.
- `scripts/generate_executive_summary.py`: Creates Markdown and HTML
  executive summaries from `benchmark.json`.
- `history/progression.json`: Non-regression ledger; the aggregator appends
  one row per iteration with `outcome_pass_rate` deltas and Wilcoxon p.
