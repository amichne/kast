#!/usr/bin/env bash
set -euo pipefail

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  readonly BENCHMARK_SCRIPT_SOURCED=true
else
  readonly BENCHMARK_SCRIPT_SOURCED=false
fi
benchmark_repository_root="$(cd -- "${BASH_SOURCE[0]%/*}/../.." && pwd)"
readonly BENCHMARK_COMMAND_SUPERVISOR="$benchmark_repository_root/.github/scripts/release/benchmark/benchmark-command-supervisor.py"
readonly BENCHMARK_EVIDENCE_AGGREGATOR="$benchmark_repository_root/.github/scripts/release/benchmark/aggregate-indexing-benchmark-evidence.py"
BENCHMARK_PYTHON_BIN="$(command -v python3)"
readonly BENCHMARK_PYTHON_BIN

readonly COLD_INDEX_LIMIT_MILLIS=2700000
readonly IDENTITY_CAPTURE_LIMIT_MILLIS=300000
readonly TEARDOWN_COMMAND_LIMIT_MILLIS=30000
readonly ROLE_COMMAND_LIMIT_MILLIS=300000
readonly FINALIZATION_LIMIT_MILLIS=30000
readonly PHASE_REGRESSION_PERCENT=15
readonly PHASE_REGRESSION_MILLIS=60000
readonly DISK_REGRESSION_PERCENT=15
readonly DISK_REGRESSION_BYTES=268435456
readonly REQUIRED_PHASES='setup configure runtimeAdmission workspaceIndex coldIndex graphRefresh graphSummary semanticIdentity'
readonly COMPARABLE_PHASES='setup configure coldIndex graphRefresh graphSummary semanticIdentity'

usage() {
  printf '%s\n' \
    "Usage: benchmark-real-repositories.sh \\" \
    "  --name <slug> \\" \
    "  --repository <https://github.com/owner/repo.git> \\" \
    "  --revision <40-hex commit> \\" \
    "  --graph-file <relative Kotlin path> \\" \
    "  --relationships-enabled <true|false> \\" \
    "  --stable-bundle <linux setup tarball> \\" \
    "  --candidate-bundle <linux setup tarball> \\" \
    "  --evidence-output <JSON file> \\" \
    '  --cache-root <directory>'
}

valid_graph_file() {
  local path="$1" component
  local -a components
  [[ -n "$path" && "$path" != /* && "$path" == *.kt ]] || return 1
  IFS=/ read -r -a components <<<"$path"
  for component in "${components[@]}"; do
    [[ "$component" =~ ^[A-Za-z0-9._-]+$ && "$component" != . && "$component" != .. ]] \
      || return 1
  done
}

gradle_workspace_for() {
  local graph_path="$1" repository_root="$2" workspace
  workspace="${graph_path%/*}"
  while [[ "$workspace" != "$repository_root" \
      && ! -f "$workspace/settings.gradle" \
      && ! -f "$workspace/settings.gradle.kts" ]]; do
    [[ "$workspace" == */* ]] || return 1
    workspace="${workspace%/*}"
    [[ -n "$workspace" ]] || workspace=/
  done
  [[ -f "$workspace/settings.gradle" || -f "$workspace/settings.gradle.kts" ]] || return 1
  printf '%s\n' "$workspace"
}

configure_gradle_java_paths() {
  local gradle_user_dir="$1" gradle_java_home="${2:-}" java_home="${3:-}"
  [[ -n "$gradle_java_home" ]] || return 0
  [[ -d "$gradle_java_home" ]] \
    || { printf 'error: Gradle Java home not found: %s\n' "$gradle_java_home" >&2; return 1; }
  [[ -d "$java_home" ]] \
    || { printf 'error: Java home not found: %s\n' "$java_home" >&2; return 1; }
  printf 'org.gradle.java.installations.paths=%s,%s\n' "$gradle_java_home" "$java_home" \
    >"$gradle_user_dir/gradle.properties"
}

epoch_millis() {
  python3 - <<'PY'
import time
print(time.time_ns() // 1_000_000)
PY
}

monotonic_millis() {
  python3 - <<'PY'
import time
print(time.monotonic_ns() // 1_000_000)
PY
}

sha256_file() {
  local path="$1" deadline
  deadline="$(role_command_deadline)"
  run_supervised_command "$deadline" bundle-digest "$BENCHMARK_PYTHON_BIN" - "$path" <<'PY'
import hashlib
import sys
from pathlib import Path

digest = hashlib.sha256()
with Path(sys.argv[1]).open("rb") as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(chunk)
print(digest.hexdigest())
PY
}

record_phase() {
  local phase_name="$1" started_epoch="$2" started_monotonic="$3"
  local finished_epoch finished_monotonic
  finished_epoch="$(epoch_millis)"
  finished_monotonic="$(monotonic_millis)"
  record_phase_at \
    "$phase_name" "$started_epoch" "$started_monotonic" \
    "$finished_epoch" "$finished_monotonic"
}

record_phase_at() {
  local phase_name="$1" started_epoch="$2" started_monotonic="$3"
  local finished_epoch="$4" finished_monotonic="$5" duration
  duration=$((finished_monotonic - started_monotonic))
  ((duration >= 0)) || duration=0
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$phase_name" "$started_epoch" "$finished_epoch" \
    "$started_monotonic" "$finished_monotonic" "$duration" \
    >>"$benchmark_phases_file"
}

run_strict() (
  set -euo pipefail
  "$@"
)

run_supervised_command() {
  local deadline_monotonic_ms="$1" operation="$2"
  shift 2
  local term_grace_millis="${KAST_RELEASE_COMMAND_TERM_GRACE_MILLIS:-5000}"
  local kill_grace_millis="${KAST_RELEASE_COMMAND_KILL_GRACE_MILLIS:-2000}"
  local test_signal_helper="${KAST_BENCHMARK_TEST_SIGNAL_HELPER:-}"
  local -a supervisor_args
  [[ "$deadline_monotonic_ms" =~ ^[1-9][0-9]*$ ]] \
    || { printf 'error: command deadline must be a positive monotonic millisecond value\n' >&2; return 2; }
  [[ "$term_grace_millis" =~ ^[1-9][0-9]*$ \
      && "$kill_grace_millis" =~ ^[1-9][0-9]*$ ]] \
    || { printf 'error: command grace periods must be positive milliseconds\n' >&2; return 2; }
  [[ -x "$BENCHMARK_COMMAND_SUPERVISOR" ]] \
    || { printf 'error: benchmark command supervisor is unavailable\n' >&2; return 2; }

  supervisor_args=(
    "$BENCHMARK_COMMAND_SUPERVISOR" run
    --deadline-monotonic-ms "$deadline_monotonic_ms"
    --operation "$operation"
    --term-grace-millis "$term_grace_millis"
    --kill-grace-millis "$kill_grace_millis"
  )
  if [[ -n "${benchmark_command_events_file:-}" ]]; then
    supervisor_args+=(--event-log "$benchmark_command_events_file")
  fi
  if [[ -n "$test_signal_helper" \
      || "${KAST_BENCHMARK_TEST_MODE:-false}" == true \
      || "${KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER:-false}" == true ]]; then
    if [[ "$BENCHMARK_SCRIPT_SOURCED" != true ]]; then
      printf 'error: test signal helper is unavailable in executable mode\n' >&2
      return 2
    fi
    supervisor_args+=(--test-sourced)
    [[ "${KAST_BENCHMARK_TEST_MODE:-false}" != true ]] \
      || supervisor_args+=(--test-mode)
    [[ "${KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER:-false}" != true ]] \
      || supervisor_args+=(--test-allow-signal-helper)
    [[ -z "$test_signal_helper" ]] \
      || supervisor_args+=(--test-signal-helper "$test_signal_helper")
  fi
  "$BENCHMARK_PYTHON_BIN" "${supervisor_args[@]}" -- "$@"
}

run_command_with_monotonic_deadline() {
  local deadline_monotonic_ms="$1"
  shift
  run_supervised_command \
    "$deadline_monotonic_ms" "${KAST_BENCHMARK_SUPERVISED_OPERATION:-command}" "$@"
}

role_command_deadline() {
  local now deadline
  if [[ -n "${benchmark_command_deadline_override:-}" ]]; then
    printf '%s\n' "$benchmark_command_deadline_override"
    return
  fi
  now="$(monotonic_millis)"
  deadline=$((now + ROLE_COMMAND_LIMIT_MILLIS))
  if [[ "${benchmark_cold_budget_active:-false}" == true \
      && -n "${benchmark_cold_deadline_monotonic_ms:-}" \
      && "$benchmark_cold_deadline_monotonic_ms" -lt "$deadline" ]]; then
    deadline="$benchmark_cold_deadline_monotonic_ms"
  fi
  printf '%s\n' "$deadline"
}

kastctl_operation() {
  local arguments=" $* "
  case "$arguments" in
    *' setup '*) printf 'setup\n' ;;
    *' config '*) printf 'config\n' ;;
    *' developer runtime up '*) printf 'runtime-up\n' ;;
    *' developer runtime status '*) printf 'runtime-status\n' ;;
    *' developer runtime stop '*) printf 'runtime-stop\n' ;;
    *' agent workspace-files '*) printf 'workspace-files\n' ;;
    *' agent graph '*' --operation refresh '*) printf 'graph-refresh\n' ;;
    *' agent graph '*' --operation summary '*) printf 'graph-summary\n' ;;
    *) printf 'kastctl\n' ;;
  esac
}

run_kastctl_with_cold_budget() {
  local deadline operation
  if [[ -n "${benchmark_command_deadline_override:-}" ]]; then
    deadline="$benchmark_command_deadline_override"
  elif [[ "${benchmark_cold_budget_active:-false}" == true ]]; then
    [[ -n "${benchmark_cold_deadline_monotonic_ms:-}" ]] \
      || { printf 'error: active cold-phase budget has no deadline\n' >&2; return 2; }
    deadline="$benchmark_cold_deadline_monotonic_ms"
  else
    deadline="$(role_command_deadline)"
  fi
  operation="$(kastctl_operation "$@")"
  run_supervised_command "$deadline" "$operation" \
    env \
      HOME="$benchmark_user_dir" \
      KAST_HOME="$benchmark_kast_home" \
      KAST_CACHE_HOME="$benchmark_cache_dir" \
      GRADLE_USER_HOME="$benchmark_gradle_dir" \
      KAST_WORKSPACE_ID="release-benchmark-$name-$benchmark_role" \
      KAST_BENCHMARK_RUN_ID="$benchmark_run_marker" \
      "$active_kastctl" "$@"
}

run_json_command() {
  local output="$1" result
  shift
  if run_kastctl_with_cold_budget --output json "$@" >"$output"; then
    return 0
  else
    result=$?
    [[ ! -f "$output" ]] || print_file_stderr "$output"
    return "$result"
  fi
}

print_file_stderr() {
  local path="$1" line
  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line" >&2
  done <"$path"
}

workspace_index_state() {
  local output="$1"
  run_supervised_command "$(role_command_deadline)" workspace-state-classification \
    "$BENCHMARK_PYTHON_BIN" - "$output" <<'PY'
import json
import sys
from pathlib import Path

try:
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    raise SystemExit(2)

cardinality = payload.get("result", {}).get("cardinality", {})
if payload.get("ok") is not True:
    raise SystemExit(2)
if cardinality.get("type") == "EXACT":
    raise SystemExit(0 if cardinality.get("totalCount", 0) > 0 else 2)
if cardinality.get("type") == "KNOWN_MINIMUM":
    raise SystemExit(1)
raise SystemExit(2)
PY
}

is_semantic_not_ready() {
  local output="$1"
  run_supervised_command "$(role_command_deadline)" semantic-readiness-classification \
    "$BENCHMARK_PYTHON_BIN" - "$output" <<'PY'
import json
import sys
from pathlib import Path

try:
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)

codes = {
    "RUNTIME_NOT_READY",
    "SEMANTIC_EVIDENCE_NOT_READY",
    "GRAPH_EVIDENCE_UNAVAILABLE",
    "INDEXING_IN_PROGRESS",
}
error = payload.get("error", {})
rpc_error = error.get("details", {}).get("rpcError", {})
if error.get("code") in codes or rpc_error.get("code") in codes:
    raise SystemExit(0)
raise SystemExit(1)
PY
}

runtime_is_durably_admitted() {
  local output="$1"
  run_supervised_command "$(role_command_deadline)" runtime-admission-classification \
    "$BENCHMARK_PYTHON_BIN" - "$output" <<'PY'
import json
import sys
from pathlib import Path

try:
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)
result = payload.get("result", payload)
runtime = result.get("runtime") if isinstance(result, dict) else None
if (
    isinstance(runtime, dict)
    and runtime.get("state") in {"STARTING", "INDEXING", "READY"}
    and isinstance(runtime.get("ownership"), dict)
    and runtime["ownership"].get("assessment") == "OWNED"
):
    raise SystemExit(0)

# v0.21.6 has no layered ownership projection. Its endpoint-backed status is
# admissible only after the selected process is alive and reachable.
selected = result.get("selected") if isinstance(result, dict) else None
if not isinstance(selected, dict):
    raise SystemExit(1)
status = selected.get("runtimeStatus")
if (
    selected.get("pidAlive") is True
    and selected.get("reachable") is True
    and isinstance(status, dict)
    and status.get("state") in {"INDEXING", "READY"}
):
    raise SystemExit(0)
raise SystemExit(1)
PY
}

