#!/usr/bin/env bash
# Run one zero-cost Copilot SDK benchmark pass against the mock Kast backend,
# summarize the aggregate output with Codex, and publish compact metrics to
# amichne/cast-benchmarks.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"

log() { printf '%s %s\n' "$1" "$2" >&2; }
die() { log "error:" "$*"; exit 1; }

usage() {
  cat <<'USAGE' >&2
Usage: run-single-mock-benchmark.sh [options] [-- <run-benchmark forwarded args>]

Runs one pass of the Copilot SDK benchmark matrix with the mock Kast backend,
then has Codex non-interactively summarize only aggregate/sanitized outputs and
publishes compact metrics to amichne/cast-benchmarks.

Defaults are intentionally narrow:
  --runs-per-config 1
  --max-retries 0
  --kast-backend mock
  --model gpt-5-mini

Options:
  --catalog PATH             Catalog JSON (default: evaluation/catalog.json)
  --bindings PATH            Bindings JSON (default: evaluation/bindings/kast.json)
  --workspace PATH           Local benchmark workspace (default: .benchmarks/copilot-sdk-mock)
  --iteration NAME           Iteration directory (default: mock-single-<UTC timestamp>)
  --run-slug NAME            cast-benchmarks file slug (default: mock-single-<UTC timestamp>)
  --configs LIST             Configs to run (default: with_skill,tool_only,without_skill)
  --concurrency N            Parallel workers (default: 3)
  --timeout-ms N             SDK session idle timeout (default: 180000)
  --model NAME               Zero-cost SDK model (default: gpt-5-mini)
  --history-root PATH        Mock payload history root (default: ~/.copilot/session-state when present; repeatable)
  --no-default-history       Do not auto-mine ~/.copilot/session-state
  --results-repo PATH        Local cast-benchmarks checkout (default: ../cast-benchmarks)
  --results-remote URL       Remote to clone when results repo is absent
  --source-pr N              Source PR number for provenance (auto-detected when possible)
  --dry-run                  Print the benchmark, Codex, and publish contract without executing
  --skip-publish             Do not commit/push to cast-benchmarks
  -h, --help                 Show this help

Environment:
  CAST_BENCHMARKS_REPO       Overrides --results-repo
  CAST_BENCHMARKS_REMOTE     Overrides --results-remote
USAGE
}

utc_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
catalog="${REPO_ROOT}/evaluation/catalog.json"
bindings="${REPO_ROOT}/evaluation/bindings/kast.json"
workspace="${REPO_ROOT}/.benchmarks/copilot-sdk-mock"
iteration="mock-single-${utc_stamp}"
run_slug="mock-single-${utc_stamp}"
configs="with_skill,tool_only,without_skill"
concurrency="3"
timeout_ms="180000"
sdk_model="gpt-5-mini"
results_repo="${CAST_BENCHMARKS_REPO:-${REPO_ROOT}/../cast-benchmarks}"
results_remote="${CAST_BENCHMARKS_REMOTE:-git@github.com:amichne/cast-benchmarks.git}"
source_pr=""
dry_run="0"
skip_publish="0"
history_roots=()
use_default_history="1"
forwarded=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --catalog)        catalog="$2";        shift 2 ;;
    --bindings)       bindings="$2";       shift 2 ;;
    --workspace)      workspace="$2";      shift 2 ;;
    --iteration)      iteration="$2";      shift 2 ;;
    --run-slug)       run_slug="$2";       shift 2 ;;
    --configs)        configs="$2";        shift 2 ;;
    --concurrency)    concurrency="$2";    shift 2 ;;
    --timeout-ms)     timeout_ms="$2";     shift 2 ;;
    --model)          sdk_model="$2";      shift 2 ;;
    --history-root)   history_roots+=("$2"); shift 2 ;;
    --no-default-history) use_default_history="0"; shift ;;
    --results-repo)   results_repo="$2";   shift 2 ;;
    --results-remote) results_remote="$2"; shift 2 ;;
    --source-pr)      source_pr="$2";      shift 2 ;;
    --dry-run)        dry_run="1";         shift ;;
    --skip-publish)   skip_publish="1";    shift ;;
    -h|--help)        usage; exit 0 ;;
    --) shift; forwarded=("$@"); break ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

