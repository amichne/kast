# Kast Value Proof Suite

This suite proves the practical value of native Kast tools against a baseline that lacks semantic Kotlin navigation. It keeps durable eval definitions inside the skill tree and writes transient run artifacts to an external workspace.

## Quick start using konditional as target

1. Render prompts:

   ```bash
   python scripts/render_prompts.py \
     --catalog catalog.json \
     --bindings bindings/konditional.json \
     --output rendered-catalog.json
   ```

2. Scaffold the workspace:

   ```bash
   python scripts/run_value_proof.py \
     --catalog rendered-catalog.json \
     --workspace ../kast-value-proof-workspace \
     --runs-per-config 3
   ```

3. Execute each run by following `run_instructions.md` in each run directory.
   `run_manifest.json` lists every generated instruction file for the iteration.

4. Grade each run using `../skill-creator/agents/grader.md`.

5. Aggregate results:

   ```bash
   python ../../skill-creator/scripts/aggregate_benchmark.py \
     ../kast-value-proof-workspace/iteration-001 \
     --skill-name kast-value-proof
   ```

6. Analyze patterns with `../skill-creator/agents/analyzer.md` against `benchmark.json`.

7. Generate the enterprise deliverable:

   ```bash
   python scripts/generate_executive_summary.py \
     --benchmark ../kast-value-proof-workspace/iteration-001/benchmark.json \
     --bindings bindings/konditional.json \
     --output ../kast-value-proof-workspace/iteration-001/executive-summary.md
   ```

8. Open the interactive viewer:

   ```bash
   python ../../skill-creator/eval-viewer/generate_review.py \
     ../kast-value-proof-workspace/iteration-001 \
     --benchmark ../kast-value-proof-workspace/iteration-001/benchmark.json
   ```

## Using your own codebase

1. Copy `bindings/template.json` to `bindings/my-project.json`.
2. Fill in each slot with a representative symbol from your codebase.
3. Run the same workflow with `--bindings bindings/my-project.json`.

## Files

- `bindings.schema.json`: schema for mapping abstract eval slots to concrete codebase symbols.
- `bindings/konditional.json`: default bindings for the konditional repository.
- `catalog.json`: ten parameterized value-proof eval cases.
- `scripts/render_prompts.py`: hydrates `{{SLOT.field}}` template variables.
- `scripts/run_value_proof.py`: creates the run workspace, `run_manifest.json`, and per-run instructions.
- `scripts/generate_executive_summary.py`: creates Markdown and HTML executive summaries from `benchmark.json`.
- `history/progression.json`: empty progression ledger for future non-regression tracking.