# This wrapper is intentionally initialized when the file is sourced. Tests and
# helper callers do not need a runtime sampler, while the executable runner
# installs benchmark_progress_sample_impl below.
benchmark_progress_sample() {
  if declare -F benchmark_progress_sample_impl >/dev/null; then
    benchmark_progress_sample_impl "$@"
  fi
}

wait_poll_interval() {
  local seconds="$1"
  [[ "$seconds" =~ ^[0-9]+$ ]] \
    || { printf 'error: poll interval must be a non-negative integer\n' >&2; return 2; }
  ((seconds > 0)) || return 0
  IFS= read -r -t "$seconds" </dev/null || true
}

wait_for_exact_workspace_index() {
  local output="$1" timeout_ms="$2" state command_result
  local poll_seconds="${KAST_RELEASE_INDEX_POLL_SECONDS:-5}"
  local deadline_monotonic now_monotonic
  shift 2
  [[ "$poll_seconds" =~ ^[0-9]+$ ]] \
    || { printf 'error: workspace index poll seconds must be a non-negative integer\n' >&2; return 1; }
  benchmark_workspace_poll_retries="${benchmark_workspace_poll_retries:-0}"
  benchmark_semantic_not_ready_retries="${benchmark_semantic_not_ready_retries:-0}"
  if [[ -n "${benchmark_cold_deadline_monotonic_ms:-}" ]]; then
    deadline_monotonic="$benchmark_cold_deadline_monotonic_ms"
  else
    deadline_monotonic=$(($(monotonic_millis) + timeout_ms))
  fi

  while true; do
    command_result=0
    run_json_command "$output" "$@" || command_result=$?
    benchmark_progress_sample WORKSPACE_INDEX
    if ((command_result != 0)); then
      if ! is_semantic_not_ready "$output"; then
        return "$command_result"
      fi
      benchmark_semantic_not_ready_retries=$((benchmark_semantic_not_ready_retries + 1))
      printf 'Semantic evidence is not ready; waiting for exact workspace evidence\n' >&2
    elif workspace_index_state "$output"; then
      return 0
    else
      state=$?
      if [[ "$state" -ne 1 ]]; then
        print_file_stderr "$output"
        printf 'error: workspace indexing returned invalid or empty exact evidence\n' >&2
        return 1
      fi
      benchmark_workspace_poll_retries=$((benchmark_workspace_poll_retries + 1))
      printf 'Workspace indexing is partial; waiting for exact evidence\n' >&2
    fi

    now_monotonic="$(monotonic_millis)"
    if ((now_monotonic >= deadline_monotonic)); then
      print_file_stderr "$output"
      printf 'error: workspace indexing did not reach exact evidence within the shared %sms cold deadline\n' \
        "$timeout_ms" >&2
      return 1
    fi
    wait_poll_interval "$poll_seconds"
  done
}

capture_workspace_identity_pages() {
  local count_output="$1" output_directory="$2" workspace_root="$3"
  local expected_total page_number=0 returned_total=0 next_page_token=''
  local page_output page_result returned_count token
  local -a command_args
  expected_total="$(run_supervised_command \
    "$(role_command_deadline)" workspace-count-classification \
    "$BENCHMARK_PYTHON_BIN" - "$count_output" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
cardinality = payload.get("result", {}).get("cardinality", {})
value = cardinality.get("totalCount")
if cardinality.get("type") != "EXACT" or not isinstance(value, int) or isinstance(value, bool) or value <= 0:
    raise SystemExit(1)
print(value)
PY
)" || { printf 'error: exact workspace count is unavailable for identity capture\n' >&2; return 1; }
  run_supervised_command "$(role_command_deadline)" workspace-pages-create \
    mkdir -p "$output_directory"
  while :; do
    page_number=$((page_number + 1))
    if ((page_number > expected_total + 1)); then
      printf 'error: workspace identity pagination exceeded exact cardinality\n' >&2
      return 1
    fi
    printf -v page_output '%s/page-%06d.json' "$output_directory" "$page_number"
    command_args=(
      agent workspace-files
      --workspace-root "$workspace_root"
      --fields path
      --limit 200
    )
    if [[ -n "$next_page_token" ]]; then
      command_args+=(--page-token "$next_page_token")
    fi
    run_json_command "$page_output" "${command_args[@]}" || return
    page_result="$(run_supervised_command \
      "$(role_command_deadline)" workspace-page-classification \
      "$BENCHMARK_PYTHON_BIN" - "$page_output" "$expected_total" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = int(sys.argv[2])
result = payload.get("result", {})
files = result.get("files")
cardinality = result.get("cardinality", {})
returned = result.get("returnedCount")
token = result.get("nextPageToken")
if (
    payload.get("ok") is not True
    or cardinality.get("type") != "EXACT"
    or cardinality.get("totalCount") != expected
    or not isinstance(files, list)
    or not isinstance(returned, int)
    or isinstance(returned, bool)
    or returned != len(files)
    or (token is not None and (not isinstance(token, str) or not token))
):
    raise SystemExit(1)
print(f"{returned}\t{token if token is not None else '-'}")
PY
)" || { printf 'error: workspace identity page is invalid\n' >&2; return 1; }
    IFS=$'\t' read -r returned_count token <<<"$page_result"
    returned_total=$((returned_total + returned_count))
    if ((returned_total > expected_total)); then
      printf 'error: workspace identity pages exceed exact cardinality\n' >&2
      return 1
    fi
    if [[ "$token" == - ]]; then
      break
    fi
    next_page_token="$token"
  done
  if ((returned_total != expected_total)); then
    printf 'error: workspace identity pages do not cover exact cardinality\n' >&2
    return 1
  fi
}

find_published_workspace_pointer() {
  run_supervised_command "$(role_command_deadline)" publication-pointer \
    "$BENCHMARK_PYTHON_BIN" - "$@" <<'PY'
import sys
from pathlib import Path

pointers = set()
for raw_root in sys.argv[1:]:
    root = Path(raw_root)
    if not root.is_dir():
        continue
    for candidate in root.rglob("current.json"):
        if candidate.parent.name == "semantic-generations" and candidate.is_file():
            pointers.add(candidate.resolve())
if len(pointers) != 1:
    print(
        f"error: expected one published workspace pointer, found {len(pointers)}",
        file=sys.stderr,
    )
    raise SystemExit(1)
print(next(iter(pointers)))
PY
}

is_source_generation_conflict() {
  local output="$1"
  run_supervised_command "$(role_command_deadline)" graph-conflict-classification \
    "$BENCHMARK_PYTHON_BIN" - "$output" <<'PY'
import json
import sys
from pathlib import Path

try:
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)

error = payload.get("error", {})
details = error.get("details", {}).get("rpcError", {}).get("data", {}).get("details", {})
if (
    error.get("code") != "CONFLICT"
    or details.get("expectedGeneration") is None
    or details.get("actualGeneration") is None
):
    raise SystemExit(1)
PY
}

run_generation_bound_graph_refresh() {
  local output="$1" attempt graph_refresh_attempts=3
  shift
  benchmark_graph_retries=0
  for ((attempt = 1; attempt <= graph_refresh_attempts; attempt += 1)); do
    if run_json_command "$output" "$@"; then
      return 0
    fi
    if [[ "$attempt" -eq "$graph_refresh_attempts" ]] \
        || ! is_source_generation_conflict "$output"; then
      return 1
    fi
    benchmark_graph_retries=$((benchmark_graph_retries + 1))
    printf 'Source generation changed during graph refresh; retrying (%s/%s)\n' \
      "$attempt" "$graph_refresh_attempts" >&2
  done
  return 1
}

