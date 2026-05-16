#!/usr/bin/env bash
# run-benchmark.sh: end-to-end orchestrator that runs the kast evaluation
# suite with the Copilot CLI as the runner, in parallel, on a zero-cost
# model by default.
#
# Pre-wires evaluation/runners/copilot/run-one.sh into
# evaluation/scripts/run_evaluation.py's --dispatch-command-template.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"

log()  { printf '%s %s\n' "$1" "$2" >&2; }
die()  { log "error:" "$*"; exit 1; }

usage() {
  cat <<'USAGE' >&2
Usage: run-benchmark.sh [options] [-- <forwarded args>]

Required:
  --bindings PATH            Bindings JSON (e.g., evaluation/bindings/kast.json)
  --workspace PATH           Benchmark workspace root (e.g., .benchmarks/copilot)

Optional:
  --catalog PATH             Catalog JSON (default: evaluation/catalog.json)
  --iteration NAME           Iteration name (default: iteration-001)
  --runs-per-config N        Runs per (eval x config) (default: 5)
  --concurrency N            Parallel workers (default: 4)
  --max-retries N            Retry count for failed/empty runs (default: 1)
  --model NAME               Copilot model (default: gpt-5-mini, zero-cost)
  --configs LIST             Comma-separated configs (default: with_skill,without_skill)
  --skip-grade               Skip grading phase
  --skip-aggregate           Skip aggregation phase

Anything after `--` is forwarded verbatim to run_evaluation.py (use this
to pass --case repeatedly to restrict to specific case IDs).

Environment:
  COPILOT_MODEL              Override the model (same as --model)
  COPILOT_BIN                Absolute path to the copilot binary
  COPILOT_EXTRA_ARGS         Extra args appended to each `copilot --prompt` call
USAGE
}

catalog="${REPO_ROOT}/evaluation/catalog.json"
bindings=""
workspace=""
iteration="iteration-001"
runs_per_config="5"
concurrency="4"
max_retries="1"
model=""
configs="with_skill,without_skill"
skip_grade=""
skip_aggregate=""
forwarded=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --catalog)         catalog="$2";          shift 2 ;;
    --bindings)        bindings="$2";         shift 2 ;;
    --workspace)       workspace="$2";        shift 2 ;;
    --iteration)       iteration="$2";        shift 2 ;;
    --runs-per-config) runs_per_config="$2";  shift 2 ;;
    --concurrency)     concurrency="$2";      shift 2 ;;
    --max-retries)     max_retries="$2";      shift 2 ;;
    --model)           model="$2";            shift 2 ;;
    --configs)         configs="$2";          shift 2 ;;
    --skip-grade)      skip_grade="--skip-grade";         shift ;;
    --skip-aggregate)  skip_aggregate="--skip-aggregate"; shift ;;
    -h|--help)         usage; exit 0 ;;
    --) shift; forwarded=("$@"); break ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

[[ -n "$bindings"  ]] || { usage; die "--bindings is required"; }
[[ -n "$workspace" ]] || { usage; die "--workspace is required"; }
[[ -f "$bindings"  ]] || die "bindings file not found: $bindings"
[[ -f "$catalog"   ]] || die "catalog file not found: $catalog"

if [[ -n "$model" ]]; then
  export COPILOT_MODEL="$model"
fi

# Extract workspace_root from the bindings so run-one.sh can pass
# --add-dir to Copilot. The eval framework already validates the field's
# presence in load_bindings(), but we need it here too.
workspace_root="$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["workspace_root"])
' "$bindings")"
[[ -n "$workspace_root" ]] || die "bindings missing workspace_root: $bindings"
export KAST_WORKSPACE_ROOT="$workspace_root"

runner="${REPO_ROOT}/evaluation/runners/copilot/run-one.sh"
[[ -x "$runner" ]] || die "runner not executable: $runner (chmod +x it?)"

dispatch_template="bash ${runner}"
dispatch_template+=" --instructions {instructions}"
dispatch_template+=" --transcript {transcript}"
dispatch_template+=" --run-dir {run_dir}"
dispatch_template+=" --eval-id {eval_id}"
dispatch_template+=" --configuration {configuration}"
dispatch_template+=" --run-number {run_number}"
dispatch_template+=" --attempt {attempt}"

exec python3 "${REPO_ROOT}/evaluation/scripts/run_evaluation.py" \
  --catalog "$catalog" \
  --bindings "$bindings" \
  --workspace "$workspace" \
  --iteration "$iteration" \
  --runs-per-config "$runs_per_config" \
  --configs "$configs" \
  --concurrency "$concurrency" \
  --max-retries "$max_retries" \
  --dispatch-command-template "$dispatch_template" \
  ${skip_grade} \
  ${skip_aggregate} \
  "${forwarded[@]}"