default_history_root="${HOME}/.copilot/session-state"
if [[ "$use_default_history" == "1" && ${#history_roots[@]} -eq 0 && -d "$default_history_root" ]]; then
  history_roots+=("$default_history_root")
fi

[[ -f "$catalog" ]] || die "catalog file not found: $catalog"
[[ -f "$bindings" ]] || die "bindings file not found: $bindings"
[[ "$run_slug" =~ ^[A-Za-z0-9._-]+$ ]] || die "--run-slug must contain only letters, numbers, dots, underscores, and hyphens"

abspath() {
  python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$1"
}

shell_join() {
  python3 - "$@" <<'PY'
import shlex
import sys
print(" ".join(shlex.quote(arg) for arg in sys.argv[1:]))
PY
}

detect_source_pr() {
  if ! command -v gh >/dev/null 2>&1; then
    return 0
  fi
  gh pr view --json number --jq .number 2>/dev/null || true
}

catalog="$(abspath "$catalog")"
bindings="$(abspath "$bindings")"
workspace="$(abspath "$workspace")"
results_repo="$(abspath "$results_repo")"
iteration_dir="${workspace}/${iteration}"
analysis_output="${iteration_dir}/codex-analysis.md"

source_branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
source_commit="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if [[ -z "$source_pr" ]]; then
  source_pr="$(detect_source_pr)"
fi

benchmark_cmd=(
  bash "${SCRIPT_DIR}/run-benchmark.sh"
  --catalog "$catalog"
  --bindings "$bindings"
  --workspace "$workspace"
  --iteration "$iteration"
  --runs-per-config 1
  --concurrency "$concurrency"
  --max-retries 0
  --model "$sdk_model"
  --timeout-ms "$timeout_ms"
  --configs "$configs"
  --kast-backend mock
)
for history_root in "${history_roots[@]}"; do
  benchmark_cmd+=(--history-root "$history_root")
done
if [[ ${#forwarded[@]} -gt 0 ]]; then
  benchmark_cmd+=("${forwarded[@]}")
fi

codex_model="gpt-5.5"
codex_reasoning_effort="xhigh"
codex_prompt=$(cat <<PROMPT
Analyze this completed Kast Copilot SDK mock-backend benchmark run mechanically.

Use only aggregate and sanitized summary files:
- ${iteration_dir}/benchmark.json
- ${iteration_dir}/benchmark.md
- ${iteration_dir}/executive-summary.md
- ${iteration_dir}/rendered-catalog.json
- ${iteration_dir}/bindings.json

Do not inspect raw transcripts, sdk-events.jsonl, otel.jsonl, inputs.json, final answers, or worktrees.

Write a concise Markdown report covering:
1. run count and configuration coverage,
2. validity and integrity signals, including invalid runs, flaky runs, mock_backend_error, and dirty-worktree flags,
3. summary-level outcome/process/efficiency observations,
4. whether the artifact is publishable as a compact metric snapshot, with calibrated confidence.
PROMPT
)
codex_cmd=(
  codex exec
  --model "$codex_model"
  -c "model_reasoning_effort=\"${codex_reasoning_effort}\""
  -c "approval_policy=\"never\""
  --sandbox danger-full-access
  -C "$REPO_ROOT"
  --output-last-message "$analysis_output"
  "$codex_prompt"
)

print_plan() {
  printf 'benchmark: %s\n' "$(shell_join "${benchmark_cmd[@]}")"
  printf 'codex: codex exec --model %s -c model_reasoning_effort="%s" -c approval_policy="never" --sandbox danger-full-access\n' "$codex_model" "$codex_reasoning_effort"
  printf 'publish: cast-benchmarks repo=%s remote=%s run_slug=%s skip_publish=%s\n' "$results_repo" "$results_remote" "$run_slug" "$skip_publish"
}

if [[ "$dry_run" == "1" ]]; then
  print_plan
  exit 0
fi

prepare_results_repo() {
  if [[ "$skip_publish" == "1" ]]; then
    return 0
  fi
  if [[ -d "${results_repo}/.git" ]]; then
    git -C "$results_repo" fetch origin main
    git -C "$results_repo" switch main
    git -C "$results_repo" pull --ff-only origin main
  else
    mkdir -p "$(dirname "$results_repo")"
    git clone "$results_remote" "$results_repo"
  fi
  if [[ -n "$(git -C "$results_repo" status --porcelain)" ]]; then
    die "results repo has uncommitted changes: $results_repo"
  fi
}

publish_compact_metrics() {
  if [[ "$skip_publish" == "1" ]]; then
    log "info:" "skipping cast-benchmarks publish"
    return 0
  fi
  python3 - \
    "$iteration_dir" \
    "$results_repo" \
    "$run_slug" \
    "$source_branch" \
    "$source_commit" \
    "${source_pr:-}" \
    "$analysis_output" <<'PY'
from __future__ import annotations

import hashlib
import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

iteration_dir = Path(sys.argv[1])
results_repo = Path(sys.argv[2])
run_slug = sys.argv[3]
source_branch = sys.argv[4]
source_commit = sys.argv[5]
source_pr_raw = sys.argv[6]
analysis_output = Path(sys.argv[7])

benchmark_path = iteration_dir / "benchmark.json"
benchmark = json.loads(benchmark_path.read_text())
metadata = benchmark.get("metadata", {})
source: dict[str, Any] = {
    "repo": "amichne/kast",
    "branch": source_branch,
    "commit": source_commit,
}
if source_pr_raw:
    source["pr"] = int(source_pr_raw)

suite = "copilot-sdk-mock-single"
iteration = iteration_dir.name
records: list[dict[str, Any]] = []

def nested(payload: dict[str, Any], *keys: str) -> Any:
    current: Any = payload
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current

def count_kast_calls(tool_calls: dict[str, Any]) -> int:
    return sum(int(value or 0) for key, value in tool_calls.items() if str(key).startswith("kast_"))

def count_generic_search_calls(tool_calls: dict[str, Any]) -> int:
    return sum(int(tool_calls.get(key, 0) or 0) for key in ("grep", "find", "rg"))

def compact_run(run: dict[str, Any]) -> dict[str, Any]:
    combined = run.get("combined") if isinstance(run.get("combined"), dict) else {}
    mechanical = run.get("mechanical") if isinstance(run.get("mechanical"), dict) else {}
    llm = run.get("llm_graded") if isinstance(run.get("llm_graded"), dict) else {}
    summary = combined.get("summary") if isinstance(combined.get("summary"), dict) else {}
    dimensions = combined.get("dimensions") if isinstance(combined.get("dimensions"), dict) else {}
    execution_metrics = (
        combined.get("execution_metrics")
        if isinstance(combined.get("execution_metrics"), dict)
        else mechanical.get("execution_metrics")
        if isinstance(mechanical.get("execution_metrics"), dict)
        else {}
    )
    timing = (
        combined.get("timing")
        if isinstance(combined.get("timing"), dict)
        else mechanical.get("timing")
        if isinstance(mechanical.get("timing"), dict)
        else {}
    )
    integrity = run.get("integrity") if isinstance(run.get("integrity"), dict) else {}
    tool_calls = execution_metrics.get("tool_calls") if isinstance(execution_metrics.get("tool_calls"), dict) else {}
    eval_id = str(run.get("eval_id", ""))
    configuration = str(run.get("configuration", ""))
    run_number = int(run.get("run_number", 0) or 0)
    return {
        "schema_version": 1,
        "source": {
            **source,
            "path": str(iteration_dir / f"eval-{eval_id}" / configuration / f"run-{run_number}"),
        },
        "suite": suite,
        "iteration": iteration,
        "eval_id": eval_id,
        "configuration": configuration,
        "run_id": f"run-{run_number}",
        "run_number": run_number,
        "statuses": {
            "run": run.get("status"),
            "mechanical": mechanical.get("status"),
            "llm": llm.get("status"),
            "combined": combined.get("status"),
        },
        "invalid_reason": run.get("invalid_reason"),
        "summary": summary,
        "dimensions": dimensions,
        "execution_metrics": {
            **execution_metrics,
            "kast_calls": execution_metrics.get("kast_calls", count_kast_calls(tool_calls)),
            "grep_or_find_calls": execution_metrics.get("grep_or_find_calls", count_generic_search_calls(tool_calls)),
        },
        "timing": timing,
        "integrity": integrity,
    }

records = [compact_run(run) for run in benchmark.get("runs", [])]

def mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None

def numeric(records: list[dict[str, Any]], path: tuple[str, ...]) -> list[float]:
    values: list[float] = []
    for record in records:
        value = nested(record, *path)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            values.append(float(value))
    return values

summary_rows: list[dict[str, Any]] = []
groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
for record in records:
    groups[(record["suite"], record["iteration"], record["configuration"])].append(record)

for (group_suite, group_iteration, configuration), group_records in sorted(groups.items()):
    invalid_reasons = Counter(
        str(record.get("invalid_reason"))
        for record in group_records
        if record.get("invalid_reason")
    )
    pass_rates = numeric(group_records, ("summary", "pass_rate"))
    outcome_rates = numeric(group_records, ("summary", "outcome_pass_rate"))
    process_rates = numeric(group_records, ("summary", "process_pass_rate"))
    total_durations = numeric(group_records, ("timing", "total_duration_seconds"))
    executor_durations = numeric(group_records, ("timing", "executor_duration_seconds"))
    summary_rows.append(
        {
            "suite": group_suite,
            "iteration": group_iteration,
            "configuration": configuration,
            "run_count": len(group_records),
            "run_statuses": dict(Counter(str(nested(record, "statuses", "run")) for record in group_records)),
            "combined_statuses": dict(Counter(str(nested(record, "statuses", "combined")) for record in group_records)),
            "mechanical_statuses": dict(Counter(str(nested(record, "statuses", "mechanical")) for record in group_records)),
            "llm_statuses": dict(Counter(str(nested(record, "statuses", "llm")) for record in group_records)),
            "invalid_reason_counts": dict(invalid_reasons),
            "pass_rate_n": len(pass_rates),
            **({"pass_rate_mean": mean(pass_rates)} if pass_rates else {}),
            "outcome_pass_rate_n": len(outcome_rates),
            **({"outcome_pass_rate_mean": mean(outcome_rates)} if outcome_rates else {}),
            "process_pass_rate_n": len(process_rates),
            **({"process_pass_rate_mean": mean(process_rates)} if process_rates else {}),
            "total_duration_seconds_n": len(total_durations),
            **({"total_duration_seconds_mean": mean(total_durations)} if total_durations else {}),
            **({"executor_duration_seconds_mean": mean(executor_durations)} if executor_durations else {}),
            "total_tool_calls_mean": mean(numeric(group_records, ("execution_metrics", "total_tool_calls"))),
            "kast_calls_mean": mean(numeric(group_records, ("execution_metrics", "kast_calls"))),
            "grep_or_find_calls_mean": mean(numeric(group_records, ("execution_metrics", "grep_or_find_calls"))),
            "errors_encountered_total": int(sum(numeric(group_records, ("execution_metrics", "errors_encountered")))),
            "flaky_count": sum(1 for record in group_records if nested(record, "integrity", "flaky") is True),
            "workspace_dirty_post_count": sum(1 for record in group_records if nested(record, "integrity", "workspace_dirty_post") is True),
            "mock_backend_error_count": sum(1 for record in group_records if record.get("invalid_reason") == "mock_backend_error"),
        }
    )

runs_path = results_repo / "runs" / f"{run_slug}-run-metrics.jsonl"
summary_path = results_repo / "summaries" / f"{run_slug}-summary.json"
analysis_path = results_repo / "summaries" / f"{run_slug}-codex-analysis.json"
provenance_path = results_repo / "provenance" / f"{run_slug}.json"
for path in (runs_path, summary_path, analysis_path, provenance_path):
    path.parent.mkdir(parents=True, exist_ok=True)

runs_path.write_text("".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records))
summary_payload = {
    "schema_version": 1,
    "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    "source": source,
    "benchmark": {
        "schema_version": benchmark.get("schema_version"),
        "benchmark_kind": benchmark.get("benchmark_kind"),
        "target_repo": metadata.get("target_repo"),
        "target_git_sha": metadata.get("target_git_sha"),
        "eval_ids": metadata.get("eval_ids"),
        "configurations": metadata.get("configurations"),
        "runs_per_eval_per_config": metadata.get("runs_per_eval_per_config"),
    },
    "run_count": len(records),
    "by_suite_iteration_configuration": summary_rows,
    "codex_analysis_path": str(analysis_path.relative_to(results_repo)),
}
summary_path.write_text(json.dumps(summary_payload, indent=2) + "\n")
analysis_text = analysis_output.read_text() if analysis_output.exists() else "Codex analysis was not generated.\n"
analysis_path.write_text(
    json.dumps(
        {
            "schema_version": 1,
            "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "source": source,
            "format": "markdown",
            "analysis": analysis_text,
        },
        indent=2,
    )
    + "\n"
)

mock_payload = iteration_dir.parent / f"{iteration}-mock-backend.json"
mock_payload_info: dict[str, Any] = {"path": str(mock_payload)}
if mock_payload.exists():
    mock_payload_info["sha256"] = hashlib.sha256(mock_payload.read_bytes()).hexdigest()

provenance_path.write_text(
    json.dumps(
        {
            "schema_version": 1,
            "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "source": source,
            "benchmark_source": {
                "iteration_dir": str(iteration_dir),
                "benchmark_path": str(benchmark_path),
                "analysis_path": str(analysis_output),
            },
            "mock_backend": mock_payload_info,
            "codex_analysis": {
                "model": "gpt-5.5",
                "reasoning_effort": "xhigh",
                "sandbox": "danger-full-access",
                "approval_policy": "never",
            },
            "artifact_policy": "Summary metrics only; raw transcripts, prompts, tool-call logs, SDK events, OTEL events, inputs, worktrees, and final answers are intentionally omitted.",
        },
        indent=2,
    )
    + "\n"
)

print(runs_path)
print(summary_path)
print(analysis_path)
print(provenance_path)
PY

  git -C "$results_repo" add \
    "runs/${run_slug}-run-metrics.jsonl" \
    "summaries/${run_slug}-summary.json" \
    "summaries/${run_slug}-codex-analysis.json" \
    "provenance/${run_slug}.json"
  if git -C "$results_repo" diff --cached --quiet; then
    log "info:" "no cast-benchmarks changes to commit"
  else
    git -C "$results_repo" commit -m "Add mock Copilot SDK benchmark ${run_slug}"
    git -C "$results_repo" push origin HEAD:main
  fi
}

print_plan >&2
mkdir -p "$iteration_dir"
prepare_results_repo

log "info:" "running single mock benchmark into ${iteration_dir}"
"${benchmark_cmd[@]}"

log "info:" "generating executive summary"
python3 "${REPO_ROOT}/evaluation/scripts/generate_executive_summary.py" "$iteration_dir"

log "info:" "running Codex aggregate analysis"
"${codex_cmd[@]}"

log "info:" "publishing compact metric snapshot"
publish_compact_metrics

log "info:" "complete: ${iteration_dir}"