verify_benchmark_evidence() {
  local workspace_output="$1" refresh_output="$2" graph_output="$3" expected_graph_path="$4"
  local workspace_root="$5" workspace_pages_directory="$6" published_pointer="$7"
  local deadline_monotonic_ms="$8" correctness_output="$9"
  run_supervised_command "$deadline_monotonic_ms" semantic-identity "$BENCHMARK_PYTHON_BIN" - \
    "$workspace_output" "$refresh_output" "$graph_output" "$expected_graph_path" \
    "$workspace_root" "$workspace_pages_directory" "$published_pointer" \
    "$deadline_monotonic_ms" "$correctness_output" <<'PY'
import hashlib
import json
import math
import sqlite3
import sys
import time
import urllib.parse
from pathlib import Path, PurePosixPath

(
    workspace_output,
    refresh_output,
    graph_output,
    expected_graph_path,
    workspace_root,
    workspace_pages_directory,
    published_pointer,
    deadline_monotonic_ms,
    correctness_output,
) = sys.argv[1:]
deadline_monotonic = int(deadline_monotonic_ms) / 1000.0

def require_budget():
    if time.monotonic() >= deadline_monotonic:
        raise SystemExit("semantic identity capture exceeded its monotonic deadline")

def canonical_sha256(value):
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()

root = Path(workspace_root).resolve()
if not root.is_dir():
    raise SystemExit(f"workspace root is unavailable: {root}")

def root_normalized_path(raw_path):
    if not isinstance(raw_path, str) or not raw_path or "\\" in raw_path:
        raise SystemExit(f"semantic identity path is not canonical: {raw_path!r}")
    candidate = Path(raw_path)
    if candidate.is_absolute():
        try:
            relative = candidate.resolve(strict=False).relative_to(root)
        except ValueError as error:
            raise SystemExit(f"semantic identity path escapes the workspace root: {raw_path}") from error
        raw_path = relative.as_posix()
    pure = PurePosixPath(raw_path)
    if pure.is_absolute() or not pure.parts or any(part in {"", ".", ".."} for part in pure.parts):
        raise SystemExit(f"semantic identity path is not root-normalized: {raw_path}")
    normalized = pure.as_posix()
    if normalized != raw_path:
        raise SystemExit(f"semantic identity path is not canonical: {raw_path}")
    return normalized

workspace_files = json.loads(Path(workspace_output).read_text(encoding="utf-8"))
cardinality = workspace_files.get("result", {}).get("cardinality", {})
workspace_total = cardinality.get("totalCount")
if (
    cardinality.get("type") != "EXACT"
    or not isinstance(workspace_total, int)
    or isinstance(workspace_total, bool)
    or workspace_total <= 0
):
    raise SystemExit(f"workspace indexing was not complete: {cardinality}")

workspace_page_paths = sorted(Path(workspace_pages_directory).glob("page-*.json"))
if not workspace_page_paths:
    raise SystemExit("workspace identity pages are unavailable")
workspace_identities = []
for page_index, page_path in enumerate(workspace_page_paths):
    require_budget()
    page = json.loads(page_path.read_text(encoding="utf-8"))
    result = page.get("result", {})
    page_cardinality = result.get("cardinality", {})
    files = result.get("files")
    if (
        page.get("ok") is not True
        or page_cardinality.get("type") != "EXACT"
        or page_cardinality.get("totalCount") != workspace_total
        or not isinstance(files, list)
        or result.get("returnedCount") != len(files)
    ):
        raise SystemExit(f"workspace identity page is incomplete: {page_path}")
    next_token = result.get("nextPageToken")
    if page_index < len(workspace_page_paths) - 1:
        if not isinstance(next_token, str) or not next_token:
            raise SystemExit(f"workspace identity page omitted its continuation: {page_path}")
    elif next_token is not None:
        raise SystemExit("workspace identity capture stopped before the final page")
    for file in files:
        if not isinstance(file, dict):
            raise SystemExit(f"workspace identity record is invalid: {file!r}")
        workspace_identities.append(root_normalized_path(file.get("relativePath")))
workspace_identities = sorted(workspace_identities)
if len(workspace_identities) != workspace_total or len(set(workspace_identities)) != workspace_total:
    raise SystemExit("workspace identity set does not match exact workspace cardinality")

refresh = json.loads(Path(refresh_output).read_text(encoding="utf-8"))
coverage = refresh.get("result", {}).get("coverage", {}).get("files", [])
expected_graph_path = root_normalized_path(expected_graph_path)
symbol_count = refresh.get("result", {}).get("symbolCount")
refresh_generation = refresh.get("result", {}).get("generation")
refreshed_paths = sorted(
    root_normalized_path(file.get("path"))
    for file in coverage
    if file.get("status") == "REFRESHED"
)
removed_paths = sorted(
    root_normalized_path(file.get("path"))
    for file in coverage
    if file.get("status") == "REMOVED" and isinstance(file.get("path"), str)
)
if (
    not refresh.get("ok")
    or not isinstance(symbol_count, int)
    or isinstance(symbol_count, bool)
    or symbol_count <= 0
    or refreshed_paths != [expected_graph_path]
    or any(file.get("status") not in {"REFRESHED", "REMOVED"} for file in coverage)
):
    raise SystemExit(f"Semantic graph refresh was incomplete: {refresh}")

graph = json.loads(Path(graph_output).read_text(encoding="utf-8"))
graph_result = graph.get("result", {})
graph_generation = graph_result.get("generation")
node_count = graph_result.get("nodeCount")
edge_occurrence_count = graph_result.get("edgeOccurrenceCount")
weighted_edge_count = graph_result.get("weightedEdgeCount")
if (
    not graph.get("ok")
    or not isinstance(node_count, int)
    or isinstance(node_count, bool)
    or node_count <= 0
    or not isinstance(edge_occurrence_count, int)
    or isinstance(edge_occurrence_count, bool)
    or edge_occurrence_count < 0
    or not isinstance(weighted_edge_count, (int, float))
    or isinstance(weighted_edge_count, bool)
    or weighted_edge_count < 0
):
    raise SystemExit("native graph summary was unavailable")
if (
    not isinstance(refresh_generation, int)
    or isinstance(refresh_generation, bool)
    or refresh_generation < 0
    or not isinstance(graph_generation, int)
    or isinstance(graph_generation, bool)
    or graph_generation < 0
):
    raise SystemExit("semantic refresh and summary omitted exact source-index generations")

require_budget()
pointer_path = Path(published_pointer).resolve()
if pointer_path.name != "current.json" or pointer_path.parent.name != "semantic-generations":
    raise SystemExit("published workspace pointer is not canonical")
pointer_before = pointer_path.read_bytes()
manifest = json.loads(pointer_before)
manifest_generation = manifest.get("sourceIndexGeneration")
if (
    not isinstance(manifest_generation, int)
    or isinstance(manifest_generation, bool)
    or manifest_generation < 0
    or len({refresh_generation, graph_generation, manifest_generation}) != 1
):
    raise SystemExit("semantic evidence does not belong to one source-index generation")
database_file = manifest.get("databaseFile")
database_relative = PurePosixPath(database_file) if isinstance(database_file, str) else None
if (
    database_relative is None
    or database_relative.is_absolute()
    or len(database_relative.parts) != 2
    or database_relative.parts[1] != "source-index.db"
    or any(part in {"", ".", ".."} for part in database_relative.parts)
):
    raise SystemExit("published workspace database path is not canonical")
generations_directory = (pointer_path.parent / "generations").resolve()
database_path = (generations_directory / Path(*database_relative.parts)).resolve()
try:
    database_path.relative_to(generations_directory)
except ValueError as error:
    raise SystemExit("published workspace database escapes its generation directory") from error
if not database_path.is_file():
    raise SystemExit("published workspace database is unavailable")

database_uri = "file:" + urllib.parse.quote(str(database_path)) + "?mode=ro&immutable=1"
connection = sqlite3.connect(database_uri, uri=True)
connection.execute("PRAGMA query_only = ON")
connection.set_progress_handler(lambda: time.monotonic() >= deadline_monotonic, 1000)

def require_columns(schema, table, required):
    columns = {
        row[1]
        for row in connection.execute(f"PRAGMA {schema}.table_info({table})")
    }
    missing = sorted(set(required) - columns)
    if missing:
        raise SystemExit(f"semantic identity schema {schema}.{table} is missing {missing}")

try:
    connection.execute("BEGIN")
    schema_row = connection.execute(
        "SELECT version, generation FROM schema_version LIMIT 1"
    ).fetchone()
    expected_schema = manifest.get("sourceIndexSchemaVersion")
    expected_generation = manifest.get("sourceIndexGeneration")
    if (
        not isinstance(expected_schema, int)
        or isinstance(expected_schema, bool)
        or not isinstance(expected_generation, int)
        or isinstance(expected_generation, bool)
        or schema_row != (expected_schema, expected_generation)
    ):
        raise SystemExit("published workspace manifest disagrees with the database identity")
    if schema_row[1] != refresh_generation:
        raise SystemExit("semantic evidence does not belong to one source-index generation")
    require_columns("main", "semantic_files", {"id", "path"})
    require_columns("main", "semantic_symbols", {"id", "stable_key", "file_id", "kind", "name"})
    require_columns(
        "main",
        "semantic_edge_occurrences",
        {
            "source_id", "target_id", "source_file_id", "kind", "context",
            "resolved_target_id", "start_offset", "end_offset", "line",
        },
    )

    overlay_file = manifest.get("repositoryOverlayFile")
    if overlay_file is None:
        node_rows = connection.execute(
            "SELECT symbols.stable_key, symbols.kind, symbols.name, files.path "
            "FROM semantic_symbols symbols "
            "JOIN semantic_files files ON files.id = symbols.file_id "
            "ORDER BY symbols.stable_key, symbols.kind, symbols.name, files.path"
        ).fetchall()
        edge_rows = connection.execute(
            "SELECT source.stable_key, target.stable_key, source_file.path, "
            "       resolved_target.stable_key, edges.start_offset, edges.end_offset, "
            "       edges.line, edges.kind, edges.context "
            "FROM semantic_edge_occurrences edges "
            "JOIN semantic_symbols source ON source.id = edges.source_id "
            "JOIN semantic_symbols target ON target.id = edges.target_id "
            "JOIN semantic_files source_file ON source_file.id = edges.source_file_id "
            "LEFT JOIN semantic_symbols resolved_target "
            "       ON resolved_target.id = edges.resolved_target_id "
            "ORDER BY source.stable_key, target.stable_key, source_file.path, "
            "         edges.kind, edges.context, resolved_target.stable_key IS NOT NULL, "
            "         resolved_target.stable_key, edges.start_offset, edges.end_offset, edges.line"
        ).fetchall()
    else:
        if overlay_file != "repository-overlay.json":
            raise SystemExit("published repository overlay path is not canonical")
        generation_directory = database_path.parent
        descriptor_path = generation_directory / overlay_file
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        base_database = descriptor.get("baseDatabase")
        expected_base = (generation_directory / "repository-base.db").resolve()
        if not isinstance(base_database, str) or Path(base_database).resolve() != expected_base:
            raise SystemExit("published repository base path is not canonical")
        if not expected_base.is_file():
            raise SystemExit("published repository base database is unavailable")
        base_uri = "file:" + urllib.parse.quote(str(expected_base)) + "?mode=ro&immutable=1"
        connection.execute("ATTACH DATABASE ? AS repository_base", (base_uri,))
        require_columns("main", "repository_overlay_tombstones", {"path"})
        require_columns("main", "semantic_files", {"id", "path", "refresh_status"})
        require_columns(
            "repository_base",
            "semantic_files",
            {"id", "path", "refresh_status"},
        )
        require_columns(
            "repository_base",
            "semantic_symbols",
            {"id", "stable_key", "file_id", "kind", "name"},
        )
        require_columns(
            "repository_base",
            "semantic_edge_occurrences",
            {
                "source_id", "target_id", "source_file_id", "kind", "context",
                "resolved_target_id", "start_offset", "end_offset", "line",
            },
        )
        overlay_cte = """
WITH effective_symbol_rows AS (
    SELECT symbols.stable_key, symbols.kind, symbols.name, files.path AS file_path
    FROM semantic_symbols symbols
    JOIN semantic_files files ON files.id = symbols.file_id
    WHERE NOT EXISTS (
        SELECT 1 FROM repository_overlay_tombstones tombstone
        WHERE tombstone.path = files.path
    )
    UNION ALL
    SELECT symbols.stable_key, symbols.kind, symbols.name, files.path AS file_path
    FROM repository_base.semantic_symbols symbols
    JOIN repository_base.semantic_files files ON files.id = symbols.file_id
    WHERE NOT EXISTS (
        SELECT 1 FROM repository_overlay_tombstones tombstone
        WHERE tombstone.path = files.path
    )
      AND NOT EXISTS (
        SELECT 1 FROM semantic_files overlay
        WHERE overlay.path = files.path AND overlay.refresh_status != 'CACHED'
    )
      AND NOT EXISTS (
        SELECT 1 FROM semantic_symbols overlay
        WHERE overlay.stable_key = symbols.stable_key
    )
), effective_symbols AS (
    SELECT stable_key, file_path FROM effective_symbol_rows
), raw_edge_occurrences AS (
    SELECT source.stable_key AS source_key, target.stable_key AS target_key,
           source_file.path AS source_file_path,
           resolved_target.stable_key AS resolved_target_key,
           edges.start_offset, edges.end_offset, edges.line,
           edges.kind, edges.context
    FROM semantic_edge_occurrences edges
    JOIN semantic_symbols source ON source.id = edges.source_id
    JOIN semantic_symbols target ON target.id = edges.target_id
    JOIN semantic_files source_file ON source_file.id = edges.source_file_id
    LEFT JOIN semantic_symbols resolved_target ON resolved_target.id = edges.resolved_target_id
    WHERE NOT EXISTS (
        SELECT 1 FROM repository_overlay_tombstones tombstone
        WHERE tombstone.path = source_file.path
    )
    UNION ALL
    SELECT source.stable_key AS source_key, target.stable_key AS target_key,
           source_file.path AS source_file_path,
           resolved_target.stable_key AS resolved_target_key,
           edges.start_offset, edges.end_offset, edges.line,
           edges.kind, edges.context
    FROM repository_base.semantic_edge_occurrences edges
    JOIN repository_base.semantic_symbols source ON source.id = edges.source_id
    JOIN repository_base.semantic_symbols target ON target.id = edges.target_id
    JOIN repository_base.semantic_files source_file ON source_file.id = edges.source_file_id
    LEFT JOIN repository_base.semantic_symbols resolved_target
           ON resolved_target.id = edges.resolved_target_id
    WHERE NOT EXISTS (
        SELECT 1 FROM repository_overlay_tombstones tombstone
        WHERE tombstone.path = source_file.path
    )
      AND NOT EXISTS (
        SELECT 1 FROM semantic_files overlay
        WHERE overlay.path = source_file.path AND overlay.refresh_status != 'CACHED'
    )
)
"""
        node_rows = connection.execute(
            overlay_cte
            + "SELECT stable_key, kind, name, file_path FROM effective_symbol_rows "
              "ORDER BY stable_key, kind, name, file_path"
        ).fetchall()
        edge_rows = connection.execute(
            overlay_cte
            + "SELECT edges.source_key, edges.target_key, edges.source_file_path, "
              "       edges.resolved_target_key, edges.start_offset, edges.end_offset, "
              "       edges.line, edges.kind, edges.context "
              "FROM raw_edge_occurrences edges "
              "JOIN effective_symbols source ON source.stable_key = edges.source_key "
              "JOIN effective_symbols target ON target.stable_key = edges.target_key "
              "ORDER BY edges.source_key, edges.target_key, edges.source_file_path, "
              "         edges.kind, edges.context, edges.resolved_target_key IS NOT NULL, "
              "         edges.resolved_target_key, edges.start_offset, edges.end_offset, edges.line"
        ).fetchall()
    connection.execute("COMMIT")
finally:
    connection.close()

require_budget()
if pointer_path.read_bytes() != pointer_before:
    raise SystemExit("published workspace pointer moved during semantic identity capture")

node_identities = [
    {
        "stableKey": stable_key,
        "kind": kind,
        "name": name,
        "path": root_normalized_path(file_path),
    }
    for stable_key, kind, name, file_path in node_rows
]
edge_identities = [
    {
        "sourceKey": source,
        "targetKey": target,
        "sourceFilePath": root_normalized_path(source_file_path),
        "resolvedTargetKey": resolved_target,
        "startOffset": start_offset,
        "endOffset": end_offset,
        "line": line,
        "kind": kind,
        "context": context,
    }
    for (
        source,
        target,
        source_file_path,
        resolved_target,
        start_offset,
        end_offset,
        line,
        kind,
        context,
    ) in edge_rows
]
if len(node_identities) != node_count:
    raise SystemExit("graph node identity set does not match summary cardinality")
if len(edge_identities) != edge_occurrence_count:
    raise SystemExit("graph edge identity set does not match summary cardinality")
if not math.isclose(float(len(edge_identities)), float(weighted_edge_count), rel_tol=0.0, abs_tol=1e-9):
    raise SystemExit("graph edge identity weights do not match summary evidence")

correctness = {
    "semanticIdentityAlgorithm": "sha256-canonical-json-v2",
    "sourceIndexGeneration": refresh_generation,
    "workspaceExactTotalCount": workspace_total,
    "workspaceFileIdentities": workspace_identities,
    "workspaceFileIdentitySha256": canonical_sha256(workspace_identities),
    "refreshSymbolCount": symbol_count,
    "refreshedPaths": sorted(refreshed_paths),
    "removedPaths": removed_paths,
    "graphNodeCount": node_count,
    "graphEdgeOccurrenceCount": edge_occurrence_count,
    "graphWeightedEdgeCount": weighted_edge_count,
    "graphNodeIdentitySha256": canonical_sha256(node_identities),
    "graphEdgeIdentitySha256": canonical_sha256(edge_identities),
}
correctness_path = Path(correctness_output)
correctness_path.parent.mkdir(parents=True, exist_ok=True)
correctness_path.write_text(json.dumps(correctness, indent=2) + "\n", encoding="utf-8")
PY
}

benchmark_owned_process_ids() {
  local marker="$1" deadline result_file
  deadline="$(role_command_deadline)"
  result_file="${benchmark_run_dir:-${TMPDIR:-/tmp}}/owned-processes-${benchmark_sample_sequence:-0}.json"
  "$BENCHMARK_PYTHON_BIN" "$BENCHMARK_COMMAND_SUPERVISOR" list-owned \
    --deadline-monotonic-ms "$deadline" \
    --marker "$marker" \
    --event-log "$benchmark_command_events_file" \
    --result-json "$result_file" \
    --print-pids
}

