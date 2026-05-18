#!/usr/bin/env bash
# run-benchmark.sh: end-to-end orchestrator that runs the kast evaluation
# suite using the @github/copilot-sdk runner, in parallel, on a zero-cost
# model by default.
#
# Pre-wires evaluation/runners/copilot-sdk/run-one.mjs into
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
  --workspace PATH           Benchmark workspace root (e.g., .benchmarks/copilot-sdk)

Optional:
  --catalog PATH             Catalog JSON (default: evaluation/catalog.json)
  --iteration NAME           Iteration name (default: iteration-001)
  --runs-per-config N        Runs per (eval x config) (default: 5)
  --concurrency N            Parallel workers (default: 4)
  --max-retries N            Retry count for failed/empty runs (default: 1)
  --model NAME               Model name (default: gpt-5-mini)
  --timeout-ms N             SDK session idle timeout in milliseconds (default: 180000)
  --configs LIST             Comma-separated configs (default: with_skill,tool_only,without_skill)
  --grade-command-template T Shell command template for grading
  --skip-dispatch            Render and scaffold only; do not launch SDK sessions
  --skip-grade               Skip grading phase
  --skip-aggregate           Skip aggregation phase

Anything after '--' is forwarded verbatim to run_evaluation.py.

Environment:
  SDK_MODEL             Override the model (same as --model)
  SDK_TIMEOUT_MS        Override the SDK session timeout (same as --timeout-ms)
  KAST_BIN              Path to the kast binary (default: kast)
  KAST_WORKSPACE_ROOT   Workspace root passed to kast rpc calls (extracted from bindings)
  KAST_EVAL_SKIP_NPM_CI Set to 1 when runner dependencies are already installed
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
timeout_ms=""
configs="with_skill,tool_only,without_skill"
grade_template=""
skip_dispatch=""
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
    --timeout-ms)      timeout_ms="$2";       shift 2 ;;
    --configs)         configs="$2";          shift 2 ;;
    --grade-command-template) grade_template="$2"; shift 2 ;;
    --skip-dispatch)   skip_dispatch="--skip-dispatch";      shift ;;
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

abspath() {
  python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$1"
}

bindings="$(abspath "$bindings")"
catalog="$(abspath "$catalog")"

if [[ -n "$model" ]]; then
  export SDK_MODEL="$model"
fi
if [[ -n "$timeout_ms" ]]; then
  export SDK_TIMEOUT_MS="$timeout_ms"
fi

workspace_root="$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["workspace_root"])
' "$bindings")"
[[ -n "$workspace_root" ]] || die "bindings missing workspace_root: $bindings"
export KAST_WORKSPACE_ROOT="$workspace_root"

runner="${REPO_ROOT}/evaluation/runners/copilot-sdk/run-one.mjs"
[[ -f "$runner" ]] || die "runner not found: $runner"

if [[ "${KAST_EVAL_SKIP_NPM_CI:-0}" != "1" ]]; then
  npm ci --prefix "${REPO_ROOT}/evaluation/runners/copilot-sdk" --silent
fi

dispatch_template="node ${runner}"
dispatch_template+=" --instructions {instructions}"
dispatch_template+=" --transcript {transcript}"
dispatch_template+=" --run-dir {run_dir}"
dispatch_template+=" --eval-id {eval_id}"
dispatch_template+=" --configuration {configuration}"
dispatch_template+=" --run-number {run_number}"
dispatch_template+=" --attempt {attempt}"

shell_quote() {
  python3 -c 'import shlex, sys; print(shlex.quote(sys.argv[1]))' "$1"
}

if [[ -z "$skip_grade" && -z "$grade_template" ]]; then
  grader="${REPO_ROOT}/evaluation/scripts/script_grader.py"
  [[ -f "$grader" ]] || die "script grader not found: $grader"
  grade_template="python3 $(shell_quote "$grader") --run-dir {run_dir} --bindings $(shell_quote "$bindings")"
fi

run_args=(
  python3 "${REPO_ROOT}/evaluation/scripts/run_evaluation.py"
  --catalog "$catalog"
  --bindings "$bindings"
  --workspace "$workspace"
  --iteration "$iteration"
  --runs-per-config "$runs_per_config"
  --configs "$configs"
  --concurrency "$concurrency"
  --max-retries "$max_retries"
  --dispatch-command-template "$dispatch_template"
)

if [[ -n "$grade_template" ]]; then
  run_args+=(--grade-command-template "$grade_template")
fi
if [[ -n "$skip_dispatch" ]]; then
  run_args+=("$skip_dispatch")
fi
if [[ -n "$skip_grade" ]]; then
  run_args+=("$skip_grade")
fi
if [[ -n "$skip_aggregate" ]]; then
  run_args+=("$skip_aggregate")
fi
run_args+=("${forwarded[@]}")

exec "${run_args[@]}"
