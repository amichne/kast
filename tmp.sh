#!/usr/bin/env bash

cd /Users/amichne/code/kast

ITER=.benchmarks/copilot-sdk-mock/mock-ramp-full-variance-3x-5-20260519T
BINDINGS="$ITER/bindings.json"
WORKSPACE_ROOT="$(jq -r '.workspace_root' "$BINDINGS")"

find "$ITER" -mindepth 4 -maxdepth 4 -type d -name 'run-*' | sort | while read -r run_dir; do
  python3 evaluation/scripts/script_grader.py \
    --run-dir "$run_dir" \
    --bindings "$BINDINGS" \
    --output "$run_dir/mechanical.json" \
    --llm-grade-input-output "$run_dir/llm-grade-input.json"

  python3 evaluation/scripts/finalize_grading.py \
    --run-dir "$run_dir" \
    --workspace-root "$WORKSPACE_ROOT"
done

python3 evaluation/scripts/value_proof_aggregate.py "$ITER" \
  --skill-name kast-value-proof \
  --bindings "$ITER/bindings.json" \
  --catalog "$ITER/rendered-catalog.json"