summarize_runtime_retry_transitions() {
  local samples_file="$1" output="$2"
  local deadline="${benchmark_finalization_deadline_monotonic_ms:-$(role_command_deadline)}"
  run_supervised_command "$deadline" finalization-retry-summary \
    "$BENCHMARK_PYTHON_BIN" - "$samples_file" "$output" <<'PY'
import json
import sys
from pathlib import Path

samples_path, output_path = map(Path, sys.argv[1:])

retry_time_fields = {
    "retryAt",
    "retryAtEpochMillis",
    "retryTime",
    "retryTimeEpochMillis",
    "nextRetryAt",
    "nextRetryAtEpochMillis",
}
retry_detail_fields = retry_time_fields | {
    "attempt",
    "retryAttempt",
    "phase",
    "lastError",
}

def normalized_retry(value):
    return {
        key: value[key]
        for key in sorted(retry_detail_fields)
        if key in value
    }

def retry_values(value):
    if isinstance(value, dict):
        retry = value.get("retry")
        if isinstance(retry, dict):
            yield normalized_retry(retry) or retry
        if retry_time_fields & value.keys() and (
            "lastError" in value
            or "attempt" in value
            or "retryAttempt" in value
        ):
            yield normalized_retry(value)
        for nested in value.values():
            yield from retry_values(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from retry_values(nested)

seen = set()
transitions = []
if samples_path.exists():
    for line in samples_path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        try:
            sample = json.loads(line)
        except json.JSONDecodeError:
            continue
        for retry in retry_values(sample.get("runtimeStatus")):
            identity = json.dumps(retry, sort_keys=True, separators=(",", ":"))
            if identity in seen:
                continue
            seen.add(identity)
            transitions.append({
                "observedAtEpochMillis": sample.get("observedAtEpochMillis"),
                "retry": retry,
            })

Path(output_path).write_text(json.dumps({
    "runtimeTransitionCount": len(transitions),
    "runtimeTransitions": transitions,
}, indent=2) + "\n", encoding="utf-8")
PY
}

compare_benchmark_evidence() {
  local stable_evidence="$1" candidate_evidence="$2" comparison_output="$3"
  run_supervised_command "$(role_command_deadline)" comparison \
    "$BENCHMARK_PYTHON_BIN" - \
    "$stable_evidence" \
    "$candidate_evidence" \
    "$comparison_output" \
    "$COLD_INDEX_LIMIT_MILLIS" \
    "$PHASE_REGRESSION_PERCENT" \
    "$PHASE_REGRESSION_MILLIS" \
    "$DISK_REGRESSION_PERCENT" \
    "$DISK_REGRESSION_BYTES" \
    "$REQUIRED_PHASES" \
    "$COMPARABLE_PHASES" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path, PurePosixPath

(
    stable_path,
    candidate_path,
    output_path,
    cold_limit,
    phase_percent,
    phase_millis,
    disk_percent,
    disk_bytes,
    required_phases,
    comparable_phases,
) = sys.argv[1:]
cold_limit = int(cold_limit)
phase_percent = int(phase_percent)
phase_millis = int(phase_millis)
disk_percent = int(disk_percent)
disk_bytes = int(disk_bytes)
required_phases = required_phases.split()
comparable_phases = comparable_phases.split()

storage_fields = {
    "initialDatabaseBytes", "finalDatabaseBytes", "peakDatabaseBytes", "databaseGrowthBytes",
    "initialWalBytes", "finalWalBytes", "peakWalBytes", "walGrowthBytes",
    "initialKastHomeBytes", "finalKastHomeBytes", "peakKastHomeBytes",
    "initialKastCacheBytes", "finalKastCacheBytes", "peakKastCacheBytes",
    "initialGradleCacheBytes", "finalGradleCacheBytes", "peakGradleCacheBytes",
    "initialUserHomeBytes", "finalUserHomeBytes", "peakUserHomeBytes",
    "initialWorkspaceBytes", "finalWorkspaceBytes", "peakWorkspaceBytes",
    "initialOwnedBytes", "finalOwnedBytes", "peakOwnedBytes", "ownedGrowthBytes",
}
resource_fields = {
    "peakRssBytes", "peakVirtualBytes", "peakCpuPercent", "peakProcessCount",
}
retry_fields = {
    "workspaceIndexPoll", "semanticNotReadyPoll", "graphGenerationConflict",
    "runtimeStatusFailures", "runtimeTransitionCount", "runtimeTransitions",
}
correctness_integer_fields = {
    "sourceIndexGeneration", "workspaceExactTotalCount", "refreshSymbolCount", "graphNodeCount",
    "graphEdgeOccurrenceCount",
}
correctness_numeric_fields = correctness_integer_fields | {"graphWeightedEdgeCount"}
correctness_comparison_numeric_fields = (
    correctness_numeric_fields - {"sourceIndexGeneration"}
)
correctness_hash_fields = {
    "workspaceFileIdentitySha256", "graphNodeIdentitySha256", "graphEdgeIdentitySha256",
}
correctness_identity_fields = correctness_hash_fields | {
    "semanticIdentityAlgorithm", "workspaceFileIdentities", "refreshedPaths", "removedPaths",
}
bundle_name_pattern = re.compile(
    r"^kast-linux-x64-(v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\.tar\.gz$"
)

def normalized_version(value):
    if not isinstance(value, str) or not value:
        return None
    return value[1:] if value.startswith("v") else value

def runtime_observation(payload):
    states = set()
    versions = set()
    if not isinstance(payload, dict):
        return states, versions
    result = payload.get("result", payload)
    if not isinstance(result, dict):
        return states, versions

    runtime = result.get("runtime")
    if isinstance(runtime, dict):
        for candidate in (runtime, runtime.get("status"), runtime.get("runtimeStatus")):
            if not isinstance(candidate, dict):
                continue
            if isinstance(candidate.get("state"), str):
                states.add(candidate["state"])
            if isinstance(candidate.get("backendVersion"), str):
                versions.add(candidate["backendVersion"])

    selected = result.get("selected")
    if isinstance(selected, dict):
        for candidate in (
            selected.get("runtimeStatus"),
            selected.get("descriptor"),
            selected.get("capabilities"),
        ):
            if not isinstance(candidate, dict):
                continue
            if isinstance(candidate.get("state"), str):
                states.add(candidate["state"])
            if isinstance(candidate.get("backendVersion"), str):
                versions.add(candidate["backendVersion"])
    return states, versions

def load(path, role):
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return None, [f"unreadable JSON: {error}"]
    if not isinstance(payload, dict):
        return None, ["evidence root must be an object"]
    errors = []
    if payload.get("schemaVersion") != 1:
        errors.append("schemaVersion must equal 1")
    if payload.get("role") != role:
        errors.append(f"role must equal {role}")
    bundle = payload.get("bundle")
    if not isinstance(bundle, dict):
        errors.append("bundle must be an object")
        expected_bundle_version = None
    else:
        file_name = bundle.get("fileName")
        match = bundle_name_pattern.fullmatch(file_name) if isinstance(file_name, str) else None
        expected_bundle_version = match.group(1) if match else None
        if expected_bundle_version is None:
            errors.append("bundle.fileName must be an exact versioned Linux x64 setup bundle")
        digest = bundle.get("sha256")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            errors.append("bundle.sha256 must be 64 lowercase hexadecimal characters")
        if (
            not isinstance(bundle.get("version"), str)
            or not bundle.get("version")
            or bundle.get("version") == "unknown"
        ):
            errors.append("bundle.version must be non-empty")
        elif (
            expected_bundle_version is not None
            and normalized_version(bundle["version"]) != normalized_version(expected_bundle_version)
        ):
            errors.append("bundle.version must match the version inferred from bundle.fileName")
        if bundle.get("expectedVersion") != expected_bundle_version:
            errors.append("bundle.expectedVersion must match bundle.fileName")
        runtime_versions = bundle.get("runtimeBackendVersions")
        if (
            not isinstance(runtime_versions, list)
            or not runtime_versions
            or any(not isinstance(value, str) or not value for value in runtime_versions)
        ):
            errors.append("bundle.runtimeBackendVersions must be a non-empty string array")
        elif (
            expected_bundle_version is not None
            and any(
                normalized_version(value) != normalized_version(expected_bundle_version)
                for value in runtime_versions
            )
        ):
            errors.append("runtime backend versions must match the inferred bundle version")
    if not isinstance(payload.get("correctness"), bool):
        errors.append("correctness must be boolean")
    isolation = payload.get("isolation")
    if not isinstance(isolation, dict):
        errors.append("isolation must be an object")
    elif (
        isolation.get("processTeardownProven") is not True
        or isolation.get("worktreeRemoved") is not True
    ):
        errors.append("isolation proof fields must both be true")

    correctness_evidence = payload.get("correctnessEvidence")
    if not isinstance(correctness_evidence, dict):
        errors.append("correctnessEvidence must be an object")
    else:
        missing = sorted(
            (correctness_numeric_fields | correctness_identity_fields)
            - correctness_evidence.keys()
        )
        if missing:
            errors.append(f"correctnessEvidence is missing {missing}")
        for field in correctness_integer_fields & correctness_evidence.keys():
            value = correctness_evidence[field]
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                errors.append(f"correctnessEvidence.{field} must be a non-negative integer")
        weighted_edges = correctness_evidence.get("graphWeightedEdgeCount")
        if (
            not isinstance(weighted_edges, (int, float))
            or isinstance(weighted_edges, bool)
            or weighted_edges < 0
        ):
            errors.append("correctnessEvidence.graphWeightedEdgeCount must be non-negative")
        if correctness_evidence.get("semanticIdentityAlgorithm") != "sha256-canonical-json-v2":
            errors.append("correctnessEvidence.semanticIdentityAlgorithm is unsupported")
        for field in correctness_hash_fields:
            value = correctness_evidence.get(field)
            if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
                errors.append(f"correctnessEvidence.{field} must be a lowercase SHA-256")
        for field in ("workspaceFileIdentities", "refreshedPaths", "removedPaths"):
            paths = correctness_evidence.get(field)
            if (
                not isinstance(paths, list)
                or any(not isinstance(value, str) or not value for value in paths)
                or (isinstance(paths, list) and len(paths) != len(set(paths)))
                or (isinstance(paths, list) and paths != sorted(paths))
                or (
                    isinstance(paths, list)
                    and any(
                        "\\" in value
                        or PurePosixPath(value).is_absolute()
                        or PurePosixPath(value).as_posix() != value
                        or any(part in {"", ".", ".."} for part in PurePosixPath(value).parts)
                        for value in paths
                        if isinstance(value, str)
                    )
                )
            ):
                errors.append(
                    f"correctnessEvidence.{field} must be a sorted unique root-normalized path array"
                )
        workspace_identities = correctness_evidence.get("workspaceFileIdentities")
        if isinstance(workspace_identities, list):
            expected_total = correctness_evidence.get("workspaceExactTotalCount")
            if len(workspace_identities) != expected_total:
                errors.append("workspace identity set must equal exact workspace cardinality")
            expected_hash = hashlib.sha256(json.dumps(
                workspace_identities,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")).hexdigest()
            if correctness_evidence.get("workspaceFileIdentitySha256") != expected_hash:
                errors.append("workspace identity hash disagrees with its canonical path set")
        workspace = payload.get("workspace")
        graph_file = workspace.get("graphFile") if isinstance(workspace, dict) else None
        if correctness_evidence.get("refreshedPaths") != [graph_file]:
            errors.append("correctnessEvidence.refreshedPaths must contain the exact graph probe")
    phases = payload.get("phases")
    if not isinstance(phases, list):
        errors.append("phases must be an array")
    else:
        names = [phase.get("name") for phase in phases if isinstance(phase, dict)]
        if names != required_phases:
            errors.append(f"phases must be exactly {required_phases}")
        for phase in phases:
            if not isinstance(phase, dict):
                errors.append("each phase must be an object")
                continue
            for field in (
                "startedAtEpochMillis", "finishedAtEpochMillis",
                "startedAtMonotonicMillis", "finishedAtMonotonicMillis", "durationMillis",
            ):
                value = phase.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                    errors.append(f"phase {phase.get('name')} has invalid {field}")
            if all(
                isinstance(phase.get(field), int) and not isinstance(phase.get(field), bool)
                for field in (
                    "startedAtEpochMillis", "finishedAtEpochMillis",
                    "startedAtMonotonicMillis", "finishedAtMonotonicMillis", "durationMillis",
                )
            ):
                if phase["finishedAtEpochMillis"] < phase["startedAtEpochMillis"]:
                    errors.append(f"phase {phase.get('name')} has reversed epoch timestamps")
                if phase["finishedAtMonotonicMillis"] < phase["startedAtMonotonicMillis"]:
                    errors.append(f"phase {phase.get('name')} has reversed monotonic timestamps")
                if (
                    phase["durationMillis"]
                    != phase["finishedAtMonotonicMillis"] - phase["startedAtMonotonicMillis"]
                ):
                    errors.append(f"phase {phase.get('name')} duration disagrees with monotonic timestamps")
    storage = payload.get("storage")
    if not isinstance(storage, dict):
        errors.append("storage must be an object")
    else:
        missing = sorted(storage_fields - storage.keys())
        if missing:
            errors.append(f"storage is missing {missing}")
        for field in storage_fields & storage.keys():
            value = storage[field]
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                errors.append(f"storage.{field} must be a non-negative integer")
    resources = payload.get("resources")
    if not isinstance(resources, dict):
        errors.append("resources must be an object")
    else:
        missing = sorted(resource_fields - resources.keys())
        if missing:
            errors.append(f"resources is missing {missing}")
        for field in resource_fields & resources.keys():
            value = resources[field]
            if not isinstance(value, (int, float)) or isinstance(value, bool) or value < 0:
                errors.append(f"resources.{field} must be non-negative")
    retries = payload.get("retries")
    if not isinstance(retries, dict):
        errors.append("retries must be an object")
    else:
        missing = sorted(retry_fields - retries.keys())
        if missing:
            errors.append(f"retries is missing {missing}")
        for field in retry_fields - {"runtimeTransitions"}:
            value = retries.get(field)
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                errors.append(f"retries.{field} must be a non-negative integer")
        transitions = retries.get("runtimeTransitions")
        if not isinstance(transitions, list):
            errors.append("retries.runtimeTransitions must be an array")
        elif retries.get("runtimeTransitionCount") != len(transitions):
            errors.append("runtimeTransitionCount must equal the transition count")
    samples = payload.get("progressSamples")
    if not isinstance(samples, list) or not samples:
        errors.append("progressSamples must be a non-empty array")
    else:
        sample_storage_fields = {
            "databaseBytes", "walBytes", "kastHomeBytes", "kastCacheBytes",
            "gradleCacheBytes", "userHomeBytes", "workspaceBytes", "ownedBytes",
        }
        sample_resource_fields = {
            "rssBytes", "virtualBytes", "cpuPercent", "processCount", "processIds",
        }
        observed_runtime_states = set()
        observed_runtime_versions = set()
        previous_observed_at = None
        for index, sample in enumerate(samples):
            if not isinstance(sample, dict):
                errors.append(f"progressSamples[{index}] must be an object")
                continue
            observed_at = sample.get("observedAtEpochMillis")
            if (
                not isinstance(observed_at, int)
                or isinstance(observed_at, bool)
                or observed_at < 0
            ):
                errors.append(f"progressSamples[{index}] has invalid observation time")
            elif previous_observed_at is not None and observed_at < previous_observed_at:
                errors.append(f"progressSamples[{index}] moved backward in time")
            else:
                previous_observed_at = observed_at
            if not isinstance(sample.get("phase"), str) or not sample.get("phase"):
                errors.append(f"progressSamples[{index}] has invalid phase")
            if (
                not isinstance(sample.get("statusExitCode"), int)
                or isinstance(sample.get("statusExitCode"), bool)
                or sample.get("statusExitCode") < 0
            ):
                errors.append(f"progressSamples[{index}] has invalid status exit code")
            if not isinstance(sample.get("storageFresh"), bool):
                errors.append(f"progressSamples[{index}] has invalid storageFresh")
            storage_observed_at = sample.get("storageObservedAtEpochMillis")
            if (
                not isinstance(storage_observed_at, int)
                or isinstance(storage_observed_at, bool)
                or storage_observed_at < 0
                or (
                    isinstance(observed_at, int)
                    and not isinstance(observed_at, bool)
                    and storage_observed_at > observed_at
                )
            ):
                errors.append(f"progressSamples[{index}] has invalid storage observation time")
            sample_storage = sample.get("storage")
            if not isinstance(sample_storage, dict):
                errors.append(f"progressSamples[{index}].storage must be an object")
            else:
                missing = sorted(sample_storage_fields - sample_storage.keys())
                if missing:
                    errors.append(f"progressSamples[{index}].storage is missing {missing}")
                for field in sample_storage_fields & sample_storage.keys():
                    value = sample_storage[field]
                    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                        errors.append(
                            f"progressSamples[{index}].storage.{field} must be a non-negative integer"
                        )
            sample_resources = sample.get("resources")
            if not isinstance(sample_resources, dict):
                errors.append(f"progressSamples[{index}].resources must be an object")
            else:
                missing = sorted(sample_resource_fields - sample_resources.keys())
                if missing:
                    errors.append(f"progressSamples[{index}].resources is missing {missing}")
                for field in ("rssBytes", "virtualBytes", "processCount"):
                    value = sample_resources.get(field)
                    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                        errors.append(
                            f"progressSamples[{index}].resources.{field} must be a non-negative integer"
                        )
                cpu_percent = sample_resources.get("cpuPercent")
                if (
                    not isinstance(cpu_percent, (int, float))
                    or isinstance(cpu_percent, bool)
                    or cpu_percent < 0
                ):
                    errors.append(
                        f"progressSamples[{index}].resources.cpuPercent must be non-negative"
                    )
                process_ids = sample_resources.get("processIds")
                if not isinstance(process_ids, list):
                    errors.append(f"progressSamples[{index}].resources.processIds must be an array")
                elif (
                    any(
                        not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0
                        for pid in process_ids
                    )
                    or len(process_ids) != len(set(process_ids))
                ):
                    errors.append(
                        f"progressSamples[{index}].resources.processIds must contain unique positive integers"
                    )
            states, versions = runtime_observation(sample.get("runtimeStatus"))
            observed_runtime_states.update(states)
            observed_runtime_versions.update(versions)
        if not observed_runtime_states & {"STARTING", "INDEXING", "READY"}:
            errors.append("progressSamples must contain admitted runtime progress evidence")
        recorded_versions = bundle.get("runtimeBackendVersions") if isinstance(bundle, dict) else None
        if isinstance(recorded_versions, list) and sorted(set(recorded_versions)) != sorted(observed_runtime_versions):
            errors.append("bundle.runtimeBackendVersions must equal observed runtime backend versions")
    return payload, errors

stable, stable_errors = load(stable_path, "stable")
candidate, candidate_errors = load(candidate_path, "candidate")
failures = []
if stable_errors:
    failures.append({"code": "EVIDENCE_INVALID", "role": "stable", "details": stable_errors})
if candidate_errors:
    failures.append({"code": "EVIDENCE_INVALID", "role": "candidate", "details": candidate_errors})

phase_regressions = []
correctness_regressions = []
correctness_identity_comparisons = []
disk_regression = None
if not stable_errors and not candidate_errors:
    if not stable["correctness"]:
        failures.append({"code": "BASELINE_CORRECTNESS_LOST"})
    if not candidate["correctness"]:
        failures.append({"code": "CORRECTNESS_LOST"})

    if stable.get("workspace") != candidate.get("workspace"):
        failures.append({"code": "CORRECTNESS_CONTEXT_MISMATCH"})
    stable_correctness = stable["correctnessEvidence"]
    candidate_correctness = candidate["correctnessEvidence"]
    for field in sorted(correctness_comparison_numeric_fields):
        baseline = stable_correctness[field]
        observed = candidate_correctness[field]
        failed = observed != baseline
        result = {
            "field": field,
            "stableValue": baseline,
            "candidateValue": observed,
            "failed": failed,
        }
        correctness_regressions.append(result)
        if failed:
            failures.append({"code": "CORRECTNESS_REGRESSION", **result})
    for field in sorted(correctness_identity_fields):
        baseline = stable_correctness[field]
        observed = candidate_correctness[field]
        failed = observed != baseline
        result = {
            "field": field,
            "stableValue": baseline,
            "candidateValue": observed,
            "failed": failed,
        }
        correctness_identity_comparisons.append(result)
        if failed:
            failures.append({"code": "CORRECTNESS_IDENTITY_MISMATCH", **result})

    stable_phases = {phase["name"]: phase["durationMillis"] for phase in stable["phases"]}
    candidate_phases = {phase["name"]: phase["durationMillis"] for phase in candidate["phases"]}
    for name in comparable_phases:
        baseline = stable_phases[name]
        observed = candidate_phases[name]
        delta = observed - baseline
        failed = baseline == 0 and observed > phase_millis
        failed = failed or (
            baseline > 0
            and observed * 100 > baseline * (100 + phase_percent)
            and delta > phase_millis
        )
        result = {
            "phase": name,
            "stableMillis": baseline,
            "candidateMillis": observed,
            "deltaMillis": delta,
            "deltaPercent": None if baseline == 0 else delta * 100.0 / baseline,
            "failed": failed,
        }
        phase_regressions.append(result)
        if failed:
            failures.append({"code": "PHASE_REGRESSION", **result})

    cold_index = candidate_phases["coldIndex"]
    if cold_index > cold_limit:
        failures.append({
            "code": "COLD_INDEX_LIMIT",
            "candidateMillis": cold_index,
            "limitMillis": cold_limit,
        })

    stable_disk = stable["storage"]["peakOwnedBytes"]
    candidate_disk = candidate["storage"]["peakOwnedBytes"]
    disk_delta = candidate_disk - stable_disk
    disk_failed = stable_disk == 0 and candidate_disk > disk_bytes
    disk_failed = disk_failed or (
        stable_disk > 0
        and candidate_disk * 100 > stable_disk * (100 + disk_percent)
        and disk_delta > disk_bytes
    )
    disk_regression = {
        "stableBytes": stable_disk,
        "candidateBytes": candidate_disk,
        "deltaBytes": disk_delta,
        "deltaPercent": None if stable_disk == 0 else disk_delta * 100.0 / stable_disk,
        "failed": disk_failed,
    }
    if disk_failed:
        failures.append({"code": "DISK_REGRESSION", **disk_regression})

comparison = {
    "schemaVersion": 1,
    "passed": not failures,
    "policy": {
        "phaseRegressionPercent": phase_percent,
        "phaseRegressionMillis": phase_millis,
        "diskRegressionPercent": disk_percent,
        "diskRegressionBytes": disk_bytes,
        "coldIndexLimitMillis": cold_limit,
    },
    "correctnessRegressions": correctness_regressions,
    "correctnessIdentityComparisons": correctness_identity_comparisons,
    "phaseRegressions": phase_regressions,
    "comparedPhases": comparable_phases,
    "diskRegression": disk_regression,
    "coldIndexLimitMillis": cold_limit,
    "failures": failures,
}
Path(output_path).parent.mkdir(parents=True, exist_ok=True)
Path(output_path).write_text(json.dumps(comparison, indent=2) + "\n", encoding="utf-8")
raise SystemExit(0 if comparison["passed"] else 1)
PY
}

write_typed_emergency_role_evidence() {
  local output="$1" role="$2" bundle="$3" bundle_digest="$4"
  local original_result="$5" finalization_result="$6" command_events="$7" deadline="$8"
  run_supervised_command "$deadline" diagnostic-finalization "$BENCHMARK_PYTHON_BIN" - \
    "$output" "$role" "$bundle" "$bundle_digest" \
    "$original_result" "$finalization_result" "$command_events" <<'PY'
import json
import sys
from pathlib import Path

(
    output,
    role,
    bundle,
    bundle_digest,
    original_result,
    finalization_result,
    command_events,
) = sys.argv[1:]
events = []
try:
    for line in Path(command_events).read_text(encoding="utf-8").splitlines():
        if line:
            events.append(json.loads(line))
except (OSError, json.JSONDecodeError):
    events = []
payload = {
    "schemaVersion": 1,
    "role": role,
    "bundle": {
        "version": "unknown",
        "sha256": bundle_digest,
        "fileName": Path(bundle).name,
    },
    "correctness": False,
    "correctnessEvidence": None,
    "diagnostic": {
        "outcome": "FINALIZATION_FAILED",
        "roleExitCode": int(original_result),
        "finalizationExitCode": int(finalization_result),
        "supervisedCommands": events,
    },
    "isolation": {"processTeardownProven": False, "worktreeRemoved": False},
    "phases": [],
    "progressSamples": [],
    "storage": {},
    "retries": {},
    "resources": {},
}
destination = Path(output)
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

assemble_benchmark_evidence() {
  local stable_evidence="$1" candidate_evidence="$2" comparison="$3" output="$4"
  local stable_name candidate_name stable_tag candidate_tag stable_digest candidate_digest
  run_supervised_command "$(role_command_deadline)" aggregate-repository-evidence \
    "$BENCHMARK_PYTHON_BIN" - \
    "$stable_evidence" "$candidate_evidence" "$comparison" "$output" \
    "$name" "$repository" "$revision" "$graph_file" <<'PY'
import json
import sys
from pathlib import Path

stable_path, candidate_path, comparison_path, output_path = sys.argv[1:5]
comparison = json.loads(Path(comparison_path).read_text(encoding="utf-8"))
payload = {
    "schemaVersion": 1,
    "repository": {
        "name": sys.argv[5],
        "url": sys.argv[6],
        "revision": sys.argv[7],
        "graphFile": sys.argv[8],
    },
    "runs": [
        json.loads(Path(stable_path).read_text(encoding="utf-8")),
        json.loads(Path(candidate_path).read_text(encoding="utf-8")),
    ],
    "comparison": comparison,
    "passed": comparison["passed"],
}
Path(output_path).parent.mkdir(parents=True, exist_ok=True)
Path(output_path).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
  stable_name="${stable_bundle##*/}"
  candidate_name="${candidate_bundle##*/}"
  stable_tag="${stable_name#kast-linux-x64-}"
  stable_tag="${stable_tag%.tar.gz}"
  candidate_tag="${candidate_name#kast-linux-x64-}"
  candidate_tag="${candidate_tag%.tar.gz}"
  stable_digest="$(sha256_file "$stable_bundle")"
  candidate_digest="$(sha256_file "$candidate_bundle")"
  if (( ${comparison_result:-0} == 0 )); then
    run_supervised_command "$(role_command_deadline)" aggregate-repository-validation \
      "$BENCHMARK_EVIDENCE_AGGREGATOR" validate-repository \
        --evidence "$output" \
        --repository-spec "$name|$repository|$revision|$graph_file" \
        --stable-tag "$stable_tag" \
        --stable-digest "$stable_digest" \
        --candidate-tag "$candidate_tag" \
        --candidate-digest "$candidate_digest"
  fi
}

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi

if [[ -n "${KAST_BENCHMARK_TEST_SIGNAL_HELPER:-}" \
    || "${KAST_BENCHMARK_TEST_MODE:-false}" == true \
    || "${KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER:-false}" == true ]]; then
  printf 'error: test signal helper is unavailable in executable mode\n' >&2
  exit 2
fi

name=
repository=
revision=
graph_file=
relationships_enabled=
stable_bundle=
candidate_bundle=
evidence_output=
cache_root=
wait_timeout_ms=$COLD_INDEX_LIMIT_MILLIS
while (($#)); do
  case "$1" in
    --name) name="${2:-}"; shift 2 ;;
    --repository) repository="${2:-}"; shift 2 ;;
    --revision) revision="${2:-}"; shift 2 ;;
    --graph-file) graph_file="${2:-}"; shift 2 ;;
    --relationships-enabled) relationships_enabled="${2:-}"; shift 2 ;;
    --stable-bundle) stable_bundle="${2:-}"; shift 2 ;;
    --candidate-bundle) candidate_bundle="${2:-}"; shift 2 ;;
    --evidence-output) evidence_output="${2:-}"; shift 2 ;;
    --cache-root) cache_root="${2:-}"; shift 2 ;;
    --wait-timeout-ms) wait_timeout_ms="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'error: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$name" =~ ^[a-z0-9-]+$ ]] || { printf 'error: invalid benchmark name\n' >&2; exit 2; }
