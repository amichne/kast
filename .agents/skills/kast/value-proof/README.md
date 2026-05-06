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

- `eval_metadata.json` for each eval case;
- one run directory per configuration and run number;
- `outputs/transcript.md` for each run;
- `timing.json` with duration and token data when available;
- `grading.json` using the skill-creator fields `text`, `passed`, and
  `evidence` for each expectation;
- `benchmark.json` and `benchmark.md`;
- an analyzer note set that calls out discriminating assertions, weak
  assertions, variance, and token or timing tradeoffs;
- a generated `eval-viewer` HTML review artifact before the next rewrite.

Do not record a promoted case in `history/progression.json` until the benchmark
has real transcripts, grading, and aggregation evidence.

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

2. Scaffold the iteration workspace:

   ```bash
   python3 scripts/run_value_proof.py \
     --catalog rendered-catalog.json \
     --workspace ../kast-value-proof-workspace \
     --runs-per-config 3 \
     --iteration iteration-001
   ```

3. Execute each run from its `run_instructions.md`.

   For candidate runs, use the candidate Kast skill. For baseline runs on an
   existing skill rewrite, use a snapshot of the old skill. Save the complete
   transcript to `outputs/transcript.md`.

4. Capture timing and token data in each run's `timing.json`.

   If a runner reports `total_tokens` and `duration_ms` only once, copy it
   immediately. Missing timing is acceptable only when the executor did not
   expose it; mark that explicitly in the file.

5. Grade each run with the skill-creator grading schema.

   Use `/Users/amichne/.agents/skills/skill-creator/agents/grader.md` as the
   grading guide. Programmatic checks are preferred when an expectation can be
   verified from the transcript or output files.

6. Aggregate the benchmark:

   ```bash
   python3 /Users/amichne/.agents/skills/skill-creator/scripts/aggregate_benchmark.py \
     ../kast-value-proof-workspace/iteration-001 \
     --skill-name kast \
     --skill-path /Users/amichne/code/kast/.agents/skills/kast
   ```

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
  codebase symbols.
- `bindings/konditional.json`: Default binding for the `konditional`
  repository.
- `catalog.json`: Parameterized value-proof eval cases.
- `scripts/render_prompts.py`: Hydrates `{{SLOT.field}}` template variables.
- `scripts/run_value_proof.py`: Creates iteration workspaces and per-run
  instructions.
- `scripts/generate_executive_summary.py`: Creates Markdown and HTML
  executive summaries from `benchmark.json`.
- `history/progression.json`: Non-regression ledger for promoted cases and
  benchmark history.