[[ "$repository" =~ ^https://github\.com/[^/]+/[^/]+\.git$ ]] \
  || { printf 'error: benchmark repository must be a GitHub HTTPS clone URL\n' >&2; exit 2; }
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] \
  || { printf 'error: revision must be a full commit SHA\n' >&2; exit 2; }
valid_graph_file "$graph_file" \
  || { printf 'error: graph file must be a relative Kotlin path\n' >&2; exit 2; }
[[ "$relationships_enabled" == true || "$relationships_enabled" == false ]] \
  || { printf 'error: relationships enabled must be true or false\n' >&2; exit 2; }
[[ -f "$stable_bundle" ]] \
  || { printf 'error: stable bundle not found: %s\n' "$stable_bundle" >&2; exit 2; }
[[ -f "$candidate_bundle" ]] \
  || { printf 'error: candidate bundle not found: %s\n' "$candidate_bundle" >&2; exit 2; }
[[ -n "$evidence_output" ]] || { printf 'error: --evidence-output is required\n' >&2; exit 2; }
[[ -n "$cache_root" ]] || { printf 'error: --cache-root is required\n' >&2; exit 2; }
[[ "$wait_timeout_ms" =~ ^[1-9][0-9]*$ ]] \
  || { printf 'error: wait timeout must be positive\n' >&2; exit 2; }
if ((wait_timeout_ms > COLD_INDEX_LIMIT_MILLIS)); then
  printf 'error: wait timeout cannot exceed the 45-minute cold-index limit\n' >&2
  exit 2
fi
if [[ "${OSTYPE:-}" != linux* ]]; then
  printf 'error: real-repository release benchmarking requires Linux pidfd signaling\n' >&2
  exit 2
fi

benchmark_preparation_events_file="${TMPDIR:-/tmp}/kast-real-repository-preparation-$name-$$.jsonl"
: >"$benchmark_preparation_events_file"
benchmark_command_events_file="$benchmark_preparation_events_file"
scratch="$(run_supervised_command "$(role_command_deadline)" scratch-create \
  mktemp -d "${TMPDIR:-/tmp}/kast-real-repository.XXXXXX")"
stable_evidence="$scratch/stable-run.json"
candidate_evidence="$scratch/candidate-run.json"
comparison_evidence="$scratch/comparison.json"

run_supervised_command "$(role_command_deadline)" repository-cache-create \
  mkdir -p "$cache_root"
cache_root="$(cd "$cache_root" && pwd -P)"
repository_cache="$cache_root/$name"
if [[ ! -d "$repository_cache/.git" ]]; then
  run_supervised_command "$(role_command_deadline)" repository-clone \
    git clone --filter=blob:none --no-checkout "$repository" "$repository_cache"
fi
run_supervised_command "$(role_command_deadline)" repository-origin-configure \
  git -C "$repository_cache" remote set-url origin "$repository"
run_supervised_command "$(role_command_deadline)" repository-fetch \
  git -C "$repository_cache" fetch --force --depth=1 origin "$revision"
run_supervised_command "$(role_command_deadline)" repository-revision-verify \
  env GIT_OPTIONAL_LOCKS=0 \
    git -C "$repository_cache" cat-file -e "${revision}^{commit}"

benchmark_progress_sample_impl() {
  local phase="$1" force_storage="${2:-false}" status_file status_exit=0 owned_pids sample_deadline
  local disk_sample_seconds="${KAST_RELEASE_DISK_SAMPLE_SECONDS:-60}"
  [[ "$force_storage" == true || "$force_storage" == false ]] \
    || { printf 'error: force-storage must be true or false\n' >&2; return 1; }
  [[ "$disk_sample_seconds" =~ ^[1-9][0-9]*$ ]] \
    || { printf 'error: disk sample seconds must be a positive integer\n' >&2; return 1; }
  benchmark_sample_sequence=$((benchmark_sample_sequence + 1))
  status_file="$benchmark_run_dir/status-latest.json"
  benchmark_last_status_file="$status_file"
  if [[ "$phase" == BASELINE ]]; then
    printf '{}\n' >"$status_file"
  elif run_kastctl_with_cold_budget --output json developer runtime status \
      --workspace-root "$workspace" >"$status_file" 2>/dev/null; then
    status_exit=0
  else
    status_exit=$?
    benchmark_status_failures=$((benchmark_status_failures + 1))
  fi
  benchmark_last_status_observed_epoch="$(epoch_millis)"
  benchmark_last_status_observed_monotonic="$(monotonic_millis)"
  owned_pids="$(benchmark_owned_process_ids \
    "$benchmark_run_marker" "$benchmark_kast_home" "$benchmark_cache_dir" \
    "$benchmark_gradle_dir" "$benchmark_user_dir" "$workspace")"
  sample_deadline="$(role_command_deadline)"
  run_supervised_command "$sample_deadline" resource-disk-sample "$BENCHMARK_PYTHON_BIN" - \
    "$benchmark_samples_file" "$benchmark_storage_state_file" \
    "$phase" "$benchmark_kast_home" \
    "$benchmark_cache_dir" "$benchmark_gradle_dir" "$benchmark_user_dir" \
    "$workspace" \
    "$status_file" "$status_exit" "$owned_pids" \
    "$force_storage" "$disk_sample_seconds" <<'PY'
import json
import os
import subprocess
import sys
import time
from pathlib import Path

(
    samples_path,
    storage_state_path,
    phase,
    kast_home,
    kast_cache,
    gradle_cache,
    user_home,
    workspace,
    status_path,
    status_exit,
    owned_pids,
    force_storage,
    disk_sample_seconds,
) = sys.argv[1:]

def tree_size(root):
    total = 0
    root_path = Path(root)
    if not root_path.exists():
        return 0
    for directory, _, files in os.walk(root_path, followlinks=False):
        for name in files:
            try:
                total += (Path(directory) / name).stat().st_size
            except (FileNotFoundError, PermissionError, OSError):
                pass
    return total

def named_file_size(root, name):
    total = 0
    try:
        paths = Path(root).rglob(name)
        for path in paths:
            try:
                if path.is_file():
                    total += path.stat().st_size
            except (FileNotFoundError, PermissionError, OSError):
                pass
    except (FileNotFoundError, PermissionError, OSError):
        pass
    return total

samples_file = Path(samples_path)
storage_state_file = Path(storage_state_path)
storage_state = None
if storage_state_file.exists():
    try:
        storage_state = json.loads(storage_state_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        storage_state = None
observed_at = time.time_ns() // 1_000_000
last_storage_at = 0 if storage_state is None else storage_state.get("observedAtEpochMillis", 0)
storage_fresh = (
    force_storage == "true"
    or storage_state is None
    or observed_at - last_storage_at >= int(disk_sample_seconds) * 1000
)
if storage_fresh:
    kast_home_bytes = tree_size(kast_home)
    kast_cache_bytes = tree_size(kast_cache)
    gradle_cache_bytes = tree_size(gradle_cache)
    user_home_bytes = tree_size(user_home)
    workspace_bytes = tree_size(workspace)
    initial_workspace_bytes = (
        workspace_bytes
        if storage_state is None
        else storage_state.get("initialWorkspaceBytes", workspace_bytes)
    )
    storage = {
        "databaseBytes": named_file_size(kast_home, "source-index.db"),
        "walBytes": named_file_size(kast_home, "source-index.db-wal"),
        "kastHomeBytes": kast_home_bytes,
        "kastCacheBytes": kast_cache_bytes,
        "gradleCacheBytes": gradle_cache_bytes,
        "userHomeBytes": user_home_bytes,
        "workspaceBytes": workspace_bytes,
        "ownedBytes": (
            kast_home_bytes
            + kast_cache_bytes
            + gradle_cache_bytes
            + user_home_bytes
            + max(workspace_bytes - initial_workspace_bytes, 0)
        ),
    }
    storage_observed_at = observed_at
    storage_state_file.write_text(json.dumps({
        "initialWorkspaceBytes": initial_workspace_bytes,
        "observedAtEpochMillis": storage_observed_at,
        "storage": storage,
    }), encoding="utf-8")
else:
    storage = storage_state["storage"]
    storage_observed_at = last_storage_at

pids = {int(pid) for pid in owned_pids.splitlines() if pid.isdigit()}
rss_kib = virtual_kib = process_count = 0
cpu_percent = 0.0
ps = subprocess.run(
    ["ps", "-axo", "pid=,rss=,vsz=,pcpu="],
    check=False,
    capture_output=True,
    text=True,
)
observed_pids = []
for line in ps.stdout.splitlines():
    parts = line.strip().split(None, 3)
    if len(parts) != 4:
        continue
    try:
        pid = int(parts[0])
    except ValueError:
        continue
    if pid not in pids:
        continue
    try:
        rss_kib += int(parts[1])
        virtual_kib += int(parts[2])
        cpu_percent += float(parts[3])
    except ValueError:
        continue
    observed_pids.append(pid)
    process_count += 1

try:
    raw_runtime_status = json.loads(Path(status_path).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    raw_runtime_status = None

def project_runtime_status(payload):
    if not isinstance(payload, dict):
        return payload
    root = payload.get("result", payload)
    if not isinstance(root, dict):
        return payload
    projected_root = {
        key: value
        for key, value in root.items()
        if key not in {"candidates", "pathResolution", "descriptorDirectory"}
    }
    selected = projected_root.get("selected")
    if isinstance(selected, dict):
        selected = dict(selected)
        status = selected.get("runtimeStatus")
        if isinstance(status, dict):
            status = dict(status)
            status.pop("sourceModuleNames", None)
            status.pop("dependentModuleNamesBySourceModuleName", None)
            selected["runtimeStatus"] = status
        projected_root["selected"] = selected
    if root is payload:
        return projected_root
    projected = {key: value for key, value in payload.items() if key != "result"}
    projected["result"] = projected_root
    return projected

runtime_status = project_runtime_status(raw_runtime_status)

sample = {
    "observedAtEpochMillis": observed_at,
    "phase": phase,
    "statusExitCode": int(status_exit),
    "runtimeStatus": runtime_status,
    "storageFresh": storage_fresh,
    "storageObservedAtEpochMillis": storage_observed_at,
    "storage": storage,
    "resources": {
        "rssBytes": rss_kib * 1024,
        "virtualBytes": virtual_kib * 1024,
        "cpuPercent": cpu_percent,
        "processCount": process_count,
        "processIds": sorted(observed_pids),
    },
}
with samples_file.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps(sample, separators=(",", ":")) + "\n")
PY
}

runtime_stop_was_proven() {
  local output="$1"
  run_supervised_command "$(role_command_deadline)" runtime-stop-proof \
    "$BENCHMARK_PYTHON_BIN" - "$output" <<'PY'
import json
import sys
from pathlib import Path

try:
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)
result = payload.get("result", payload)
if not isinstance(result, dict):
    raise SystemExit(1)
if result.get("stopped") is True or result.get("stoppedCount", 0) > 0:
    raise SystemExit(0)
raise SystemExit(1)
PY
}

terminate_owned_processes() {
  local deadline result_file
  deadline=$(($(monotonic_millis) + TEARDOWN_COMMAND_LIMIT_MILLIS))
  result_file="$benchmark_run_dir/owned-process-teardown.json"
  "$BENCHMARK_PYTHON_BIN" "$BENCHMARK_COMMAND_SUPERVISOR" terminate-owned \
    --deadline-monotonic-ms "$deadline" \
    --marker "$benchmark_run_marker" \
    --event-log "$benchmark_command_events_file" \
    --result-json "$result_file" \
    --term-grace-millis 5000 \
    --kill-grace-millis 2000
}

stop_runtime_and_prove() {
  local stop_result=0 stop_output="$benchmark_run_dir/runtime-stop.json" stop_deadline
  if [[ "$benchmark_runtime_invoked" == true ]]; then
    stop_deadline=$(($(monotonic_millis) + TEARDOWN_COMMAND_LIMIT_MILLIS))
    benchmark_command_deadline_override="$stop_deadline" \
      run_json_command "$stop_output" developer runtime stop \
        --workspace-root "$workspace" || stop_result=$?
    if ((stop_result == 0)) \
        && ! benchmark_command_deadline_override="$stop_deadline" \
          runtime_stop_was_proven "$stop_output"; then
      print_file_stderr "$stop_output"
      printf 'error: runtime stop did not prove that a runtime stopped\n' >&2
      stop_result=1
    fi
  fi
  terminate_owned_processes || stop_result=1
  return "$stop_result"
}

finalize_run_evidence() {
  local output="$1" correctness="$2" teardown_proven="$3" worktree_removed="$4"
  local original_result="$5"
  local retry_summary="$benchmark_run_dir/retry-summary.json"
  summarize_runtime_retry_transitions "$benchmark_samples_file" "$retry_summary"
  run_supervised_command "$benchmark_finalization_deadline_monotonic_ms" finalization \
    "$BENCHMARK_PYTHON_BIN" - \
    "$benchmark_phases_file" "$benchmark_samples_file" "$retry_summary" "$output" \
    "$benchmark_role" "$benchmark_bundle" "$benchmark_bundle_digest" \
    "$benchmark_kast_home/current/receipt.json" "$correctness" \
    "$benchmark_correctness_file" \
    "$benchmark_workspace_poll_retries" "$benchmark_semantic_not_ready_retries" \
    "$benchmark_graph_retries" "$benchmark_status_failures" \
    "$teardown_proven" "$worktree_removed" "$benchmark_workspace_relative_root" \
    "$scoped_graph_file" "$benchmark_command_events_file" "$original_result" <<'PY'
import json
import re
import sys
from pathlib import Path

(
    phases_path,
    samples_path,
    retry_summary_path,
    output_path,
    role,
    bundle_path,
    bundle_digest,
    receipt_path,
    correctness,
    correctness_path,
    workspace_retries,
    semantic_retries,
    graph_retries,
    status_failures,
    teardown_proven,
    worktree_removed,
    workspace_relative_root,
    graph_file,
    command_events_path,
    original_result,
) = sys.argv[1:]

phases = []
phase_file = Path(phases_path)
if phase_file.exists():
    for line in phase_file.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        name, started, finished, started_monotonic, finished_monotonic, duration = line.split("\t")
        phases.append({
            "name": name,
            "startedAtEpochMillis": int(started),
            "finishedAtEpochMillis": int(finished),
            "startedAtMonotonicMillis": int(started_monotonic),
            "finishedAtMonotonicMillis": int(finished_monotonic),
            "durationMillis": int(duration),
        })

samples = []
sample_file = Path(samples_path)
if sample_file.exists():
    for line in sample_file.read_text(encoding="utf-8").splitlines():
        if line:
            samples.append(json.loads(line))
try:
    receipt = json.loads(Path(receipt_path).read_text(encoding="utf-8"))
    version = receipt.get("activeVersion") or receipt.get("version")
except (OSError, json.JSONDecodeError):
    version = None
if not isinstance(version, str) or not version:
    version = "unknown"

try:
    correctness_evidence = json.loads(Path(correctness_path).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    correctness_evidence = None

supervised_commands = []
try:
    for line in Path(command_events_path).read_text(encoding="utf-8").splitlines():
        if line:
            supervised_commands.append(json.loads(line))
except (OSError, json.JSONDecodeError):
    supervised_commands = []

bundle_name = Path(bundle_path).name
version_match = re.fullmatch(
    r"kast-linux-x64-(v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\.tar\.gz",
    bundle_name,
)
expected_version = version_match.group(1) if version_match else None

def runtime_backend_versions(progress_samples):
    versions = set()
    for sample in progress_samples:
        payload = sample.get("runtimeStatus")
        if not isinstance(payload, dict):
            continue
        result = payload.get("result", payload)
        if not isinstance(result, dict):
            continue
        runtime = result.get("runtime")
        if isinstance(runtime, dict):
            for candidate in (
                runtime,
                runtime.get("status"),
                runtime.get("runtimeStatus"),
            ):
                if isinstance(candidate, dict) and isinstance(candidate.get("backendVersion"), str):
                    versions.add(candidate["backendVersion"])
        selected = result.get("selected")
        if isinstance(selected, dict):
            for candidate in (
                selected.get("runtimeStatus"),
                selected.get("descriptor"),
                selected.get("capabilities"),
            ):
                if isinstance(candidate, dict) and isinstance(candidate.get("backendVersion"), str):
                    versions.add(candidate["backendVersion"])
    return sorted(versions)

observed_runtime_backend_versions = runtime_backend_versions(samples)

def values(group, field):
    return [sample[group][field] for sample in samples if field in sample.get(group, {})]

def initial(group, field):
    found = values(group, field)
    return found[0] if found else 0

def final(group, field):
    found = values(group, field)
    return found[-1] if found else 0

def peak(group, field):
    return max(values(group, field), default=0)

def growth(group, field):
    return max(peak(group, field) - initial(group, field), 0)

retry_summary = json.loads(Path(retry_summary_path).read_text(encoding="utf-8"))
payload = {
    "schemaVersion": 1,
    "role": role,
    "bundle": {
        "version": version,
        "expectedVersion": expected_version,
        "runtimeBackendVersions": observed_runtime_backend_versions,
        "sha256": bundle_digest,
        "fileName": bundle_name,
    },
    "workspace": {
        "repositoryRelativeRoot": workspace_relative_root,
        "graphFile": graph_file,
    },
    "correctness": correctness == "true",
    "correctnessEvidence": correctness_evidence,
    "diagnostic": {
        "outcome": "SUCCEEDED" if original_result == "0" else "FAILED",
        "roleExitCode": int(original_result),
        "supervisedCommands": supervised_commands,
    },
    "isolation": {
        "processTeardownProven": teardown_proven == "true",
        "worktreeRemoved": worktree_removed == "true",
    },
    "phases": phases,
    "progressSamples": samples,
    "storage": {
        "initialDatabaseBytes": initial("storage", "databaseBytes"),
        "finalDatabaseBytes": final("storage", "databaseBytes"),
        "peakDatabaseBytes": peak("storage", "databaseBytes"),
        "databaseGrowthBytes": growth("storage", "databaseBytes"),
        "initialWalBytes": initial("storage", "walBytes"),
        "finalWalBytes": final("storage", "walBytes"),
        "peakWalBytes": peak("storage", "walBytes"),
        "walGrowthBytes": growth("storage", "walBytes"),
        "initialKastHomeBytes": initial("storage", "kastHomeBytes"),
        "finalKastHomeBytes": final("storage", "kastHomeBytes"),
        "peakKastHomeBytes": peak("storage", "kastHomeBytes"),
        "initialKastCacheBytes": initial("storage", "kastCacheBytes"),
        "finalKastCacheBytes": final("storage", "kastCacheBytes"),
        "peakKastCacheBytes": peak("storage", "kastCacheBytes"),
        "initialGradleCacheBytes": initial("storage", "gradleCacheBytes"),
        "finalGradleCacheBytes": final("storage", "gradleCacheBytes"),
        "peakGradleCacheBytes": peak("storage", "gradleCacheBytes"),
        "initialUserHomeBytes": initial("storage", "userHomeBytes"),
        "finalUserHomeBytes": final("storage", "userHomeBytes"),
        "peakUserHomeBytes": peak("storage", "userHomeBytes"),
        "initialWorkspaceBytes": initial("storage", "workspaceBytes"),
        "finalWorkspaceBytes": final("storage", "workspaceBytes"),
        "peakWorkspaceBytes": peak("storage", "workspaceBytes"),
        "initialOwnedBytes": initial("storage", "ownedBytes"),
        "finalOwnedBytes": final("storage", "ownedBytes"),
        "peakOwnedBytes": peak("storage", "ownedBytes"),
        "ownedGrowthBytes": growth("storage", "ownedBytes"),
    },
    "retries": {
        "workspaceIndexPoll": int(workspace_retries),
        "semanticNotReadyPoll": int(semantic_retries),
        "graphGenerationConflict": int(graph_retries),
        "runtimeStatusFailures": int(status_failures),
        **retry_summary,
    },
    "resources": {
        "peakRssBytes": peak("resources", "rssBytes"),
        "peakVirtualBytes": peak("resources", "virtualBytes"),
        "peakCpuPercent": peak("resources", "cpuPercent"),
        "peakProcessCount": peak("resources", "processCount"),
    },
}
Path(output_path).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

seal_finalization_evidence() {
  local output="$1"
  run_supervised_command "$benchmark_finalization_deadline_monotonic_ms" \
    finalization-seal "$BENCHMARK_PYTHON_BIN" - \
    "$output" "$benchmark_command_events_file" <<'PY'
import json
import os
import sys
from pathlib import Path

output_path = Path(sys.argv[1])
events_path = Path(sys.argv[2])
payload = json.loads(output_path.read_text(encoding="utf-8"))
events = [
    json.loads(line)
    for line in events_path.read_text(encoding="utf-8").splitlines()
    if line
]
finalization = [
    event
    for event in events
    if event.get("type") == "KAST_BENCHMARK_SUPERVISED_COMMAND"
    and event.get("operation") == "finalization"
    and event.get("outcome") == "SUCCEEDED"
    and event.get("exitCode") == 0
]
if len(finalization) != 1:
    raise SystemExit("exact successful finalization event is unavailable")
diagnostic = payload.get("diagnostic")
if not isinstance(diagnostic, dict):
    raise SystemExit("run diagnostic is unavailable")
diagnostic["supervisedCommands"] = events
temporary = output_path.with_name(f".{output_path.name}.{os.getpid()}.sealed")
temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
os.replace(temporary, output_path)
PY
}

write_emergency_role_evidence() {
  local output="$1" original_result="$2" finalization_result="$3" deadline
  deadline=$(($(monotonic_millis) + FINALIZATION_LIMIT_MILLIS))
  write_typed_emergency_role_evidence \
    "$output" "$benchmark_role" "$benchmark_bundle" "$benchmark_bundle_digest" \
    "$original_result" "$finalization_result" "$benchmark_command_events_file" "$deadline"
}

remove_role_worktree() {
  local deadline
  if [[ "$benchmark_worktree_added" != true ]]; then
    [[ ! -e "$benchmark_repository_worktree" ]]
    return
  fi
  deadline=$(($(monotonic_millis) + TEARDOWN_COMMAND_LIMIT_MILLIS))
  run_supervised_command "$deadline" worktree-cleanup \
    git -C "$repository_cache" worktree remove --force "$benchmark_repository_worktree"
  benchmark_worktree_added=false
}

finish_role() {
  local original_result="$1" teardown_result=0 worktree_result=0
  local teardown_proven=false worktree_removed=false correctness=false final_result
  trap - EXIT
  set +e
  benchmark_cold_budget_active=false
  stop_runtime_and_prove
  teardown_result=$?
  if ((teardown_result == 0)); then
    teardown_proven=true
    remove_role_worktree
    worktree_result=$?
    if ((worktree_result == 0)); then
      worktree_removed=true
      : >"$benchmark_run_dir/isolation-proven"
    fi
  fi
  if ((original_result == 0 && teardown_result == 0 && worktree_result == 0)) \
      && [[ "$benchmark_semantic_correctness" == true ]]; then
    correctness=true
  fi
  benchmark_finalization_deadline_monotonic_ms=$((
    $(monotonic_millis) + FINALIZATION_LIMIT_MILLIS
  ))
  finalize_run_evidence \
    "$benchmark_run_evidence" "$correctness" "$teardown_proven" "$worktree_removed" \
    "$original_result"
  final_result=$?
  if ((final_result == 0)); then
    seal_finalization_evidence "$benchmark_run_evidence"
    final_result=$?
  fi
  if ((final_result != 0)); then
    write_emergency_role_evidence \
      "$benchmark_run_evidence" "$original_result" "$final_result"
  fi
  if ((original_result != 0 || teardown_result != 0 || worktree_result != 0 || final_result != 0)); then
    exit 1
  fi
  exit 0
}

run_bundle_benchmark() {
  benchmark_role="$1"
  benchmark_bundle="$2"
  benchmark_run_evidence="$3"
  local phase_started_epoch phase_started_monotonic cold_started_epoch cold_started_monotonic
  local admission_epoch='' admission_monotonic='' admission_recorded=false
  local workspace_finished_epoch workspace_finished_monotonic
  local runtime_command_pid runtime_result=0
  local now_monotonic poll_seconds identity_timeout_ms identity_deadline published_pointer

  benchmark_run_dir="$scratch/runs/$benchmark_role"
  benchmark_repository_worktree="$benchmark_run_dir/repository"
  benchmark_bundle_dir="$benchmark_run_dir/bundle"
  benchmark_user_dir="$benchmark_run_dir/user"
  benchmark_kast_home="$benchmark_run_dir/kast-home"
  benchmark_cache_dir="$benchmark_run_dir/kast-cache"
  benchmark_gradle_dir="$benchmark_run_dir/gradle"
  benchmark_phases_file="$benchmark_run_dir/phases.tsv"
  benchmark_samples_file="$benchmark_run_dir/samples.jsonl"
  benchmark_storage_state_file="$benchmark_run_dir/storage-state.json"
  benchmark_correctness_file="$benchmark_run_dir/correctness.json"
  benchmark_workspace_identity_pages="$benchmark_run_dir/workspace-identity-pages"
  benchmark_command_events_file="$benchmark_run_dir/supervised-commands.jsonl"
  benchmark_run_marker="release-benchmark-$name-$benchmark_role-$$"
  benchmark_bundle_digest=
  benchmark_workspace_poll_retries=0
  benchmark_semantic_not_ready_retries=0
  benchmark_graph_retries=0
  benchmark_status_failures=0
  benchmark_sample_sequence=0
  benchmark_last_status_file=
  benchmark_last_status_observed_epoch=
  benchmark_last_status_observed_monotonic=
  benchmark_runtime_invoked=false
  benchmark_cold_budget_active=false
  benchmark_worktree_added=false
  benchmark_semantic_correctness=false
  benchmark_workspace_relative_root=.
  workspace=
  scoped_graph_file=
  active_kastctl=
  poll_seconds="${KAST_RELEASE_INDEX_POLL_SECONDS:-5}"
  identity_timeout_ms="${KAST_RELEASE_IDENTITY_TIMEOUT_MS:-$IDENTITY_CAPTURE_LIMIT_MILLIS}"
  [[ "$poll_seconds" =~ ^[0-9]+$ ]] \
    || { printf 'error: progress poll seconds must be a non-negative integer\n' >&2; return 1; }
  [[ "$identity_timeout_ms" =~ ^[1-9][0-9]*$ ]] \
    || { printf 'error: semantic identity timeout must be a positive integer\n' >&2; return 1; }

  trap 'finish_role $?' EXIT
  run_supervised_command "$(role_command_deadline)" role-directory-preparation \
    mkdir -p \
    "$benchmark_run_dir" "$benchmark_bundle_dir" "$benchmark_user_dir" \
    "$benchmark_kast_home" "$benchmark_cache_dir" "$benchmark_gradle_dir"
  : >"$benchmark_phases_file"
  : >"$benchmark_samples_file"
  while IFS= read -r preparation_event; do
    printf '%s\n' "$preparation_event" >>"$benchmark_command_events_file"
  done <"$benchmark_preparation_events_file"
  benchmark_bundle_digest="$(sha256_file "$benchmark_bundle")"

  run_supervised_command "$(role_command_deadline)" worktree-add \
    git -C "$repository_cache" worktree add \
      --detach "$benchmark_repository_worktree" "$revision"
  benchmark_worktree_added=true
  graph_path="$benchmark_repository_worktree/$graph_file"
  [[ -f "$graph_path" ]] \
    || { printf 'error: graph file not found: %s\n' "$graph_file" >&2; return 1; }
  workspace="$(gradle_workspace_for "$graph_path" "$benchmark_repository_worktree")" \
    || { printf 'error: graph file is not inside a Gradle build: %s\n' "$graph_file" >&2; return 1; }
  scoped_graph_file="${graph_path#"$workspace"/}"
  benchmark_workspace_relative_root="${workspace#"$benchmark_repository_worktree"/}"
  [[ "$workspace" != "$benchmark_repository_worktree" ]] || benchmark_workspace_relative_root=.

  phase_started_epoch="$(epoch_millis)"
  phase_started_monotonic="$(monotonic_millis)"
  run_supervised_command "$(role_command_deadline)" bundle-extract \
    tar -xzf "$benchmark_bundle" -C "$benchmark_bundle_dir"
  bundle_bin="$(run_supervised_command "$(role_command_deadline)" bundle-discovery \
    find "$benchmark_bundle_dir" -maxdepth 3 -type f \
      -path '*/libexec/kastctl' -print -quit)"
  [[ -n "$bundle_bin" ]] \
    || { printf 'error: %s bundle does not contain libexec/kastctl\n' "$benchmark_role" >&2; return 1; }
  bundle_root="${bundle_bin%/libexec/kastctl}"
  run_supervised_command "$(role_command_deadline)" bundle-permissions \
    chmod 755 "$bundle_bin"
  run_supervised_command "$(role_command_deadline)" setup \
    env \
      HOME="$benchmark_user_dir" \
      KAST_HOME="$benchmark_kast_home" \
      KAST_CACHE_HOME="$benchmark_cache_dir" \
      GRADLE_USER_HOME="$benchmark_gradle_dir" \
      KAST_WORKSPACE_ID="release-benchmark-$name-$benchmark_role" \
      KAST_BENCHMARK_RUN_ID="$benchmark_run_marker" \
      "$bundle_bin" --output json setup --source "$bundle_root" >/dev/null
  active_kastctl="$benchmark_kast_home/current/libexec/kastctl"
  [[ -x "$active_kastctl" ]] \
    || { printf 'error: setup did not install kastctl for %s\n' "$benchmark_role" >&2; return 1; }
  record_phase setup "$phase_started_epoch" "$phase_started_monotonic"

  phase_started_epoch="$(epoch_millis)"
  phase_started_monotonic="$(monotonic_millis)"
  configure_gradle_java_paths \
    "$benchmark_gradle_dir" "${GRADLE_JAVA_HOME:-}" "${JAVA_HOME:-}"
  run_kastctl_with_cold_budget config set indexing.relationships.enabled "$relationships_enabled" \
    --workspace-root "$workspace" >/dev/null
  if [[ "$relationships_enabled" == true ]]; then
    run_kastctl_with_cold_budget config set indexing.relationships.parallelism 2 \
      --workspace-root "$workspace" >/dev/null
  fi
  run_kastctl_with_cold_budget config set gradle.toolingApiTimeoutMillis "$wait_timeout_ms" \
    --workspace-root "$workspace" >/dev/null
  run_kastctl_with_cold_budget config list --workspace-root "$workspace" \
    >"$benchmark_run_dir/effective-config.toon"
  record_phase configure "$phase_started_epoch" "$phase_started_monotonic"
  benchmark_progress_sample BASELINE true

  cold_started_epoch="$(epoch_millis)"
  cold_started_monotonic="$(monotonic_millis)"
  benchmark_cold_deadline_monotonic_ms=$((cold_started_monotonic + wait_timeout_ms))
  benchmark_cold_budget_active=true
  benchmark_runtime_invoked=true
  run_json_command "$benchmark_run_dir/runtime.json" developer runtime up \
    --workspace-root "$workspace" \
    --wait-timeout-ms "$wait_timeout_ms" &
  runtime_command_pid=$!
  while kill -0 "$runtime_command_pid" >/dev/null 2>&1; do
    benchmark_progress_sample RUNTIME_ADMISSION
    if [[ "$admission_recorded" == false ]] \
        && runtime_is_durably_admitted "$benchmark_last_status_file"; then
      admission_epoch="$benchmark_last_status_observed_epoch"
      admission_monotonic="$benchmark_last_status_observed_monotonic"
      record_phase_at \
        runtimeAdmission "$cold_started_epoch" "$cold_started_monotonic" \
        "$admission_epoch" "$admission_monotonic"
      admission_recorded=true
    fi
    now_monotonic="$(monotonic_millis)"
    if ((now_monotonic >= benchmark_cold_deadline_monotonic_ms)); then
      break
    fi
    wait_poll_interval "$poll_seconds"
  done
  if wait "$runtime_command_pid"; then
    runtime_result=0
  else
    runtime_result=$?
  fi
  if ((runtime_result != 0)); then
    benchmark_cold_budget_active=false
    printf 'error: %s runtime admission failed with exit %s\n' \
      "$benchmark_role" "$runtime_result" >&2
    return "$runtime_result"
  fi

  benchmark_progress_sample RUNTIME_COMMAND_COMPLETE
  if [[ "$admission_recorded" == false ]] \
      && runtime_is_durably_admitted "$benchmark_last_status_file"; then
    admission_epoch="$benchmark_last_status_observed_epoch"
    admission_monotonic="$benchmark_last_status_observed_monotonic"
    record_phase_at \
      runtimeAdmission "$cold_started_epoch" "$cold_started_monotonic" \
      "$admission_epoch" "$admission_monotonic"
    admission_recorded=true
  fi
  if [[ "$admission_recorded" == false ]]; then
    printf 'error: %s runtime command completed without durable admission evidence\n' \
      "$benchmark_role" >&2
    return 1
  fi

  wait_for_exact_workspace_index \
    "$benchmark_run_dir/workspace-files.json" "$wait_timeout_ms" \
    agent workspace-files --workspace-root "$workspace" --count
  capture_workspace_identity_pages \
    "$benchmark_run_dir/workspace-files.json" \
    "$benchmark_workspace_identity_pages" \
    "$workspace"
  benchmark_progress_sample WORKSPACE_EXACT true
  workspace_finished_epoch="$(epoch_millis)"
  workspace_finished_monotonic="$(monotonic_millis)"
  benchmark_cold_budget_active=false
  record_phase_at \
    workspaceIndex "$admission_epoch" "$admission_monotonic" \
    "$workspace_finished_epoch" "$workspace_finished_monotonic"
  record_phase_at \
    coldIndex "$cold_started_epoch" "$cold_started_monotonic" \
    "$workspace_finished_epoch" "$workspace_finished_monotonic"
  phase_started_epoch="$(epoch_millis)"
  phase_started_monotonic="$(monotonic_millis)"
  run_generation_bound_graph_refresh "$benchmark_run_dir/graph-refresh.json" agent graph \
    --workspace-root "$workspace" \
    --operation refresh \
    --file-path "$scoped_graph_file" \
    --exclusive
  record_phase graphRefresh "$phase_started_epoch" "$phase_started_monotonic"
  benchmark_progress_sample GRAPH_REFRESH_COMPLETE true

  phase_started_epoch="$(epoch_millis)"
  phase_started_monotonic="$(monotonic_millis)"
  run_json_command "$benchmark_run_dir/graph.json" agent graph \
    --workspace-root "$workspace" --operation summary
  record_phase graphSummary "$phase_started_epoch" "$phase_started_monotonic"
  benchmark_progress_sample GRAPH_SUMMARY_COMPLETE true

  phase_started_epoch="$(epoch_millis)"
  phase_started_monotonic="$(monotonic_millis)"
  identity_deadline=$((phase_started_monotonic + identity_timeout_ms))
  published_pointer="$(benchmark_command_deadline_override="$identity_deadline" \
    find_published_workspace_pointer "$benchmark_kast_home" "$benchmark_cache_dir")"
  verify_benchmark_evidence \
    "$benchmark_run_dir/workspace-files.json" \
    "$benchmark_run_dir/graph-refresh.json" \
    "$benchmark_run_dir/graph.json" \
    "$scoped_graph_file" \
    "$workspace" \
    "$benchmark_workspace_identity_pages" \
    "$published_pointer" \
    "$identity_deadline" \
    "$benchmark_correctness_file"
  record_phase semanticIdentity "$phase_started_epoch" "$phase_started_monotonic"
  benchmark_progress_sample COMPLETE true
  benchmark_semantic_correctness=true
}

write_skipped_run_evidence() {
  local output="$1" role="$2" bundle="$3" reason="$4" digest deadline
  digest="$(sha256_file "$bundle")"
  deadline=$(($(monotonic_millis) + FINALIZATION_LIMIT_MILLIS))
  run_supervised_command "$deadline" skipped-role-finalization \
    "$BENCHMARK_PYTHON_BIN" - \
    "$output" "$role" "$bundle" "$digest" "$reason" \
    "${benchmark_command_events_file:-$benchmark_preparation_events_file}" <<'PY'
import json
import sys
from pathlib import Path

output, role, bundle, digest, reason, events_path = sys.argv[1:]
events = []
try:
    for line in Path(events_path).read_text(encoding="utf-8").splitlines():
        if line:
            events.append(json.loads(line))
except (OSError, json.JSONDecodeError):
    events = []
Path(output).write_text(json.dumps({
    "schemaVersion": 1,
    "role": role,
    "bundle": {
        "version": "unknown",
        "sha256": digest,
        "fileName": Path(bundle).name,
    },
    "correctness": False,
    "correctnessEvidence": None,
    "skipped": {"reason": reason},
    "diagnostic": {
        "outcome": "SKIPPED",
        "roleExitCode": 1,
        "supervisedCommands": events,
    },
    "isolation": {"processTeardownProven": False, "worktreeRemoved": False},
    "phases": [],
    "storage": {},
    "retries": {},
    "resources": {},
    "progressSamples": [],
}, indent=2) + "\n", encoding="utf-8")
PY
}

stable_result=0
candidate_result=0
comparison_result=0
set +e
run_strict run_bundle_benchmark stable "$stable_bundle" "$stable_evidence"
stable_result=$?
set -e
if [[ ! -f "$stable_evidence" ]]; then
  write_skipped_run_evidence \
    "$stable_evidence" stable "$stable_bundle" \
    'stable role failed before evidence could be finalized'
fi

if [[ -f "$scratch/runs/stable/isolation-proven" ]]; then
  set +e
  run_strict run_bundle_benchmark candidate "$candidate_bundle" "$candidate_evidence"
  candidate_result=$?
  set -e
  if [[ ! -f "$candidate_evidence" ]]; then
    write_skipped_run_evidence \
      "$candidate_evidence" candidate "$candidate_bundle" \
      'candidate role failed before evidence could be finalized'
  fi
else
  candidate_result=1
  write_skipped_run_evidence \
    "$candidate_evidence" candidate "$candidate_bundle" \
    'stable role process teardown was not proven'
fi

set +e
compare_benchmark_evidence \
  "$stable_evidence" "$candidate_evidence" "$comparison_evidence"
comparison_result=$?
set -e
assemble_benchmark_evidence \
  "$stable_evidence" "$candidate_evidence" "$comparison_evidence" "$evidence_output"

if [[ -f "$scratch/runs/stable/isolation-proven" \
    && -f "$scratch/runs/candidate/isolation-proven" ]]; then
  benchmark_command_events_file="$benchmark_preparation_events_file"
  run_supervised_command "$(role_command_deadline)" scratch-cleanup \
    "$BENCHMARK_PYTHON_BIN" - "$scratch" <<'PY'
import shutil
import sys
from pathlib import Path

scratch = Path(sys.argv[1]).resolve()
if scratch.name.startswith("kast-real-repository."):
    shutil.rmtree(scratch)
else:
    raise SystemExit(f"refusing to remove unexpected scratch path: {scratch}")
PY
  benchmark_command_events_file=
  run_supervised_command "$(role_command_deadline)" preparation-log-cleanup \
    rm -f "$benchmark_preparation_events_file"
else
  printf 'benchmark isolation was not proven; retained diagnostics: %s (preparation events: %s)\n' \
    "$scratch" "$benchmark_preparation_events_file" >&2
fi

if ((stable_result != 0 || candidate_result != 0 || comparison_result != 0)); then
  printf 'benchmark %s failed; evidence: %s\n' "$name" "$evidence_output" >&2
  exit 1
fi
printf 'benchmark %s passed at %s; evidence: %s\n' "$name" "$revision" "$evidence_output"
