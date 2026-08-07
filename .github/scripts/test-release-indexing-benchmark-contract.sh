#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'release indexing benchmark contract: %s\n' "$*" >&2
  exit 1
}

require() {
  local file="$1" text="$2" message="$3"
  grep -Fq -- "$text" "$file" || die "$message"
}

reject() {
  local file="$1" text="$2" message="$3"
  ! grep -Fq -- "$text" "$file" || die "$message"
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
benchmark="$repo_root/scripts/release/benchmark-real-repositories.sh"
supervisor="$repo_root/.github/scripts/release/benchmark/benchmark-command-supervisor.py"
aggregator="$repo_root/.github/scripts/release/benchmark/aggregate-indexing-benchmark-evidence.py"
release="$repo_root/.github/workflows/release.yml"
cli_root="$repo_root/cli-rs/src/interface/cli/root.rs"
cli_config="$repo_root/cli-rs/src/interface/cli/workspace/config.rs"
dispatch="$repo_root/cli-rs/src/interface/entrypoint/dispatch.rs"
cli_model="$repo_root/cli-rs/src/configuration/config/model.rs"
project_indexer="$repo_root/indexer/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt"
reference_indexer="$repo_root/index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt"
schema="$repo_root/index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/schema/SourceIndexSchemaTables.kt"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-benchmark-contract.XXXXXX")"
trap 'find "$scratch" -depth -delete' EXIT

[[ -x "$benchmark" ]] || die 'real-repository benchmark runner must be executable'
[[ -x "$supervisor" ]] || die 'atomic benchmark command supervisor must be executable'
[[ -x "$aggregator" ]] || die 'strict benchmark evidence aggregator must be executable'

supervisor_help="$(python3 "$supervisor" --help)"
for supervisor_command in run terminate-owned self-test-capture-gap; do
  grep -Fq -- "$supervisor_command" <<<"$supervisor_help" \
    || die "atomic supervisor is missing $supervisor_command"
done

help="$($benchmark --help)"
for option in --stable-bundle --candidate-bundle --evidence-output; do
  grep -Fq -- "$option" <<<"$help" || die "benchmark help is missing $option"
done

test_signal_helper="$scratch/test-signal-helper.sh"
cat >"$test_signal_helper" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
signal_name="$1"
pid="$2"
expected_start_identity="$3"
observed_start_identity="ps-lstart:$(ps -p "$pid" -o lstart= | awk '{$1=$1; print}')"
[[ "$observed_start_identity" == "$expected_start_identity" ]] || exit 3
kill "-$signal_name" "$pid"
SH
chmod 700 "$test_signal_helper"

if executable_helper_output="$(env \
    KAST_BENCHMARK_TEST_SIGNAL_HELPER="$test_signal_helper" \
    KAST_BENCHMARK_TEST_MODE=true \
    KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER=true \
    "$benchmark" --help 2>&1)"; then
  die 'executable benchmark mode accepted a test signal helper'
fi
[[ "$executable_helper_output" == *'test signal helper is unavailable in executable mode'* ]] \
  || die 'executable helper rejection did not report the production boundary'

require "$release" 'real-repository-indexing:' \
  'release workflow must own the real-repository indexing gate'
require "$release" 'fail-fast: false' \
  'repository matrix failures must not cancel remaining repositories'
require "$release" 'ktorio/ktor-samples.git' \
  'release gate must include a self-contained official Ktor sample'
reject "$release" 'ktorio/ktor.git' \
  'release gate must not use the Ktor included-build probe'
require "$release" 'AleksK1NG/Kotlin-Clean-Architecture-CQRS.git' \
  'release gate must include a Java 21 Kotlin Spring project'
reject "$release" 'spring-projects/spring-boot.git' \
  'release gate must not import the full Spring Boot monorepo'
require "$release" 'square/okhttp.git' \
  'release gate must include a Kotlin Multiplatform integration repository'
require "$release" \
  'graph_file: httpbin/src/main/kotlin/io/ktor/samples/httpbin/Server.kt' \
  'Ktor must use a pinned compiler graph probe'
require "$release" \
  'graph_file: src/main/kotlin/com/alexander/bryksin/kotlinspringcleanarchitecture/KotlinSpringCleanArchitectureApplication.kt' \
  'Spring must use a pinned compiler graph probe'
require "$release" \
  'graph_file: okcurl/src/main/kotlin/okhttp3/curl/Main.kt' \
  'OkHttp must use a pinned compiler graph probe'

relationship_setting() {
  local name="$1"
  awk -v name="$name" '
    $1 == "-" && $2 == "name:" {
      if (selected) exit
      selected = ($3 == name)
      next
    }
    selected && $1 == "relationships_enabled:" { print $2; exit }
  ' "$release"
}

for repository_name in ktor spring-boot okhttp; do
  [[ "$(relationship_setting "$repository_name")" == true ]] \
    || die "$repository_name must complete relationship indexing"
done

# shellcheck disable=SC2016 # GitHub expressions are matched literally.
require "$release" 'setup-bundle-linux-x64-${{ github.run_id }}' \
  'release gate must test the built release bundle'
require "$release" 'Set up Gradle Java 17 toolchain' \
  'release gate must install the Ktor sample toolchain'
require "$release" 'set-default: false' \
  'Gradle toolchain setup must not replace the Java 21 Kast runtime'
# shellcheck disable=SC2016
require "$release" 'GRADLE_JAVA_HOME: ${{ steps.gradle-java.outputs.path }}' \
  'release gate must bind the installed Gradle toolchain'
reject "$release" 'GRADLE_OPTS=' \
  'Gradle toolchain paths must not depend on a gradlew-only variable'
require "$release" "--stable-bundle \"\$stable_bundle\"" \
  'release gate must pass the verified latest-stable bundle'
require "$release" "--candidate-bundle \"\$candidate_bundle\"" \
  'release gate must pass the candidate bundle'
require "$release" "--evidence-output \"\$evidence_output\"" \
  'release gate must persist comparative evidence'
require "$release" "real-repository-indexing-\${{ matrix.name }}-\${{ github.run_id }}" \
  'release gate must retain evidence for every repository'
reject "$release" "if: \${{ false }}" \
  'real-repository release indexing must be enabled'
require "$release" 'needs.real-repository-indexing.result' \
  'release publication must require repository indexing'
require "$release" '.github/scripts/release/benchmark/aggregate-indexing-benchmark-evidence.py aggregate-release' \
  'release aggregation must use the executable strict nested-evidence validator'

require "$benchmark" 'readonly COLD_INDEX_LIMIT_MILLIS=2700000' \
  'real repositories must have a 45-minute cold-index bound'
require "$benchmark" 'run_kastctl_with_cold_budget config list' \
  'benchmark must capture effective workspace configuration'
require "$benchmark" "run_kastctl_with_cold_budget config set indexing.relationships.enabled \"\$relationships_enabled\"" \
  'benchmark must apply the declared relationship indexing plan'
require "$benchmark" 'run_kastctl_with_cold_budget config set indexing.relationships.parallelism 2' \
  'benchmark must exercise relationship indexing configuration'
require "$benchmark" 'run_json_command()' \
  'benchmark must centralize typed command failure reporting'
require "$benchmark" 'print_file_stderr "$output"' \
  'typed command failures must preserve their response payload'
require "$benchmark" 'wait_for_exact_workspace_index()' \
  'benchmark must wait for typed exact workspace evidence'
require "$benchmark" 'run_generation_bound_graph_refresh()' \
  'benchmark must own bounded generation-conflict recovery'
require "$benchmark" 'graph_refresh_attempts=3' \
  'graph refresh recovery must have a fixed attempt bound'
require "$benchmark" 'expectedGeneration' \
  'graph refresh recovery must require expected-generation evidence'
require "$benchmark" 'actualGeneration' \
  'graph refresh recovery must require actual-generation evidence'
require "$benchmark" "run_bundle_benchmark stable \"\$stable_bundle\"" \
  'latest stable must run first on the worker'
require "$benchmark" "run_bundle_benchmark candidate \"\$candidate_bundle\"" \
  'candidate must run after latest stable on the worker'
require "$benchmark" 'benchmark_progress_sample()' \
  'benchmark must retain progress samples while indexing'
require "$benchmark" 'benchmark_owned_process_ids()' \
  'benchmark must identify the complete role-owned process tree'
require "$benchmark" 'KAST_BENCHMARK_RUN_ID=' \
  'benchmark must mark every role-owned process'
require "$supervisor" 'os.pidfd_open' \
  'Linux teardown must open a pidfd for the captured process'
require "$supervisor" 'signal.pidfd_send_signal' \
  'Linux teardown must signal the opened pidfd atomically'
require "$supervisor" '"processGroupClosure"' \
  'supervised failures must emit exact process-group closure proof'
# shellcheck disable=SC2016
reject "$benchmark" 'signal_process_snapshot()' \
  'production teardown must not fall back to verify-then-kill'
reject "$benchmark" 'benchmark_owned_process_snapshots()' \
  'process ownership must not cross an unretained PID snapshot boundary'
reject "$benchmark" 'ps eww' \
  'production ownership discovery must not classify from a stale ps snapshot'
require "$benchmark" 'run_kastctl_with_cold_budget()' \
  'every cold-phase Kast call must share the remaining monotonic budget'
require "$benchmark" 'readonly TEARDOWN_COMMAND_LIMIT_MILLIS=30000' \
  'runtime teardown must have a fixed command deadline so evidence finalization cannot hang'
# shellcheck disable=SC2016
require "$benchmark" 'benchmark_command_deadline_override="$stop_deadline"' \
  'runtime stop must be bounded before evidence finalization'
# shellcheck disable=SC2016
reject "$benchmark" '"$runtime_command_pid" "$runtime_command_start_identity" 50 20 || true' \
  'runtime timeout must fail closed when atomic teardown cannot be proven'
require "$benchmark" 'run_strict()' \
  'benchmark must execute each role with strict failure propagation'
require "$benchmark" "readonly COMPARABLE_PHASES='setup configure coldIndex graphRefresh graphSummary semanticIdentity'" \
  'benchmark must compare only behavior-independent phase boundaries'
require "$benchmark" 'runtime_is_durably_admitted()' \
  'runtime admission timing must come from observable durable status'
require "$benchmark" 'KAST_RELEASE_DISK_SAMPLE_SECONDS:-60' \
  'recursive disk measurement must use a coarse sampling interval'
reject "$benchmark" "run_bundle_benchmark stable \"\$stable_bundle\" \"\$stable_evidence\" ||" \
  'stable execution must not disable errexit through an OR-list'
reject "$benchmark" "run_bundle_benchmark candidate \"\$candidate_bundle\" \"\$candidate_evidence\" ||" \
  'candidate execution must not disable errexit through an OR-list'
reject "$benchmark" "repository_worktree=\"\$scratch/repository\"" \
  'stable and candidate must not share one repository worktree'
require "$benchmark" "benchmark_repository_worktree=\"\$benchmark_run_dir/repository\"" \
  'each role must receive its own clean repository worktree'
require "$benchmark" 'runtime_stop_was_proven()' \
  'runtime stop must validate typed stop evidence'
require "$benchmark" 'processTeardownProven' \
  'run evidence must report process teardown proof'
require "$benchmark" 'worktreeRemoved' \
  'run evidence must report worktree removal proof'
reject "$benchmark" "kastctl developer runtime stop --workspace-root \"\$workspace\" >/dev/null 2>&1 || true" \
  'runtime stop failures must not be discarded'
for measurement in \
  databaseBytes walBytes kastHomeBytes kastCacheBytes gradleCacheBytes userHomeBytes workspaceBytes \
  databaseGrowthBytes walGrowthBytes ownedGrowthBytes peakOwnedBytes \
  peakRssBytes peakVirtualBytes peakCpuPercent peakProcessCount \
  semanticNotReadyPoll graphGenerationConflict runtimeStatusFailures \
  runtimeTransitionCount runtimeTransitions; do
  require "$benchmark" "$measurement" \
    "benchmark evidence is missing measurement: $measurement"
done
require "$benchmark" 'compare_benchmark_evidence()' \
  'benchmark must centralize comparison policy'
require "$benchmark" 'PHASE_REGRESSION_PERCENT=15' \
  'phase regression percentage must be explicit'
require "$benchmark" 'PHASE_REGRESSION_MILLIS=60000' \
  'phase regression duration must be explicit'
require "$benchmark" 'DISK_REGRESSION_PERCENT=15' \
  'disk regression percentage must be explicit'
require "$benchmark" 'DISK_REGRESSION_BYTES=268435456' \
  'disk regression size must be explicit'
require "$benchmark" 'settings.gradle.kts' \
  'benchmark must recognize Kotlin Gradle roots'
require "$benchmark" 'settings.gradle' \
  'benchmark must recognize Groovy Gradle roots'
# shellcheck disable=SC2016
require "$benchmark" 'scoped_graph_file="${graph_path#"$workspace"/}"' \
  'benchmark must make the probe relative to the selected Gradle root'
require "$benchmark" '--operation refresh' \
  'benchmark must populate the native graph through the compiler indexer'
require "$benchmark" "--file-path \"\$scoped_graph_file\"" \
  'benchmark must refresh the pinned Kotlin source'
require "$benchmark" '--exclusive' \
  'benchmark graph evidence must stay within the pinned probe scope'
require "$benchmark" 'verify_benchmark_evidence()' \
  'benchmark must centralize correctness validation'
for correctness_field in \
  sourceIndexGeneration workspaceExactTotalCount refreshSymbolCount refreshedPaths removedPaths \
  workspaceFileIdentities workspaceFileIdentitySha256 \
  graphNodeCount graphEdgeOccurrenceCount graphWeightedEdgeCount \
  graphNodeIdentitySha256 graphEdgeIdentitySha256; do
  require "$benchmark" "$correctness_field" \
    "persisted correctness evidence is missing: $correctness_field"
done
require "$benchmark" '"correctnessEvidence": correctness_evidence' \
  'run finalization must persist exact correctness evidence'
require "$benchmark" '"expectedVersion": expected_version' \
  'run finalization must persist the version inferred from the exact bundle name'
require "$benchmark" '"runtimeBackendVersions": observed_runtime_backend_versions' \
  'run finalization must persist observed runtime backend versions'
require "$benchmark" 'refreshed_paths' \
  'benchmark must identify the exact refreshed probe'
require "$benchmark" 'file.get("status") not in {"REFRESHED", "REMOVED"}' \
  'exclusive graph validation must reject unknown coverage states'
reject "$benchmark" 'len(coverage) != 1' \
  'exclusive graph validation must allow typed removals'
reject "$benchmark" '--accept-indexing' \
  'benchmark must not weaken semantic command admission'

reject_graph_file() {
  local candidate_path="$1" output
  if output="$(
    "$benchmark" \
      --name validation \
      --repository https://github.com/example/repository.git \
      --revision 0000000000000000000000000000000000000000 \
      --graph-file "$candidate_path" \
      --relationships-enabled false \
      --stable-bundle /missing-stable \
      --candidate-bundle /missing-candidate \
      --evidence-output /unused/evidence.json \
      --cache-root /unused 2>&1
  )"; then
    die "graph file was accepted: $candidate_path"
  fi
  [[ "$output" == *'graph file must be a relative Kotlin path'* ]] \
    || die "graph file failed at the wrong boundary: $candidate_path: $output"
}

reject_graph_file ''
reject_graph_file /tmp/Probe.kt
reject_graph_file ./Probe.kt
reject_graph_file src//Probe.kt
reject_graph_file src/./Probe.kt
reject_graph_file src/../Probe.kt
reject_graph_file src/Probe.kts

if [[ "$(uname -s)" != Linux ]]; then
  unsupported_stable="$scratch/kast-linux-x64-v0.21.6.tar.gz"
  unsupported_candidate="$scratch/kast-linux-x64-v0.21.7.tar.gz"
  touch "$unsupported_stable" "$unsupported_candidate"
  if unsupported_output="$($benchmark \
      --name unsupported-platform \
      --repository https://github.com/example/repository.git \
      --revision 0000000000000000000000000000000000000000 \
      --graph-file src/Probe.kt \
      --relationships-enabled false \
      --stable-bundle "$unsupported_stable" \
      --candidate-bundle "$unsupported_candidate" \
      --evidence-output "$scratch/unsupported.json" \
      --cache-root "$scratch/unsupported-cache" 2>&1)"; then
    die 'Linux-x64 benchmark accepted an unsupported production platform'
  fi
  [[ "$unsupported_output" == *'requires Linux pidfd signaling'* ]] \
    || die 'unsupported production platform failed without a precise pidfd blocker'
fi

# The comparator is sourced so threshold behavior stays deterministic and does
# not depend on hosted-runner timing or network state.
# shellcheck disable=SC1090 # The checked runner path is resolved above.
source "$benchmark"
set +e
KAST_BENCHMARK_TEST_SIGNAL_HELPER="$test_signal_helper" \
  KAST_BENCHMARK_TEST_MODE=true \
  KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER=false \
  run_supervised_command \
    "$(( $(monotonic_millis) + 1000 ))" sourced-helper-boundary /usr/bin/true \
    >"$scratch/sourced-helper-boundary.stdout" \
    2>"$scratch/sourced-helper-boundary.stderr"
sourced_helper_result=$?
set -e
[[ "$sourced_helper_result" -eq 2 ]] \
  || die 'sourced benchmark accepted a signal helper without both explicit opt-ins'
grep -Fq 'requires sourced mode and both explicit test opt-ins' \
  "$scratch/sourced-helper-boundary.stderr" \
  || die 'sourced helper boundary failed without the exact dual-opt-in diagnostic'
export KAST_BENCHMARK_TEST_SIGNAL_HELPER="$test_signal_helper"
export KAST_BENCHMARK_TEST_MODE=true
export KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER=true
export KAST_RELEASE_COMMAND_TERM_GRACE_MILLIS=100
export KAST_RELEASE_COMMAND_KILL_GRACE_MILLIS=500
declare -F compare_benchmark_evidence >/dev/null \
  || die 'benchmark does not expose its evidence comparator'
declare -F run_supervised_command >/dev/null \
  || die 'benchmark does not expose one bounded external-command authority'
declare -F benchmark_progress_sample >/dev/null \
  || die 'benchmark does not expose an initialized optional progress sampler'
benchmark_progress_sample OPTIONAL_SOURCE_PROBE
declare -F summarize_runtime_retry_transitions >/dev/null \
  || die 'benchmark does not expose retry-transition aggregation'
declare -F run_strict >/dev/null \
  || die 'benchmark does not expose strict command execution'

set +e
"$BENCHMARK_PYTHON_BIN" "$supervisor" run \
  --deadline-monotonic-ms "$(( $(monotonic_millis) + 2000 ))" \
  --operation event-persistence-failure \
  --term-grace-millis 100 \
  --kill-grace-millis 500 \
  --event-log / \
  --test-sourced \
  --test-mode \
  --test-allow-signal-helper \
  --test-signal-helper "$test_signal_helper" \
  -- /usr/bin/true \
  >"$scratch/event-persistence.stdout" \
  2>"$scratch/event-persistence.stderr"
event_persistence_result=$?
set -e
[[ "$event_persistence_result" -eq 125 ]] \
  || die 'event persistence failure did not override successful child exit'
grep -Fq 'could not persist supervisor evidence' "$scratch/event-persistence.stderr" \
  || die 'event persistence failure lacked an exact diagnostic'

result_parent_file="$scratch/result-parent-file"
touch "$result_parent_file"
set +e
"$BENCHMARK_PYTHON_BIN" "$supervisor" run \
  --deadline-monotonic-ms "$(( $(monotonic_millis) + 2000 ))" \
  --operation result-persistence-failure \
  --term-grace-millis 100 \
  --kill-grace-millis 500 \
  --event-log "$scratch/result-persistence-events.jsonl" \
  --result-json "$result_parent_file/result.json" \
  --test-sourced \
  --test-mode \
  --test-allow-signal-helper \
  --test-signal-helper "$test_signal_helper" \
  -- /usr/bin/true \
  >"$scratch/result-persistence.stdout" \
  2>"$scratch/result-persistence.stderr"
result_persistence_result=$?
set -e
[[ "$result_persistence_result" -eq 125 ]] \
  || die 'result persistence failure did not override successful child exit'
python3 - "$scratch/result-persistence-events.jsonl" <<'PY'
import json
import sys
from pathlib import Path

events = [
    json.loads(line)
    for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    if line
]
assert len(events) == 1, events
assert events[0]["outcome"] == "SUPERVISION_FAILED", events
assert events[0]["exitCode"] == 125, events
assert "could not persist result evidence" in events[0]["detail"], events
PY

capture_gap_result="$scratch/capture-gap.json"
python3 "$supervisor" self-test-capture-gap \
  --delay-millis 100 \
  --result-json "$capture_gap_result"
python3 - "$capture_gap_result" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["schemaVersion"] == 1, payload
assert payload["type"] == "KAST_BENCHMARK_CAPTURE_GAP_PROOF", payload
assert payload["directChildRemainedUnreapedUntilCapture"] is True, payload
assert payload["replacementWasClaimed"] is False, payload
assert payload["replacementWasSignaled"] is False, payload
if sys.platform == "linux":
    assert payload["childExitedBeforeCapture"] is True, payload
    assert payload["pidfdOpenedBeforeReap"] is True, payload
PY

python3 - "$supervisor" <<'PY'
import importlib.util
import sys
from pathlib import Path

path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("benchmark_command_supervisor", path)
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

assert module.CLOSURE_PROOF_RESERVE_MILLIS == 1_000
assert module.COMMAND_ADMISSION_RESERVE_MILLIS == 1_000
assert module.required_cleanup_reserve_millis(
    term_grace_millis=5_000,
    kill_grace_millis=2_000,
) == 8_000
assert module.required_admission_budget_millis(
    term_grace_millis=5_000,
    kill_grace_millis=2_000,
) == 9_000
assert module.classify_pre_spawn_timeout(
    remaining_budget_millis=0,
    required_cleanup_millis=8_000,
    required_admission_millis=9_000,
) is module.PreSpawnTimeoutReason.DEADLINE_EXPIRED
assert module.classify_pre_spawn_timeout(
    remaining_budget_millis=8_000,
    required_cleanup_millis=8_000,
    required_admission_millis=9_000,
) is module.PreSpawnTimeoutReason.INSUFFICIENT_CLEANUP_RESERVE
assert module.classify_pre_spawn_timeout(
    remaining_budget_millis=8_001,
    required_cleanup_millis=8_000,
    required_admission_millis=9_000,
) is module.PreSpawnTimeoutReason.INSUFFICIENT_ADMISSION_BUDGET
assert module.classify_pre_spawn_timeout(
    remaining_budget_millis=9_000,
    required_cleanup_millis=8_000,
    required_admission_millis=9_000,
) is module.PreSpawnTimeoutReason.INSUFFICIENT_ADMISSION_BUDGET
assert module.classify_pre_spawn_timeout(
    remaining_budget_millis=9_001,
    required_cleanup_millis=8_000,
    required_admission_millis=9_000,
) is None
PY

hanging_surface="$scratch/hanging-surface.sh"
cat >"$hanging_surface" <<'SH'
#!/usr/bin/env bash
trap '' TERM
while :; do :; done
SH
chmod 700 "$hanging_surface"
benchmark_command_events_file="$scratch/supervised-command-events.jsonl"
expired_command_event="$scratch/expired-command-event.json"
expired_command_log="$scratch/expired-command-events.jsonl"
expired_command_deadline=$(( $(monotonic_millis) - 1 ))
set +e
"$BENCHMARK_PYTHON_BIN" "$supervisor" run \
  --deadline-monotonic-ms "$expired_command_deadline" \
  --operation pre-spawn-timeout \
  --event-log "$expired_command_log" \
  --result-json "$expired_command_event" \
  --test-sourced \
  --test-mode \
  --test-allow-signal-helper \
  --test-signal-helper "$test_signal_helper" \
  -- /usr/bin/true
expired_command_result=$?
set -e
[[ "$expired_command_result" -eq 124 ]] \
  || die "expired pre-spawn deadline returned $expired_command_result instead of timeout"
python3 - "$expired_command_event" "$expired_command_log" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
events = [
    json.loads(line)
    for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    if line
]
assert events == [payload], events
assert payload["type"] == "KAST_BENCHMARK_SUPERVISED_COMMAND", payload
assert payload["operation"] == "pre-spawn-timeout", payload
assert payload["outcome"] == "TIMED_OUT", payload
assert payload["exitCode"] == 124, payload
assert payload["timeoutPhase"] == "PRE_SPAWN", payload
assert payload["timeoutReason"] == "DEADLINE_EXPIRED", payload
assert payload["detail"] == "command deadline expired before spawn", payload
assert payload["remainingBudgetMillis"] == 0, payload
assert payload["requiredCleanupReserveMillis"] == 8_000, payload
assert payload["termGraceMillis"] == 5_000, payload
assert payload["killGraceMillis"] == 2_000, payload
assert payload["closureProofReserveMillis"] == 1_000, payload
assert payload["admissionReserveMillis"] == 1_000, payload
assert payload["requiredAdmissionBudgetMillis"] == 9_000, payload
assert payload["pid"] is None, payload
assert payload["captureIdentity"] is None, payload
assert payload["pidfdOpenedBeforeWait"] is False, payload
assert payload["termination"] == {"termSent": False, "killSent": False}, payload
assert payload["processGroupClosure"] == {
    "required": False,
    "proven": None,
    "pidfdsRetained": False,
    "capturedProcesses": [],
    "remainingProcesses": [],
    "recapturePasses": 0,
    "stableConfirmationPasses": 0,
}, payload
assert payload["startedAtMonotonicMillis"] >= payload["deadlineMonotonicMillis"], payload
assert payload["finishedAtMonotonicMillis"] >= payload["startedAtMonotonicMillis"], payload
PY

for bounded_surface in \
  setup config graph-refresh graph-summary process-enumeration resource-disk-sample \
  teardown-enumeration teardown-signaling worktree-cleanup finalization; do
  bounded_deadline=$(( $(monotonic_millis) + 3500 ))
  set +e
  KAST_BENCHMARK_TEST_SIGNAL_HELPER="$test_signal_helper" \
    KAST_BENCHMARK_TEST_MODE=true \
    KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER=true \
    KAST_RELEASE_COMMAND_TERM_GRACE_MILLIS=100 \
    KAST_RELEASE_COMMAND_KILL_GRACE_MILLIS=500 \
    run_supervised_command \
      "$bounded_deadline" "$bounded_surface" "$hanging_surface" \
      >/dev/null 2>"$scratch/$bounded_surface.stderr"
  bounded_result=$?
  set -e
  [[ "$bounded_result" -eq 124 ]] \
    || die "$bounded_surface hang did not return the typed timeout exit: $bounded_result"
done
python3 - "$benchmark_command_events_file" <<'PY'
import json
import sys
from pathlib import Path

events = [
    json.loads(line)
    for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    if line
]
expected = {
    "setup", "config", "graph-refresh", "graph-summary", "process-enumeration",
    "resource-disk-sample", "teardown-enumeration", "teardown-signaling",
    "worktree-cleanup", "finalization",
}
assert {event["operation"] for event in events} == expected, events
for event in events:
    assert event["schemaVersion"] == 1, event
    assert event["type"] == "KAST_BENCHMARK_SUPERVISED_COMMAND", event
    assert event["outcome"] == "TIMED_OUT", event
    assert event["exitCode"] == 124, event
    assert event["termination"]["termSent"] is True, event
    assert event["termination"]["killSent"] is True, event
    assert "timeoutPhase" not in event, event
    assert event["finishedAtMonotonicMillis"] <= event["deadlineMonotonicMillis"], event
PY

printf -v emergency_digest '%064d' 0
KAST_RELEASE_COMMAND_TERM_GRACE_MILLIS=100 \
KAST_RELEASE_COMMAND_KILL_GRACE_MILLIS=500 \
write_typed_emergency_role_evidence \
  "$scratch/emergency-role-evidence.json" \
  stable \
  "$scratch/kast-linux-x64-v0.21.6.tar.gz" \
  "$emergency_digest" \
  124 \
  124 \
  "$benchmark_command_events_file" \
  "$(( $(monotonic_millis) + 3000 ))"
python3 - "$scratch/emergency-role-evidence.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["schemaVersion"] == 1, payload
assert payload["role"] == "stable", payload
assert payload["correctness"] is False, payload
diagnostic = payload["diagnostic"]
assert diagnostic["outcome"] == "FINALIZATION_FAILED", payload
assert diagnostic["roleExitCode"] == 124, payload
assert diagnostic["finalizationExitCode"] == 124, payload
timed_out = {
    event["operation"]
    for event in diagnostic["supervisedCommands"]
    if event.get("outcome") == "TIMED_OUT"
}
assert {
    "setup", "config", "graph-refresh", "graph-summary",
    "process-enumeration", "resource-disk-sample", "teardown-enumeration",
    "teardown-signaling", "worktree-cleanup", "finalization",
} <= timed_out, payload
PY

strict_probe_output="$scratch/strict-probe-output"
strict_failure_probe() {
  false
  touch "$strict_probe_output"
}
set +e
run_strict strict_failure_probe
strict_probe_result=$?
set -e
[[ "$strict_probe_result" -ne 0 && ! -e "$strict_probe_output" ]] \
  || die 'strict execution allowed a failed command to continue'

if [[ "$(uname -s)" == Linux ]]; then
  linux_process_is_live() {
    local pid="$1" stat remainder state
    [[ -r "/proc/$pid/stat" ]] || return 1
    stat="$(<"/proc/$pid/stat")"
    remainder="${stat#*) }"
    state="${remainder%% *}"
    [[ "$state" != Z && "$state" != X ]]
  }

  wait_for_pid_file() {
    local path="$1"
    for _ in {1..40}; do
      [[ -s "$path" ]] && return 0
      sleep 0.05
    done
    return 1
  }

  late_group_child="$scratch/late-group-child.pid"
  late_group_snapshot="$scratch/late-group-snapshot"
  late_group_release="$scratch/late-group-release"
  late_group_fixture="$scratch/late-group-fixture.sh"
  cat >"$late_group_fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
child_file="$1"
snapshot_file="$2"
release_file="$3"
on_term() {
  trap - TERM
  while [[ ! -e "$snapshot_file" ]]; do sleep 0.01; done
  (trap '' TERM; while :; do :; done) &
  printf '%s\n' "$!" >"$child_file"
  : >"$release_file"
  exit 0
}
trap on_term TERM
while :; do sleep 1; done
SH
  chmod 700 "$late_group_fixture"
  late_group_deadline=$(( $(monotonic_millis) + 4000 ))
  set +e
  "$BENCHMARK_PYTHON_BIN" "$supervisor" run \
    --deadline-monotonic-ms "$late_group_deadline" \
    --operation late-process-group-closure \
    --term-grace-millis 200 \
    --kill-grace-millis 1000 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-closure-snapshot-file "$late_group_snapshot" \
    --test-closure-release-file "$late_group_release" \
    --test-closure-barrier-phase after-signal \
    --result-json "$scratch/late-group-result.json" \
    -- "$late_group_fixture" "$late_group_child" \
      "$late_group_snapshot" "$late_group_release"
  late_group_result=$?
  set -e
  [[ "$late_group_result" -eq 124 ]] \
    || die "late process-group fixture returned $late_group_result instead of timeout"
  wait_for_pid_file "$late_group_child" \
    || die 'TERM handler did not publish its late process-group child'
  read -r late_group_pid <"$late_group_child"
  late_group_survived=false
  if linux_process_is_live "$late_group_pid"; then
    late_group_survived=true
    kill -KILL "$late_group_pid" 2>/dev/null || true
  fi
  [[ "$late_group_survived" == false ]] \
    || die 'supervised timeout returned while a late process-group descendant remained live'
  python3 - "$scratch/late-group-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
closure = payload["processGroupClosure"]
assert closure["required"] is True, payload
assert closure["proven"] is True, payload
assert closure["pidfdsRetained"] is True, payload
assert closure["remainingProcesses"] == [], payload
assert closure["recapturePasses"] >= 1, payload
assert closure["stableConfirmationPasses"] >= 2, payload
assert payload["finishedAtMonotonicMillis"] <= payload["deadlineMonotonicMillis"], payload
PY

  normal_group_child="$scratch/normal-group-child.pid"
  normal_group_fixture="$scratch/normal-group-fixture.sh"
  cat >"$normal_group_fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
(trap '' TERM; while :; do :; done) &
printf '%s\n' "$!" >"$1"
exit 0
SH
  chmod 700 "$normal_group_fixture"
  "$BENCHMARK_PYTHON_BIN" "$supervisor" run \
    --deadline-monotonic-ms "$(( $(monotonic_millis) + 4000 ))" \
    --operation normal-success-background \
    --term-grace-millis 100 \
    --kill-grace-millis 500 \
    --result-json "$scratch/normal-group-result.json" \
    -- "$normal_group_fixture" "$normal_group_child"
  wait_for_pid_file "$normal_group_child" \
    || die 'normal-success fixture did not publish its background child'
  read -r normal_group_pid <"$normal_group_child"
  normal_group_was_live=false
  if linux_process_is_live "$normal_group_pid"; then
    normal_group_was_live=true
    kill -KILL "$normal_group_pid" 2>/dev/null || true
  fi
  [[ "$normal_group_was_live" == true ]] \
    || die 'supervisor killed a normal-success background descendant'
  python3 - "$scratch/normal-group-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["outcome"] == "SUCCEEDED", payload
assert payload["processGroupClosure"]["required"] is False, payload
PY

  pidfd_failure_child="$scratch/pidfd-failure-child.pid"
  pidfd_failure_fixture="$scratch/pidfd-failure-fixture.sh"
  cat >"$pidfd_failure_fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
(trap '' TERM; while :; do :; done) &
printf '%s\n' "$!" >"$1"
while :; do sleep 1; done
SH
  chmod 700 "$pidfd_failure_fixture"
  set +e
  "$BENCHMARK_PYTHON_BIN" "$supervisor" run \
    --deadline-monotonic-ms "$(( $(monotonic_millis) + 4000 ))" \
    --operation pidfd-open-failure \
    --term-grace-millis 200 \
    --kill-grace-millis 1000 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-fail-pidfd-open \
    --test-pidfd-open-failure-ready-file "$pidfd_failure_child" \
    --result-json "$scratch/pidfd-failure-result.json" \
    -- "$pidfd_failure_fixture" "$pidfd_failure_child"
  pidfd_failure_result=$?
  set -e
  [[ "$pidfd_failure_result" -eq 125 ]] \
    || die "pidfd-open fault returned $pidfd_failure_result instead of SUPERVISION_FAILED"
  wait_for_pid_file "$pidfd_failure_child" \
    || die 'pidfd-open fault fixture did not publish its group child'
  read -r pidfd_failure_pid <"$pidfd_failure_child"
  pidfd_failure_survived=false
  if linux_process_is_live "$pidfd_failure_pid"; then
    pidfd_failure_survived=true
    kill -KILL "$pidfd_failure_pid" 2>/dev/null || true
  fi
  [[ "$pidfd_failure_survived" == false ]] \
    || die 'pidfd-open failure returned while a stable-group descendant remained live'
  python3 - "$scratch/pidfd-failure-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["outcome"] == "SUPERVISION_FAILED", payload
assert payload["exitCode"] == 125, payload
assert payload["processGroupClosure"]["required"] is True, payload
assert payload["processGroupClosure"]["proven"] is True, payload
assert payload["processGroupClosure"]["remainingProcesses"] == [], payload
PY

  group_capture_error_child="$scratch/group-capture-error-child.pid"
  group_capture_error_fixture="$scratch/group-capture-error-fixture.sh"
  cat >"$group_capture_error_fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
(trap '' TERM; while :; do :; done) &
printf '%s\n' "$!" >"$1"
while :; do sleep 1; done
SH
  chmod 700 "$group_capture_error_fixture"
  set +e
  "$BENCHMARK_PYTHON_BIN" "$supervisor" run \
    --deadline-monotonic-ms "$(( $(monotonic_millis) + 3000 ))" \
    --operation group-capture-emfile \
    --term-grace-millis 200 \
    --kill-grace-millis 500 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-capture-pidfd-error EMFILE \
    --test-capture-pid-file "$group_capture_error_child" \
    --result-json "$scratch/group-capture-error-result.json" \
    -- "$group_capture_error_fixture" "$group_capture_error_child"
  group_capture_error_result=$?
  set -e
  [[ "$group_capture_error_result" -eq 125 ]] \
    || die 'group-member pidfd EMFILE did not fail supervision closed'
  wait_for_pid_file "$group_capture_error_child" \
    || die 'group-member pidfd fault fixture did not publish its child'
  read -r group_capture_error_pid <"$group_capture_error_child"
  if linux_process_is_live "$group_capture_error_pid"; then
    kill -KILL "$group_capture_error_pid" 2>/dev/null || true
    die 'group-member pidfd EMFILE left the stable process group live'
  fi
  python3 - "$scratch/group-capture-error-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
closure = payload["processGroupClosure"]
assert payload["outcome"] == "SUPERVISION_FAILED", payload
assert payload["exitCode"] == 125, payload
assert closure["proven"] is False, payload
assert closure["enumerationError"]["code"] == "EMFILE", payload
PY

  late_marker_child="$scratch/late-marker-child.pid"
  late_marker_snapshot="$scratch/late-marker-snapshot"
  late_marker_release="$scratch/late-marker-release"
  late_marker_fixture="$scratch/late-marker-fixture.sh"
  cat >"$late_marker_fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
child_file="$1"
snapshot_file="$2"
release_file="$3"
on_term() {
  trap - TERM
  while [[ ! -e "$snapshot_file" ]]; do sleep 0.01; done
  (trap '' TERM; while :; do :; done) &
  printf '%s\n' "$!" >"$child_file"
  : >"$release_file"
  exit 0
}
trap on_term TERM
while :; do sleep 1; done
SH
  chmod 700 "$late_marker_fixture"
  late_marker="release-benchmark-late-marker-$$"
  env KAST_BENCHMARK_RUN_ID="$late_marker" \
    "$late_marker_fixture" "$late_marker_child" \
      "$late_marker_snapshot" "$late_marker_release" &
  late_marker_parent=$!
  late_marker_deadline=$(( $(monotonic_millis) + 5000 ))
  "$BENCHMARK_PYTHON_BIN" "$supervisor" terminate-owned \
    --deadline-monotonic-ms "$late_marker_deadline" \
    --marker "$late_marker" \
    --event-log "$scratch/late-marker-events.jsonl" \
    --result-json "$scratch/late-marker-result.json" \
    --term-grace-millis 300 \
    --kill-grace-millis 1000 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-closure-snapshot-file "$late_marker_snapshot" \
    --test-closure-release-file "$late_marker_release" \
    --test-closure-barrier-phase after-signal
  wait "$late_marker_parent" 2>/dev/null || true
  wait_for_pid_file "$late_marker_child" \
    || die 'marked TERM handler did not publish its late inherited-marker child'
  read -r late_marker_pid <"$late_marker_child"
  late_marker_survived=false
  if linux_process_is_live "$late_marker_pid"; then
    late_marker_survived=true
    kill -KILL "$late_marker_pid" 2>/dev/null || true
  fi
  [[ "$late_marker_survived" == false ]] \
    || die 'marked teardown returned before reaching an empty recapture fixed point'
  python3 - "$scratch/late-marker-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
closure = payload["ownershipClosure"]
assert payload["outcome"] == "SUCCEEDED", payload
assert payload["processes"] == [], payload
assert closure["proven"] is True, payload
assert closure["remainingProcesses"] == [], payload
assert closure["recapturePasses"] >= 2, payload
assert closure["stableConfirmationPasses"] >= 2, payload
PY

  empty_marker="release-benchmark-empty-marker-$$"
  empty_marker_snapshot="$scratch/empty-marker-snapshot"
  empty_marker_release="$scratch/empty-marker-release"
  empty_marker_child="$scratch/empty-marker-child.pid"
  empty_marker_spawner="$scratch/empty-marker-spawner.sh"
  cat >"$empty_marker_spawner" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
marker="$1"
snapshot_file="$2"
release_file="$3"
child_file="$4"
while [[ ! -e "$snapshot_file" ]]; do sleep 0.01; done
env KAST_BENCHMARK_RUN_ID="$marker" bash -c '
  printf "%s\n" "$$" >"$1"
  : >"$2"
  trap "" TERM
  while :; do :; done
' _ "$child_file" "$release_file"
SH
  chmod 700 "$empty_marker_spawner"
  "$empty_marker_spawner" \
    "$empty_marker" "$empty_marker_snapshot" "$empty_marker_release" \
    "$empty_marker_child" &
  empty_marker_spawner_pid=$!
  "$BENCHMARK_PYTHON_BIN" "$supervisor" terminate-owned \
    --deadline-monotonic-ms "$(( $(monotonic_millis) + 5000 ))" \
    --marker "$empty_marker" \
    --result-json "$scratch/empty-marker-result.json" \
    --term-grace-millis 300 \
    --kill-grace-millis 1000 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-closure-snapshot-file "$empty_marker_snapshot" \
    --test-closure-release-file "$empty_marker_release" \
    --test-closure-barrier-phase initial
  wait "$empty_marker_spawner_pid" 2>/dev/null || true
  wait_for_pid_file "$empty_marker_child" \
    || die 'empty-scan fixture did not publish its marked process'
  read -r empty_marker_pid <"$empty_marker_child"
  empty_marker_survived=false
  if linux_process_is_live "$empty_marker_pid"; then
    empty_marker_survived=true
    kill -KILL "$empty_marker_pid" 2>/dev/null || true
  fi
  [[ "$empty_marker_survived" == false ]] \
    || die 'initially empty marker scan returned before stable confirmation'
  python3 - "$scratch/empty-marker-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
closure = payload["ownershipClosure"]
assert payload["outcome"] == "SUCCEEDED", payload
assert closure["proven"] is True, payload
assert closure["remainingProcesses"] == [], payload
assert closure["stableConfirmationPasses"] >= 2, payload
PY

  marker_capture_error="release-benchmark-marker-eperm-$$"
  marker_capture_error_pid_file="$scratch/marker-capture-error.pid"
  env KAST_BENCHMARK_RUN_ID="$marker_capture_error" bash -c '
    printf "%s\n" "$$" >"$1"
    while :; do sleep 1; done
  ' _ "$marker_capture_error_pid_file" &
  marker_capture_error_parent=$!
  wait_for_pid_file "$marker_capture_error_pid_file" \
    || die 'marker pidfd fault fixture did not publish its PID'
  set +e
  "$BENCHMARK_PYTHON_BIN" "$supervisor" terminate-owned \
    --deadline-monotonic-ms "$(( $(monotonic_millis) + 2000 ))" \
    --marker "$marker_capture_error" \
    --result-json "$scratch/marker-capture-error-result.json" \
    --term-grace-millis 200 \
    --kill-grace-millis 500 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-capture-pidfd-error EPERM \
    --test-capture-pid-file "$marker_capture_error_pid_file"
  marker_capture_error_result=$?
  set -e
  [[ "$marker_capture_error_result" -eq 125 ]] \
    || die 'marked-process pidfd EPERM did not fail supervision closed'
  kill -KILL "$marker_capture_error_parent" 2>/dev/null || true
  wait "$marker_capture_error_parent" 2>/dev/null || true
  python3 - "$scratch/marker-capture-error-result.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["outcome"] == "SUPERVISION_FAILED", payload
assert payload["ownershipClosure"]["proven"] is False, payload
assert payload["ownershipClosure"]["enumerationError"]["code"] == "EPERM", payload
PY

  parent_reuse_marker="release-benchmark-parent-reuse-$$"
  parent_reuse_parent_file="$scratch/parent-reuse-parent.pid"
  parent_reuse_child_file="$scratch/parent-reuse-child.pid"
  parent_reuse_fixture="$scratch/parent-reuse-fixture.sh"
  cat >"$parent_reuse_fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$$" >"$1"
env -u KAST_BENCHMARK_RUN_ID bash -c '
  printf "%s\n" "$$" >"$1"
  trap "" TERM
  while :; do :; done
' _ "$2" &
while :; do sleep 1; done
SH
  chmod 700 "$parent_reuse_fixture"
  env KAST_BENCHMARK_RUN_ID="$parent_reuse_marker" \
    "$parent_reuse_fixture" "$parent_reuse_parent_file" \
      "$parent_reuse_child_file" &
  parent_reuse_parent=$!
  wait_for_pid_file "$parent_reuse_parent_file" \
    || die 'parent-reuse fixture did not publish its parent PID'
  wait_for_pid_file "$parent_reuse_child_file" \
    || die 'parent-reuse fixture did not publish its child PID'
  "$BENCHMARK_PYTHON_BIN" "$supervisor" terminate-owned \
    --deadline-monotonic-ms "$(( $(monotonic_millis) + 3000 ))" \
    --marker "$parent_reuse_marker" \
    --result-json "$scratch/parent-reuse-result.json" \
    --term-grace-millis 200 \
    --kill-grace-millis 500 \
    --test-sourced \
    --test-mode \
    --test-allow-signal-helper \
    --test-signal-helper "$test_signal_helper" \
    --test-parent-identity-mismatch-pid-file "$parent_reuse_parent_file"
  wait "$parent_reuse_parent" 2>/dev/null || true
  read -r parent_reuse_child <"$parent_reuse_child_file"
  parent_reuse_child_was_live=false
  if linux_process_is_live "$parent_reuse_child"; then
    parent_reuse_child_was_live=true
    kill -KILL "$parent_reuse_child" 2>/dev/null || true
  fi
  [[ "$parent_reuse_child_was_live" == true ]] \
    || die 'dead/reused numeric parent authority captured an unmarked replacement child'
  python3 - "$scratch/parent-reuse-result.json" "$parent_reuse_child" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
child_pid = int(sys.argv[2])
assert payload["outcome"] == "SUCCEEDED", payload
assert all(
    process["pid"] != child_pid
    for process in payload["ownershipClosure"]["capturedProcesses"]
), payload
PY

  expired_ownership_deadline=$(( $(monotonic_millis) - 1 ))
  for ownership_mode in list-owned terminate-owned; do
    expired_result_file="$scratch/expired-$ownership_mode.json"
    expired_event_file="$scratch/expired-$ownership_mode.jsonl"
    set +e
    if [[ "$ownership_mode" == list-owned ]]; then
      "$BENCHMARK_PYTHON_BIN" "$supervisor" list-owned \
        --deadline-monotonic-ms "$expired_ownership_deadline" \
        --marker "expired-contract-$$" \
        --event-log "$expired_event_file" \
        --result-json "$expired_result_file" \
        >/dev/null 2>&1
    else
      "$BENCHMARK_PYTHON_BIN" "$supervisor" terminate-owned \
        --deadline-monotonic-ms "$expired_ownership_deadline" \
        --marker "expired-contract-$$" \
        --event-log "$expired_event_file" \
        --result-json "$expired_result_file" \
        --term-grace-millis 100 \
        --kill-grace-millis 100 \
        >/dev/null 2>&1
    fi
    expired_ownership_result=$?
    set -e
    [[ "$expired_ownership_result" -eq 124 ]] \
      || die "$ownership_mode did not enforce its own absolute deadline"
    python3 - "$expired_result_file" "$expired_event_file" <<'PY'
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
events = [
    json.loads(line)
    for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    if line
]
assert result["schemaVersion"] == 1, result
assert result["type"] == "KAST_BENCHMARK_OWNED_PROCESSES", result
assert result["outcome"] == "TIMED_OUT", result
assert events == [result], (events, result)
PY
  done

  process_marker="release-benchmark-contract-$$"
  env KAST_BENCHMARK_RUN_ID="$process_marker" \
    bash -c 'sleep 30 & wait' &
  process_parent=$!
  sleep 0.1
  owned_deadline=$(( $(monotonic_millis) + 3000 ))
  owned_process_ids="$("$BENCHMARK_PYTHON_BIN" "$supervisor" list-owned \
      --deadline-monotonic-ms "$owned_deadline" \
      --marker "$process_marker" \
      --event-log "$benchmark_command_events_file" \
      --result-json "$scratch/owned-processes.json" \
      --print-pids)"
  [[ "$(wc -w <<<"$owned_process_ids" | tr -d ' ')" -ge 2 ]] \
    || die 'pidfd-held ownership discovery omitted a marked descendant'
  teardown_deadline=$(( $(monotonic_millis) + 3000 ))
  "$BENCHMARK_PYTHON_BIN" "$supervisor" terminate-owned \
      --deadline-monotonic-ms "$teardown_deadline" \
      --marker "$process_marker" \
      --event-log "$benchmark_command_events_file" \
      --result-json "$scratch/owned-teardown.json" \
      --term-grace-millis 200 \
      --kill-grace-millis 500
  wait "$process_parent" 2>/dev/null || true
  if kill -0 "$process_parent" >/dev/null 2>&1; then
    die 'pidfd-held ownership teardown left the marked process alive'
  fi
fi

printf '%s\n' '{"selected":{"pidAlive":true,"reachable":true,"runtimeStatus":{"state":"INDEXING"}}}' \
  >"$scratch/admitted-status.json"
runtime_is_durably_admitted "$scratch/admitted-status.json" \
  || die 'INDEXING runtime was not recognized as durably admitted'
printf '%s\n' '{"ok":true,"result":{"runtime":{"state":"STARTING","ownership":{"assessment":"OWNED"}}}}' \
  >"$scratch/service-starting-status.json"
runtime_is_durably_admitted "$scratch/service-starting-status.json" \
  || die 'service-owned STARTING runtime was not recognized as durably admitted'
printf '%s\n' '{"ok":true,"result":{"runtime":{"state":"STARTING","ownership":{"assessment":"CONFLICT"}}}}' \
  >"$scratch/ambiguous-starting-status.json"
if runtime_is_durably_admitted "$scratch/ambiguous-starting-status.json"; then
  die 'ambiguous STARTING runtime was recognized as durably admitted'
fi
printf '%s\n' '{"selected":{"pidAlive":true,"reachable":false,"runtimeStatus":{"state":"INDEXING"}}}' \
  >"$scratch/unreachable-status.json"
if runtime_is_durably_admitted "$scratch/unreachable-status.json"; then
  die 'unreachable runtime was recognized as durably admitted'
fi

graph_refresh_attempt_file="$scratch/graph-refresh-attempts"
graph_refresh_scenario=generation-conflict-once
fake_kastctl="$scratch/fake-kastctl.sh"
cat >"$fake_kastctl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

attempt=0
  [[ ! -f "$graph_refresh_attempt_file" ]] \
    || read -r attempt <"$graph_refresh_attempt_file"
  attempt=$((attempt + 1))
  printf '%s\n' "$attempt" >"$graph_refresh_attempt_file"
  if [[ "$graph_refresh_scenario" == hang ]]; then
    while :; do :; done
  fi
  if [[ "$graph_refresh_scenario" == workspace-partial-once ]]; then
    if [[ "$attempt" -eq 1 ]]; then
      printf '%s\n' '{"ok":true,"result":{"cardinality":{"type":"KNOWN_MINIMUM","knownMinimumCount":98}}}'
    else
      printf '%s\n' '{"ok":true,"result":{"cardinality":{"type":"EXACT","totalCount":98}}}'
    fi
    exit 0
  fi
  if [[ "$graph_refresh_scenario" == workspace-empty ]]; then
    printf '%s\n' '{"ok":true,"result":{"cardinality":{"type":"EXACT","totalCount":0}}}'
    exit 0
  fi
  if [[ "$graph_refresh_scenario" == workspace-not-ready-once && "$attempt" -eq 1 ]]; then
    printf '%s\n' '{"ok":false,"error":{"code":"RUNTIME_NOT_READY"}}'
    exit 1
  fi
  if [[ "$graph_refresh_scenario" == workspace-not-ready-once ]]; then
    printf '%s\n' '{"ok":true,"result":{"cardinality":{"type":"EXACT","totalCount":98}}}'
    exit 0
  fi
  if [[ "$graph_refresh_scenario" == generation-conflict-once && "$attempt" -eq 1 ]]; then
    printf '%s\n' '{"ok":false,"error":{"code":"CONFLICT","details":{"rpcError":{"data":{"details":{"expectedGeneration":"1","actualGeneration":"2"}}}}}}'
    exit 1
  fi
  if [[ "$graph_refresh_scenario" == other-conflict ]]; then
    printf '%s\n' '{"ok":false,"error":{"code":"CONFLICT","details":{"rpcError":{"data":{"details":{"expectedPsiGeneration":"1","actualPsiGeneration":"2"}}}}}}'
    exit 1
  fi
  printf '%s\n' '{"ok":true,"result":{"generation":2}}'
SH
chmod 700 "$fake_kastctl"
export graph_refresh_attempt_file graph_refresh_scenario
active_kastctl="$fake_kastctl"
benchmark_user_dir="$scratch/fake-user"
benchmark_kast_home="$scratch/fake-kast-home"
benchmark_cache_dir="$scratch/fake-kast-cache"
benchmark_gradle_dir="$scratch/fake-gradle"
benchmark_run_marker="release-benchmark-contract-fake-$$"
benchmark_role=contract
name=contract-repository
mkdir -p \
  "$benchmark_user_dir" "$benchmark_kast_home" "$benchmark_cache_dir" \
  "$benchmark_gradle_dir"

declare -F run_kastctl_with_cold_budget >/dev/null \
  || die 'benchmark does not expose the shared cold-phase CLI budget'
for hanging_call in status workspace; do
  graph_refresh_scenario=hang
  [[ ! -e "$graph_refresh_attempt_file" ]] \
    || find "$graph_refresh_attempt_file" -delete
  hanging_deadline=$(( $(monotonic_millis) + 500 ))
  hanging_started="$(monotonic_millis)"
  set +e
  if [[ "$hanging_call" == status ]]; then
      KAST_BENCHMARK_TEST_SIGNAL_HELPER="$test_signal_helper" \
      KAST_BENCHMARK_TEST_MODE=true \
      KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER=true \
      KAST_RELEASE_COMMAND_TERM_GRACE_MILLIS=100 \
      KAST_RELEASE_COMMAND_KILL_GRACE_MILLIS=500 \
      benchmark_cold_budget_active=true \
      benchmark_cold_deadline_monotonic_ms="$hanging_deadline" \
      run_kastctl_with_cold_budget developer runtime status \
      >"$scratch/hanging-status.json" 2>/dev/null
  else
      KAST_BENCHMARK_TEST_SIGNAL_HELPER="$test_signal_helper" \
      KAST_BENCHMARK_TEST_MODE=true \
      KAST_BENCHMARK_TEST_ALLOW_SIGNAL_HELPER=true \
      KAST_RELEASE_COMMAND_TERM_GRACE_MILLIS=100 \
      KAST_RELEASE_COMMAND_KILL_GRACE_MILLIS=500 \
      benchmark_cold_budget_active=true \
      benchmark_cold_deadline_monotonic_ms="$hanging_deadline" \
      KAST_RELEASE_INDEX_POLL_SECONDS=0 \
      wait_for_exact_workspace_index \
        "$scratch/hanging-workspace.json" 10000 agent workspace-files --count \
      2>/dev/null
  fi
  hanging_result=$?
  set -e
  hanging_elapsed=$(( $(monotonic_millis) - hanging_started ))
  [[ "$hanging_result" -ne 0 ]] \
    || die "hanging $hanging_call call escaped the cold deadline"
  ((hanging_elapsed < 3000)) \
    || die "hanging $hanging_call call exceeded bounded TERM/KILL teardown"
done

graph_refresh_scenario=generation-conflict-once
run_generation_bound_graph_refresh "$scratch/graph-refresh.json" \
  agent graph --operation refresh
[[ "$(cat "$graph_refresh_attempt_file")" == 2 ]] \
  || die 'generation conflict was not retried exactly once'
graph_refresh_scenario=other-conflict
find "$graph_refresh_attempt_file" -delete
if run_generation_bound_graph_refresh "$scratch/other-conflict.json" \
    agent graph --operation refresh 2>/dev/null; then
  die 'non-generation conflict unexpectedly succeeded'
fi
[[ "$(cat "$graph_refresh_attempt_file")" == 1 ]] \
  || die 'non-generation conflict was retried'

graph_refresh_scenario=workspace-partial-once
find "$graph_refresh_attempt_file" -delete
KAST_RELEASE_INDEX_POLL_SECONDS=0 \
  wait_for_exact_workspace_index "$scratch/workspace-files.json" 10000 \
  agent workspace-files --count
[[ "$(cat "$graph_refresh_attempt_file")" == 2 ]] \
  || die 'partial workspace evidence was not polled to exactness'
graph_refresh_scenario=workspace-empty
find "$graph_refresh_attempt_file" -delete
if KAST_RELEASE_INDEX_POLL_SECONDS=0 \
    wait_for_exact_workspace_index "$scratch/workspace-empty.json" 10000 \
    agent workspace-files --count 2>/dev/null; then
  die 'exact empty workspace unexpectedly succeeded'
fi
[[ "$(cat "$graph_refresh_attempt_file")" == 1 ]] \
  || die 'exact empty workspace was retried'

graph_refresh_scenario=workspace-not-ready-once
find "$graph_refresh_attempt_file" -delete
KAST_RELEASE_INDEX_POLL_SECONDS=0 \
  wait_for_exact_workspace_index "$scratch/workspace-not-ready.json" 10000 \
  agent workspace-files --count
[[ "$(cat "$graph_refresh_attempt_file")" == 2 ]] \
  || die 'semantic-not-ready workspace read was not retried once'

retry_samples="$scratch/retry-samples.jsonl"
cat >"$retry_samples" <<'JSONL'
{"runtimeStatus":{"selected":{"runtimeStatus":{"progress":{"retry":{"attempt":1,"retryAtEpochMillis":1000,"lastError":{"code":"GRADLE_IMPORT_FAILED"}}}}}}}
{"runtimeStatus":{"selected":{"runtimeStatus":{"progress":{"retry":{"attempt":1,"retryAtEpochMillis":1000,"lastError":{"code":"GRADLE_IMPORT_FAILED"}}}}}}}
{"runtimeStatus":{"selected":{"runtimeStatus":{"progress":{"retryAttempt":2,"retryAt":"2026-08-05T12:00:00Z","retryTimeEpochMillis":2000,"lastError":{"code":"GRADLE_IMPORT_FAILED"}}}}}}
JSONL
summarize_runtime_retry_transitions "$retry_samples" "$scratch/retry-summary.json"
python3 - "$scratch/retry-summary.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["runtimeTransitionCount"] == 2, payload
assert len(payload["runtimeTransitions"]) == 2, payload
PY

printf '%s\n' '{"ok":true,"result":{"cardinality":{"type":"EXACT","totalCount":2}}}' \
  >"$scratch/evidence-workspace.json"
printf '%s\n' '{"ok":true,"result":{"generation":7,"symbolCount":4,"coverage":{"files":[{"path":"src/Probe.kt","status":"REFRESHED"},{"path":"src/Other.kt","status":"REMOVED"}]}}}' \
  >"$scratch/evidence-refresh.json"
printf '%s\n' '{"ok":true,"result":{"generation":7,"nodeCount":4,"edgeOccurrenceCount":7,"weightedEdgeCount":7.0}}' \
  >"$scratch/evidence-graph.json"
evidence_workspace_root="$scratch/evidence-root"
evidence_workspace_pages="$scratch/evidence-workspace-pages"
evidence_workspaces="$scratch/evidence-publication/workspaces"
evidence_database="$evidence_workspaces/$(printf '%064d' 0)/cache/source-index.db"
mkdir -p \
  "$evidence_workspace_root/src" \
  "$evidence_workspace_pages" \
  "$(dirname "$evidence_database")"
cat >"$evidence_workspace_pages/page-000001.json" <<'JSON'
{"ok":true,"result":{"cardinality":{"type":"EXACT","totalCount":2},"returnedCount":2,"files":[{"relativePath":"src/B.kt"},{"relativePath":"src/A.kt"}]}}
JSON
python3 - "$evidence_database" "$evidence_workspace_root" <<'PY'
import sqlite3
import sys
from pathlib import Path

database = Path(sys.argv[1])
workspace = Path(sys.argv[2]).resolve()
connection = sqlite3.connect(database)
connection.executescript("""
CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL);
INSERT INTO schema_version VALUES (15, 7);
CREATE TABLE workspace_publication(
    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
    revision INTEGER NOT NULL,
    identity TEXT NOT NULL,
    source_index_generation INTEGER NOT NULL,
    source_index_schema_version INTEGER NOT NULL,
    published_at_epoch_millis INTEGER NOT NULL,
    repository_overlay_file TEXT
);
INSERT INTO workspace_publication VALUES (1, 1, 'contract-fixture', 7, 15, 1, NULL);
CREATE TABLE semantic_files(id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE);
CREATE TABLE semantic_symbols(
    id INTEGER PRIMARY KEY,
    stable_key TEXT NOT NULL UNIQUE,
    file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL
);
CREATE TABLE semantic_edge_occurrences(
    id INTEGER PRIMARY KEY,
    source_id INTEGER NOT NULL,
    target_id INTEGER NOT NULL,
    source_file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    context TEXT NOT NULL,
    resolved_target_id INTEGER,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    line INTEGER NOT NULL
);
""")
files = [
    (1, str(workspace / "src/A.kt")),
    (2, "src/B.kt"),
    (3, "src/Probe.kt"),
]
connection.executemany("INSERT INTO semantic_files VALUES (?, ?)", files)
symbols = [
    (1, "sample.A", 1, "CLASS", "A"),
    (2, "sample.B", 2, "CLASS", "B"),
    (3, "sample.C", 3, "FUNCTION", "C"),
    (4, "sample.D", 3, "FUNCTION", "D"),
]
connection.executemany("INSERT INTO semantic_symbols VALUES (?, ?, ?, ?, ?)", symbols)
edges = [
    (1, 1, 2, 1, "CALLS", "BODY", 2, 10, 20, 2),
    (2, 1, 2, 1, "CALLS", "BODY", None, 30, 40, 3),
    (3, 1, 3, 1, "REFERENCES", "NONE", 3, 50, 60, 4),
    (4, 2, 3, 2, "CALLS", "BODY", 3, 70, 80, 5),
    (5, 2, 4, 2, "REFERENCES", "NONE", None, 90, 100, 6),
    (6, 3, 1, 3, "CALLS", "BODY", 1, 110, 120, 7),
    (7, 4, 4, 3, "REFERENCES", "NONE", 4, 130, 140, 8),
]
connection.executemany(
    "INSERT INTO semantic_edge_occurrences VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    edges,
)
connection.commit()
connection.close()
PY
verify_benchmark_evidence \
  "$scratch/evidence-workspace.json" \
  "$scratch/evidence-refresh.json" \
  "$scratch/evidence-graph.json" \
  src/Probe.kt \
  "$evidence_workspace_root" \
  "$evidence_workspace_pages" \
  "$evidence_database" \
  "$(( $(monotonic_millis) + 5000 ))" \
  "$scratch/correctness-evidence.json"
python3 - "$scratch/correctness-evidence.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["workspaceExactTotalCount"] == 2, payload
assert payload["workspaceFileIdentities"] == ["src/A.kt", "src/B.kt"], payload
assert payload["refreshSymbolCount"] == 4, payload
assert payload["refreshedPaths"] == ["src/Probe.kt"], payload
assert payload["removedPaths"] == ["src/Other.kt"], payload
assert payload["graphNodeCount"] == 4, payload
assert payload["graphEdgeOccurrenceCount"] == 7, payload
assert payload["graphWeightedEdgeCount"] == 7.0, payload
assert payload["sourceIndexGeneration"] == 7, payload
assert payload["semanticIdentityAlgorithm"] == "sha256-canonical-json-v2", payload
for field in (
    "workspaceFileIdentitySha256",
    "graphNodeIdentitySha256",
    "graphEdgeIdentitySha256",
):
    assert len(payload[field]) == 64, payload
    int(payload[field], 16)
PY

# Equal cardinalities and the old source/target/kind/context tuple do not prove
# edge occurrence identity. Source file, resolution, and source locations are
# semantic fields and must all change the exact edge fingerprint.
evidence_changed_edge_database="$evidence_workspaces/$(printf '%064d' 1)/cache/source-index.db"
mkdir -p "$(dirname "$evidence_changed_edge_database")"
cp "$evidence_database" "$evidence_changed_edge_database"
python3 - \
  "$evidence_changed_edge_database" <<'PY'
import sqlite3
import sys
from pathlib import Path

database = Path(sys.argv[1])
connection = sqlite3.connect(database)
connection.execute(
    """UPDATE semantic_edge_occurrences
       SET source_file_id = 2,
           resolved_target_id = 3,
           start_offset = 1000,
           end_offset = 1010,
           line = 100
       WHERE id = 1"""
)
connection.commit()
connection.close()
PY
verify_benchmark_evidence \
  "$scratch/evidence-workspace.json" \
  "$scratch/evidence-refresh.json" \
  "$scratch/evidence-graph.json" \
  src/Probe.kt \
  "$evidence_workspace_root" \
  "$evidence_workspace_pages" \
  "$evidence_changed_edge_database" \
  "$(( $(monotonic_millis) + 5000 ))" \
  "$scratch/changed-edge-correctness-evidence.json"
python3 - \
  "$scratch/correctness-evidence.json" \
  "$scratch/changed-edge-correctness-evidence.json" <<'PY'
import json
import sys
from pathlib import Path

baseline, changed = (
    json.loads(Path(path).read_text(encoding="utf-8"))
    for path in sys.argv[1:]
)
for field in (
    "sourceIndexGeneration",
    "graphNodeCount",
    "graphEdgeOccurrenceCount",
    "graphWeightedEdgeCount",
    "graphNodeIdentitySha256",
):
    assert baseline[field] == changed[field], (field, baseline, changed)
assert baseline["graphEdgeIdentitySha256"] != changed["graphEdgeIdentitySha256"], (
    baseline,
    changed,
)
PY
# Counts remain equal, but a publication that moves after the graph summary is
# a different semantic snapshot and must be rejected before comparison.
evidence_moved_database="$evidence_workspaces/$(printf '%064d' 2)/cache/source-index.db"
mkdir -p "$(dirname "$evidence_moved_database")"
cp "$evidence_database" "$evidence_moved_database"
python3 - \
  "$evidence_moved_database" <<'PY'
import sqlite3
import sys
from pathlib import Path

database = Path(sys.argv[1])
connection = sqlite3.connect(database)
connection.execute("UPDATE schema_version SET generation = 8")
connection.execute("UPDATE semantic_symbols SET stable_key = 'sample.Replaced' WHERE id = 4")
connection.execute(
    "UPDATE workspace_publication "
    "SET revision = 2, identity = 'moved-fixture', source_index_generation = 8 "
    "WHERE singleton = 1"
)
connection.commit()
connection.close()
PY
if verify_benchmark_evidence \
    "$scratch/evidence-workspace.json" \
    "$scratch/evidence-refresh.json" \
    "$scratch/evidence-graph.json" \
    src/Probe.kt \
    "$evidence_workspace_root" \
    "$evidence_workspace_pages" \
    "$evidence_moved_database" \
    "$(( $(monotonic_millis) + 5000 ))" \
    "$scratch/moved-correctness-evidence.json" \
    2>"$scratch/moved-publication.stderr"; then
  die 'equal-count publication movement after graph summary was accepted'
fi
grep -Fq 'one source-index generation' "$scratch/moved-publication.stderr" \
  || die 'publication movement failed without exact generation evidence'

repository_fixture="$scratch/repository"
ktor_fixture="$repository_fixture/ktor-test-server"
mkdir -p \
  "$ktor_fixture/src/main/kotlin/test/server" \
  "$repository_fixture/okcurl/src/main/kotlin"
touch \
  "$repository_fixture/settings.gradle.kts" \
  "$ktor_fixture/settings.gradle.kts" \
  "$ktor_fixture/src/main/kotlin/test/server/ServerUtils.kt" \
  "$repository_fixture/okcurl/src/main/kotlin/Main.kt"
[[ "$(gradle_workspace_for "$ktor_fixture/src/main/kotlin/test/server/ServerUtils.kt" "$repository_fixture")" == "$ktor_fixture" ]] \
  || die 'nested Ktor build was not selected'
[[ "$(gradle_workspace_for "$repository_fixture/okcurl/src/main/kotlin/Main.kt" "$repository_fixture")" == "$repository_fixture" ]] \
  || die 'repository Gradle root was not selected'
mkdir -p "$scratch/no-settings/src"
touch "$scratch/no-settings/src/Probe.kt"
if gradle_workspace_for "$scratch/no-settings/src/Probe.kt" \
    "$scratch/no-settings" >/dev/null; then
  die 'probe outside a Gradle build was accepted'
fi

gradle_user_fixture="$scratch/gradle-user"
gradle_java_fixture="$scratch/java-17"
runtime_java_fixture="$scratch/java-21"
mkdir -p "$gradle_user_fixture" "$gradle_java_fixture" "$runtime_java_fixture"
configure_gradle_java_paths \
  "$gradle_user_fixture" "$gradle_java_fixture" "$runtime_java_fixture"
[[ "$(cat "$gradle_user_fixture/gradle.properties")" == \
    "org.gradle.java.installations.paths=$gradle_java_fixture,$runtime_java_fixture" ]] \
  || die 'Gradle Tooling API paths were not configured'
if configure_gradle_java_paths \
    "$gradle_user_fixture" "$scratch/missing-java" "$runtime_java_fixture" \
    2>/dev/null; then
  die 'missing Gradle Java home was accepted'
fi

require "$cli_root" 'Config(ConfigArgs)' \
  'CLI must expose the config command family'
require "$cli_config" 'List(ConfigWorkspaceArgs)' \
  'config must list effective workspace state'
require "$cli_config" 'Set(ConfigSetArgs)' \
  'config must set one workspace field non-interactively'
require "$cli_config" 'Unset(ConfigUnsetArgs)' \
  'config must unset one workspace field non-interactively'
require "$dispatch" 'Command::Config(args)' \
  'config commands must be dispatched'
require "$cli_model" 'pub relationships: RelationshipIndexingConfig' \
  'effective config must name relationship indexing directly'
require "$project_indexer" 'indexSymbolRelationships(' \
  'Indexer orchestration must name the compiler-resolved operation'
require "$reference_indexer" 'onFilesIndexed(successfulPaths)' \
  'failed scans must not be reported as indexed'
require "$schema" 'relationship_index_status TEXT NOT NULL' \
  'module progress must name relationship indexing state'

write_evidence() {
  local path="$1" role="$2" correctness="$3" index_ms="$4" graph_ms="$5" disk_bytes="$6"
  local bundle_digest
  if [[ "$role" == stable ]]; then
    bundle_digest="$stable_test_digest"
  else
    bundle_digest="$candidate_test_digest"
  fi
  python3 - \
    "$path" "$role" "$correctness" "$index_ms" "$graph_ms" "$disk_bytes" \
    "$bundle_digest" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

path, role, correctness, index_ms, graph_ms, disk_bytes, bundle_digest = sys.argv[1:]
version = "v0.21.6" if role == "stable" else "v0.21.7"
backend_version = version.removeprefix("v")
phase_names = [
    "setup",
    "configure",
    "runtimeAdmission",
    "workspaceIndex",
    "coldIndex",
    "graphRefresh",
    "graphSummary",
    "semanticIdentity",
]
phase_durations = {name: 1 for name in phase_names}
phase_durations["coldIndex"] = int(index_ms)
phase_durations["graphRefresh"] = int(graph_ms)
storage_bytes = int(disk_bytes)
workspace_identities = ["src/A.kt", "src/Probe.kt"]
workspace_identity_sha256 = hashlib.sha256(json.dumps(
    workspace_identities,
    ensure_ascii=False,
    separators=(",", ":"),
).encode("utf-8")).hexdigest()

def successful_command(operation, started):
    return {
        "schemaVersion": 1,
        "type": "KAST_BENCHMARK_SUPERVISED_COMMAND",
        "operation": operation,
        "outcome": "SUCCEEDED",
        "exitCode": 0,
        "startedAtEpochMillis": started,
        "finishedAtEpochMillis": started + 1,
        "startedAtMonotonicMillis": started,
        "finishedAtMonotonicMillis": started + 1,
        "durationMillis": 1,
        "deadlineMonotonicMillis": started + 2,
        "termination": {"termSent": False, "killSent": False},
        "processGroupClosure": {
            "required": False,
            "proven": None,
            "pidfdsRetained": False,
            "capturedProcesses": [],
            "remainingProcesses": [],
            "recapturePasses": 0,
            "stableConfirmationPasses": 0,
        },
    }

def progress_sample(observed_at, phase, storage_value, owned_bytes):
    return {
        "observedAtEpochMillis": observed_at,
        "phase": phase,
        "statusExitCode": 0,
        "runtimeStatus": {
            "selected": {
                "runtimeStatus": {
                    "state": "READY",
                    "backendVersion": backend_version,
                },
            },
        },
        "storageFresh": True,
        "storageObservedAtEpochMillis": observed_at,
        "storage": {
            "databaseBytes": storage_value,
            "walBytes": storage_value,
            "kastHomeBytes": storage_value,
            "kastCacheBytes": storage_value,
            "gradleCacheBytes": storage_value,
            "userHomeBytes": storage_value,
            "workspaceBytes": storage_value,
            "ownedBytes": owned_bytes,
        },
        "resources": {
            "rssBytes": 1,
            "virtualBytes": 1,
            "cpuPercent": 0.0,
            "processCount": 1,
            "processIds": [1],
        },
    }

supervised_commands = [
    successful_command("contract-fixture", 1),
    {
        "schemaVersion": 1,
        "type": "KAST_BENCHMARK_OWNED_PROCESSES",
        "operation": "teardown-signaling",
        "outcome": "SUCCEEDED",
        "processes": [],
        "startedAtEpochMillis": 3,
        "finishedAtEpochMillis": 4,
        "startedAtMonotonicMillis": 3,
        "finishedAtMonotonicMillis": 4,
        "durationMillis": 1,
        "deadlineMonotonicMillis": 5,
        "termination": {"termSent": True, "killSent": False},
        "ownershipClosure": {
            "required": True,
            "proven": True,
            "pidfdsRetained": True,
            "capturedProcesses": [
                {"pid": 101, "startIdentity": "fixture-start:1"},
            ],
            "remainingProcesses": [],
            "recapturePasses": 3,
            "stableConfirmationPasses": 2,
        },
    },
    successful_command("worktree-cleanup", 5),
    successful_command("finalization", 7),
]
Path(path).write_text(json.dumps({
    "schemaVersion": 1,
    "role": role,
    "bundle": {
        "version": version,
        "expectedVersion": version,
        "runtimeBackendVersions": [backend_version],
        "sha256": bundle_digest,
        "fileName": f"kast-linux-x64-{version}.tar.gz",
    },
    "correctness": correctness == "true",
    "correctnessEvidence": {
        "semanticIdentityAlgorithm": "sha256-canonical-json-v2",
        "sourceIndexGeneration": 7,
        "workspaceExactTotalCount": 2,
        "workspaceFileIdentities": workspace_identities,
        "workspaceFileIdentitySha256": workspace_identity_sha256,
        "refreshSymbolCount": 20,
        "refreshedPaths": ["src/Probe.kt"],
        "removedPaths": [],
        "graphNodeCount": 200,
        "graphEdgeOccurrenceCount": 300,
        "graphWeightedEdgeCount": 350.0,
        "graphNodeIdentitySha256": "1" * 64,
        "graphEdgeIdentitySha256": "2" * 64,
    },
    "workspace": {
        "repositoryRelativeRoot": ".",
        "graphFile": "src/Probe.kt",
    },
    "diagnostic": {
        "outcome": "SUCCEEDED",
        "roleExitCode": 0,
        "supervisedCommands": supervised_commands,
    },
    "isolation": {
        "processTeardownProven": True,
        "worktreeRemoved": True,
    },
    "phases": [
        {
            "name": name,
            "startedAtEpochMillis": 1000,
            "finishedAtEpochMillis": 1000 + phase_durations[name],
            "startedAtMonotonicMillis": 2000,
            "finishedAtMonotonicMillis": 2000 + phase_durations[name],
            "durationMillis": phase_durations[name],
        }
        for name in phase_names
    ],
    "storage": {
        "initialDatabaseBytes": 0,
        "finalDatabaseBytes": 1,
        "peakDatabaseBytes": 1,
        "databaseGrowthBytes": 1,
        "initialWalBytes": 0,
        "finalWalBytes": 1,
        "peakWalBytes": 1,
        "walGrowthBytes": 1,
        "initialKastHomeBytes": 0,
        "finalKastHomeBytes": 1,
        "peakKastHomeBytes": 1,
        "initialKastCacheBytes": 0,
        "finalKastCacheBytes": 1,
        "peakKastCacheBytes": 1,
        "initialGradleCacheBytes": 0,
        "finalGradleCacheBytes": 1,
        "peakGradleCacheBytes": 1,
        "initialUserHomeBytes": 0,
        "finalUserHomeBytes": 1,
        "peakUserHomeBytes": 1,
        "initialWorkspaceBytes": 0,
        "finalWorkspaceBytes": 1,
        "peakWorkspaceBytes": 1,
        "initialOwnedBytes": 0,
        "finalOwnedBytes": storage_bytes,
        "peakOwnedBytes": storage_bytes,
        "ownedGrowthBytes": storage_bytes,
    },
    "retries": {
        "workspaceIndexPoll": 0,
        "semanticNotReadyPoll": 0,
        "graphGenerationConflict": 0,
        "runtimeStatusFailures": 0,
        "runtimeTransitionCount": 0,
        "runtimeTransitions": [],
    },
    "resources": {
        "peakRssBytes": 1,
        "peakVirtualBytes": 1,
        "peakCpuPercent": 0.0,
        "peakProcessCount": 1,
    },
    "progressSamples": [
        progress_sample(1000, "BASELINE", 0, 0),
        progress_sample(1001, "COMPLETE", 1, storage_bytes),
    ],
}), encoding="utf-8")
PY
}

set_phase_duration() {
  local path="$1" phase_name="$2" duration="$3"
  python3 - "$path" "$phase_name" "$duration" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
phase_name = sys.argv[2]
duration = int(sys.argv[3])
payload = json.loads(path.read_text(encoding="utf-8"))
phase = next(item for item in payload["phases"] if item["name"] == phase_name)
phase["durationMillis"] = duration
phase["finishedAtEpochMillis"] = phase["startedAtEpochMillis"] + duration
phase["finishedAtMonotonicMillis"] = phase["startedAtMonotonicMillis"] + duration
path.write_text(json.dumps(payload), encoding="utf-8")
PY
}

stable_bundle="$scratch/kast-linux-x64-v0.21.6.tar.gz"
candidate_bundle="$scratch/kast-linux-x64-v0.21.7.tar.gz"
printf 'stable contract bundle\n' >"$stable_bundle"
printf 'candidate contract bundle\n' >"$candidate_bundle"
stable_test_digest="$(python3 - "$stable_bundle" <<'PY'
import hashlib
import sys
from pathlib import Path
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
candidate_test_digest="$(python3 - "$candidate_bundle" <<'PY'
import hashlib
import sys
from pathlib import Path
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
stable="$scratch/stable.json"
candidate="$scratch/candidate.json"
comparison="$scratch/comparison.json"

# A percentage-only change and an absolute-only change both remain within the
# conjunctive performance budget.
write_evidence "$stable" stable true 400000 100000 1073741824
write_evidence "$candidate" candidate true 459000 160000 1341128704
compare_benchmark_evidence "$stable" "$candidate" "$comparison" \
  || die 'comparator rejected evidence within the conjunctive budgets'
python3 - "$comparison" <<'PY'
import json
import sys
from pathlib import Path
payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["passed"] is True, payload
assert payload["failures"] == [], payload
PY

# Exact integer thresholds must not fail due to binary floating-point rounding.
write_evidence "$stable" stable true 400020 100000 1073741824
write_evidence "$candidate" candidate true 460023 100000 1234803098
compare_benchmark_evidence "$stable" "$candidate" "$comparison" \
  || die 'comparator rejected evidence at the exact integer threshold'

# Stable releases can block `up` through readiness while candidates can return
# at admission. The total cold boundary is comparable; the behavior-dependent
# admission/index split is diagnostic only.
write_evidence "$stable" stable true 400000 100000 1073741824
write_evidence "$candidate" candidate true 400000 100000 1073741824
set_phase_duration "$stable" runtimeAdmission 300000
set_phase_duration "$stable" workspaceIndex 1000
set_phase_duration "$candidate" runtimeAdmission 1000
set_phase_duration "$candidate" workspaceIndex 300000
compare_benchmark_evidence "$stable" "$candidate" "$comparison" \
  || die 'comparator mixed blocking and early-admission phase semantics'
python3 - "$comparison" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
compared = {phase["phase"] for phase in payload["phaseRegressions"]}
assert compared == {
    "setup", "configure", "coldIndex", "graphRefresh", "graphSummary", "semanticIdentity",
}, payload
PY

# More than 15 percent and more than 60 seconds for one phase is a regression.
write_evidence "$candidate" candidate true 461000 100000 1073741824
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted a phase regression over both limits'
fi
grep -Fq 'PHASE_REGRESSION' "$comparison" \
  || die 'phase regression evidence is missing'

# Disk growth must exceed both 15 percent and 256 MiB before it fails.
write_evidence "$candidate" candidate true 400000 100000 1342177281
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted a disk regression over both limits'
fi
grep -Fq 'DISK_REGRESSION' "$comparison" \
  || die 'disk regression evidence is missing'

write_evidence "$candidate" candidate true 2700001 100000 1073741824
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted cold indexing beyond 45 minutes'
fi
grep -Fq 'COLD_INDEX_LIMIT' "$comparison" \
  || die 'cold-index limit evidence is missing'

write_evidence "$candidate" candidate false 400000 100000 1073741824
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted lost correctness'
fi
grep -Fq 'CORRECTNESS_LOST' "$comparison" \
  || die 'correctness failure evidence is missing'

write_evidence "$stable" stable true 400000 100000 1073741824
write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["correctnessEvidence"]["sourceIndexGeneration"] += 1
path.write_text(json.dumps(payload), encoding="utf-8")
PY
compare_benchmark_evidence "$stable" "$candidate" "$comparison" \
  || die 'comparator rejected equal semantic evidence with a different run-local generation'
python3 - "$comparison" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["passed"] is True, payload
assert "sourceIndexGeneration" not in {
    item["field"] for item in payload["correctnessRegressions"]
}, payload
PY

pre_spawn_stable="$scratch/pre-spawn-stable.json"
pre_spawn_candidate="$scratch/pre-spawn-candidate.json"
python3 - "$stable" "$candidate" "$pre_spawn_stable" "$pre_spawn_candidate" <<'PY'
import json
import sys
from pathlib import Path

for source, destination in zip(sys.argv[1:3], sys.argv[3:5], strict=True):
    payload = json.loads(Path(source).read_text(encoding="utf-8"))
    event = payload["diagnostic"]["supervisedCommands"][0]
    event.update({
        "pid": None,
        "captureIdentity": None,
        "pidfdOpenedBeforeWait": False,
        "outcome": "TIMED_OUT",
        "exitCode": 124,
        "timeoutPhase": "PRE_SPAWN",
        "timeoutReason": "DEADLINE_EXPIRED",
        "detail": "command deadline expired before spawn",
        "remainingBudgetMillis": 0,
        "requiredCleanupReserveMillis": 8_000,
        "termGraceMillis": 5_000,
        "killGraceMillis": 2_000,
        "closureProofReserveMillis": 1_000,
        "admissionReserveMillis": 1_000,
        "requiredAdmissionBudgetMillis": 9_000,
        "deadlineMonotonicMillis": event["startedAtMonotonicMillis"] - 1,
    })
    Path(destination).write_text(json.dumps(payload), encoding="utf-8")
PY
compare_benchmark_evidence \
  "$pre_spawn_stable" "$pre_spawn_candidate" "$comparison" \
  || die 'comparator rejected typed pre-spawn timeout evidence'

# These globals are consumed by the sourced assembly function.
# shellcheck disable=SC2034
name=contract-repository repository=https://github.com/example/repository.git \
  revision=0000000000000000000000000000000000000000 graph_file=src/Probe.kt
assembled="$scratch/assembled.json"
assemble_benchmark_evidence "$stable" "$candidate" "$comparison" "$assembled"
python3 - "$assembled" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["repository"]["name"] == "contract-repository", payload
assert payload["passed"] is True, payload
assert payload["comparison"]["passed"] is True, payload
assert [run["role"] for run in payload["runs"]] == ["stable", "candidate"], payload
for run in payload["runs"]:
    assert run["bundle"]["fileName"], run
    assert len(run["bundle"]["sha256"]) == 64, run
PY

repository_validation_arguments=(
  validate-repository
  --repository-spec 'contract-repository|https://github.com/example/repository.git|0000000000000000000000000000000000000000|src/Probe.kt'
  --stable-tag v0.21.6
  --stable-digest "$stable_test_digest"
  --candidate-tag v0.21.7
  --candidate-digest "$candidate_test_digest"
)

pre_spawn_repository="$scratch/pre-spawn-repository.json"
python3 - "$assembled" "$pre_spawn_repository" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for run in payload["runs"]:
    event = run["diagnostic"]["supervisedCommands"][0]
    event.update({
        "pid": None,
        "captureIdentity": None,
        "pidfdOpenedBeforeWait": False,
        "outcome": "TIMED_OUT",
        "exitCode": 124,
        "timeoutPhase": "PRE_SPAWN",
        "timeoutReason": "DEADLINE_EXPIRED",
        "detail": "command deadline expired before spawn",
        "remainingBudgetMillis": 0,
        "requiredCleanupReserveMillis": 8_000,
        "termGraceMillis": 5_000,
        "killGraceMillis": 2_000,
        "closureProofReserveMillis": 1_000,
        "admissionReserveMillis": 1_000,
        "requiredAdmissionBudgetMillis": 9_000,
        "deadlineMonotonicMillis": event["startedAtMonotonicMillis"] - 1,
    })
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
"$aggregator" "${repository_validation_arguments[@]}" \
  --evidence "$pre_spawn_repository" \
  || die 'repository validator rejected typed pre-spawn timeout evidence'

insufficient_admission_repository="$scratch/insufficient-admission-repository.json"
python3 - "$pre_spawn_repository" "$insufficient_admission_repository" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for run in payload["runs"]:
    event = run["diagnostic"]["supervisedCommands"][0]
    event.update({
        "timeoutReason": "INSUFFICIENT_ADMISSION_BUDGET",
        "detail": "insufficient command admission budget before spawn",
        "deadlineMonotonicMillis": event["startedAtMonotonicMillis"] + 2_500,
        "remainingBudgetMillis": 2_500,
        "requiredCleanupReserveMillis": 2_000,
        "termGraceMillis": 500,
        "killGraceMillis": 500,
        "closureProofReserveMillis": 1_000,
        "admissionReserveMillis": 1_000,
        "requiredAdmissionBudgetMillis": 3_000,
    })
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
"$aggregator" "${repository_validation_arguments[@]}" \
  --evidence "$insufficient_admission_repository" \
  || die 'repository validator rejected insufficient-admission timeout evidence'

insufficient_cleanup_repository="$scratch/insufficient-cleanup-repository.json"
python3 - "$insufficient_admission_repository" "$insufficient_cleanup_repository" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for run in payload["runs"]:
    event = run["diagnostic"]["supervisedCommands"][0]
    event.update({
        "timeoutReason": "INSUFFICIENT_CLEANUP_RESERVE",
        "detail": "insufficient cleanup reserve before spawn",
        "deadlineMonotonicMillis": event["startedAtMonotonicMillis"] + 1_500,
        "remainingBudgetMillis": 1_500,
    })
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
"$aggregator" "${repository_validation_arguments[@]}" \
  --evidence "$insufficient_cleanup_repository" \
  || die 'repository validator rejected insufficient-cleanup timeout evidence'

misclassified_admission_budget="$scratch/misclassified-admission-budget.json"
python3 - "$insufficient_admission_repository" "$misclassified_admission_budget" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
event = payload["runs"][0]["diagnostic"]["supervisedCommands"][0]
event["timeoutReason"] = "INSUFFICIENT_CLEANUP_RESERVE"
event["detail"] = "insufficient cleanup reserve before spawn"
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$misclassified_admission_budget" \
    2>"$scratch/misclassified-admission-budget.stderr"; then
  die 'repository validator accepted admission-budget evidence as cleanup insufficiency'
fi
grep -Fq 'cleanup timeout had sufficient cleanup reserve' \
  "$scratch/misclassified-admission-budget.stderr" \
  || die 'misclassified admission budget failed without exact interval diagnosis'

misclassified_cleanup_reserve="$scratch/misclassified-cleanup-reserve.json"
python3 - "$insufficient_cleanup_repository" "$misclassified_cleanup_reserve" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
event = payload["runs"][0]["diagnostic"]["supervisedCommands"][0]
event["timeoutReason"] = "INSUFFICIENT_ADMISSION_BUDGET"
event["detail"] = "insufficient command admission budget before spawn"
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$misclassified_cleanup_reserve" \
    2>"$scratch/misclassified-cleanup-reserve.stderr"; then
  die 'repository validator accepted cleanup insufficiency as admission-budget evidence'
fi
grep -Fq 'admission timeout budget is outside its exact interval' \
  "$scratch/misclassified-cleanup-reserve.stderr" \
  || die 'misclassified cleanup reserve failed without exact interval diagnosis'

ordinary_timeout_without_closure="$scratch/ordinary-timeout-without-closure.json"
python3 - "$assembled" "$ordinary_timeout_without_closure" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
event = payload["runs"][0]["diagnostic"]["supervisedCommands"][0]
event.update({"outcome": "TIMED_OUT", "exitCode": 124})
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$ordinary_timeout_without_closure" \
    2>"$scratch/ordinary-timeout-without-closure.stderr"; then
  die 'repository validator accepted an ordinary timeout without closure proof'
fi
grep -Fq 'processGroupClosure is not proven' \
  "$scratch/ordinary-timeout-without-closure.stderr" \
  || die 'ordinary timeout failed without exact closure diagnosis'

forged_pre_spawn_timeout="$scratch/forged-pre-spawn-timeout.json"
python3 - "$pre_spawn_repository" "$forged_pre_spawn_timeout" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["runs"][0]["diagnostic"]["supervisedCommands"][0]["pid"] = 42
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$forged_pre_spawn_timeout" \
    2>"$scratch/forged-pre-spawn-timeout.stderr"; then
  die 'repository validator accepted a pre-spawn timeout with a process'
fi
grep -Fq 'pre-spawn timeout unexpectedly has a process' \
  "$scratch/forged-pre-spawn-timeout.stderr" \
  || die 'forged pre-spawn timeout failed without exact process diagnosis'

forged_cleanup_reserve="$scratch/forged-cleanup-reserve.json"
python3 - "$insufficient_admission_repository" "$forged_cleanup_reserve" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
event = payload["runs"][0]["diagnostic"]["supervisedCommands"][0]
event["requiredCleanupReserveMillis"] += 1
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$forged_cleanup_reserve" \
    2>"$scratch/forged-cleanup-reserve.stderr"; then
  die 'repository validator accepted an inconsistent cleanup reserve'
fi
grep -Fq 'required cleanup reserve is inconsistent' \
  "$scratch/forged-cleanup-reserve.stderr" \
  || die 'forged cleanup reserve failed without exact diagnosis'

shallow_isolation="$scratch/shallow-isolation.json"
python3 - "$assembled" "$shallow_isolation" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for run in payload["runs"]:
    commands = run["diagnostic"]["supervisedCommands"]
    run["diagnostic"]["supervisedCommands"] = [
        event for event in commands if event["operation"] == "contract-fixture"
    ]
    run["isolation"] = {
        "processTeardownProven": True,
        "worktreeRemoved": True,
    }
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$shallow_isolation" 2>"$scratch/shallow-isolation.stderr"; then
  die 'repository validator trusted asserted isolation without closure events'
fi
grep -Fq 'derived isolation evidence is incomplete' "$scratch/shallow-isolation.stderr" \
  || die 'shallow isolation failed without exact derived-evidence diagnosis'

invalid_event_duration="$scratch/invalid-event-duration.json"
python3 - "$assembled" "$invalid_event_duration" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["runs"][0]["diagnostic"]["supervisedCommands"][0]["durationMillis"] += 1
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$invalid_event_duration" 2>"$scratch/invalid-event-duration.stderr"; then
  die 'repository validator accepted inconsistent command duration evidence'
fi
grep -Fq 'supervised command duration mismatch' "$scratch/invalid-event-duration.stderr" \
  || die 'command duration mismatch failed without exact diagnosis'

invalid_event_deadline="$scratch/invalid-event-deadline.json"
python3 - "$assembled" "$invalid_event_deadline" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
event = payload["runs"][0]["diagnostic"]["supervisedCommands"][0]
event["deadlineMonotonicMillis"] = event["finishedAtMonotonicMillis"] - 1
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$invalid_event_deadline" 2>"$scratch/invalid-event-deadline.stderr"; then
  die 'repository validator accepted a successful command after its deadline'
fi
grep -Fq 'successful command finished after its deadline' \
  "$scratch/invalid-event-deadline.stderr" \
  || die 'command deadline mismatch failed without exact diagnosis'

invalid_teardown_closure="$scratch/invalid-teardown-closure.json"
python3 - "$assembled" "$invalid_teardown_closure" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for run in payload["runs"]:
    teardown = next(
        event
        for event in run["diagnostic"]["supervisedCommands"]
        if event["operation"] == "teardown-signaling"
    )
    teardown["ownershipClosure"]["proven"] = False
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$invalid_teardown_closure" 2>"$scratch/invalid-teardown-closure.stderr"; then
  die 'repository validator accepted an unproven teardown closure'
fi
grep -Fq 'teardown closure is not proven' "$scratch/invalid-teardown-closure.stderr" \
  || die 'unproven teardown closure failed without exact diagnosis'

for aggregate_dimension in storage resources retries runtime-status retries-transition; do
  forged_aggregate="$scratch/forged-$aggregate_dimension.json"
  python3 - "$assembled" "$forged_aggregate" "$aggregate_dimension" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
run = payload["runs"][1]
dimension = sys.argv[3]
if dimension == "storage":
    run["progressSamples"][0]["storage"]["ownedBytes"] = 2 * 1024**3
elif dimension == "resources":
    run["progressSamples"][0]["resources"]["rssBytes"] = 4096
elif dimension == "retries":
    run["retries"]["workspaceIndexPoll"] = 1
elif dimension == "runtime-status":
    run["progressSamples"][0]["statusExitCode"] = 1
else:
    run["progressSamples"][0]["runtimeStatus"]["selected"]["runtimeStatus"]["progress"] = {
        "retry": {
            "attempt": 1,
            "retryAtEpochMillis": 2000,
            "lastError": {"code": "GRADLE_IMPORT_FAILED"},
        },
    }
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
  if "$aggregator" "${repository_validation_arguments[@]}" \
      --evidence "$forged_aggregate" 2>"$scratch/forged-$aggregate_dimension.stderr"; then
    die "repository validator trusted forged $aggregate_dimension aggregates"
  fi
  grep -Fq 'does not match raw benchmark evidence' \
    "$scratch/forged-$aggregate_dimension.stderr" \
    || die "forged $aggregate_dimension failed without raw-evidence diagnosis"
done

supervision_failure_evidence="$scratch/supervision-failure-event.json"
python3 - "$assembled" "$supervision_failure_evidence" <<'PY'
import copy
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
event = copy.deepcopy(payload["runs"][0]["diagnostic"]["supervisedCommands"][0])
event.update({
    "operation": "resource-disk-sample",
    "outcome": "SUPERVISION_FAILED",
    "exitCode": 125,
    "processGroupClosure": {
        "required": True,
        "proven": False,
        "pidfdsRetained": True,
        "capturedProcesses": [],
        "remainingProcesses": [],
        "recapturePasses": 1,
        "stableConfirmationPasses": 0,
        "enumerationError": {"code": "EIO", "detail": "fixture"},
    },
})
payload["runs"][0]["diagnostic"]["supervisedCommands"].insert(1, event)
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${repository_validation_arguments[@]}" \
    --evidence "$supervision_failure_evidence" \
    2>"$scratch/supervision-failure-event.stderr"; then
  die 'repository validator accepted a non-lifecycle supervision failure'
fi
grep -Fq 'contains a supervision failure' "$scratch/supervision-failure-event.stderr" \
  || die 'supervision failure event lacked an exact aggregate diagnosis'

aggregate_input="$scratch/aggregate-input"
aggregate_output="$scratch/release-aggregate.json"
mkdir -p "$aggregate_input"
python3 - "$assembled" "$aggregate_input" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
destination = Path(sys.argv[2])
specs = {
    "ktor": (
        "https://github.com/ktorio/ktor-samples.git",
        "c89f051e1183455eda8b26146555a1b5ed23d18c",
        "httpbin/src/main/kotlin/io/ktor/samples/httpbin/Server.kt",
    ),
    "spring-boot": (
        "https://github.com/AleksK1NG/Kotlin-Clean-Architecture-CQRS.git",
        "d523770246f2ddb586868e4a9e55cafce7e0d6f8",
        "src/main/kotlin/com/alexander/bryksin/kotlinspringcleanarchitecture/KotlinSpringCleanArchitectureApplication.kt",
    ),
    "okhttp": (
        "https://github.com/square/okhttp.git",
        "0960b47ec28a02e893499d2a7e53bf462a62875e",
        "okcurl/src/main/kotlin/okhttp3/curl/Main.kt",
    ),
}
for name, (url, revision, graph_file) in specs.items():
    payload = copy.deepcopy(source)
    payload["repository"] = {
        "name": name,
        "url": url,
        "revision": revision,
        "graphFile": graph_file,
    }
    (destination / f"{name}.json").write_text(json.dumps(payload), encoding="utf-8")
PY
repository_specs=(
  'ktor|https://github.com/ktorio/ktor-samples.git|c89f051e1183455eda8b26146555a1b5ed23d18c|httpbin/src/main/kotlin/io/ktor/samples/httpbin/Server.kt'
  'spring-boot|https://github.com/AleksK1NG/Kotlin-Clean-Architecture-CQRS.git|d523770246f2ddb586868e4a9e55cafce7e0d6f8|src/main/kotlin/com/alexander/bryksin/kotlinspringcleanarchitecture/KotlinSpringCleanArchitectureApplication.kt'
  'okhttp|https://github.com/square/okhttp.git|0960b47ec28a02e893499d2a7e53bf462a62875e|okcurl/src/main/kotlin/okhttp3/curl/Main.kt'
)
aggregate_arguments=(
  aggregate-release
  --evidence-root "$aggregate_input"
  --output "$aggregate_output"
  --release-tag v0.21.7
  --release-sha 0000000000000000000000000000000000000000
  --stable-tag v0.21.6
  --stable-digest "$stable_test_digest"
  --candidate-digest "$candidate_test_digest"
)
for repository_spec in "${repository_specs[@]}"; do
  aggregate_arguments+=(--repository-spec "$repository_spec")
done
"$aggregator" "${aggregate_arguments[@]}"
python3 - "$aggregate_output" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["schemaVersion"] == 1, payload
assert payload["passed"] is True, payload
assert payload["release"] == {
    "tag": "v0.21.7",
    "gitSha": "0" * 40,
}, payload
assert [item["repository"]["name"] for item in payload["repositories"]] == [
    "ktor", "okhttp", "spring-boot",
], payload
for repository in payload["repositories"]:
    assert repository["schemaVersion"] == 1, repository
    assert repository["comparison"]["policy"]["coldIndexLimitMillis"] == 2_700_000, repository
    assert [run["role"] for run in repository["runs"]] == ["stable", "candidate"], repository
PY

python3 - "$aggregate_input/ktor.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["runs"][0]["schemaVersion"] = 2
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${aggregate_arguments[@]}" 2>"$scratch/malformed-run-schema.stderr"; then
  die 'release aggregator trusted a malformed nested run schema'
fi
grep -Fq 'stable schemaVersion must equal 1' "$scratch/malformed-run-schema.stderr" \
  || die 'malformed nested run schema failed without exact evidence'

# Restore the fixture, then prove that exact pinned repository context cannot
# be replaced while all superficial pass booleans remain true.
python3 - "$assembled" "$aggregate_input/ktor.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["repository"] = {
    "name": "ktor",
    "url": "https://github.com/ktorio/ktor-samples.git",
    "revision": "f" * 40,
    "graphFile": "httpbin/src/main/kotlin/io/ktor/samples/httpbin/Server.kt",
}
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${aggregate_arguments[@]}" 2>"$scratch/malformed-context.stderr"; then
  die 'release aggregator trusted mismatched pinned repository context'
fi
grep -Fq 'repository context mismatch' "$scratch/malformed-context.stderr" \
  || die 'mismatched repository context failed without exact evidence'

python3 - "$assembled" "$aggregate_input/ktor.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["repository"] = {
    "name": "ktor",
    "url": "https://github.com/ktorio/ktor-samples.git",
    "revision": "c89f051e1183455eda8b26146555a1b5ed23d18c",
    "graphFile": "httpbin/src/main/kotlin/io/ktor/samples/httpbin/Server.kt",
}
payload["comparison"] = {"schemaVersion": 1, "passed": True}
payload["passed"] = True
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
PY
if "$aggregator" "${aggregate_arguments[@]}" 2>"$scratch/spoofed-pass.stderr"; then
  die 'release aggregator trusted superficial passed booleans'
fi
grep -Fq 'comparison does not match strict nested evidence' "$scratch/spoofed-pass.stderr" \
  || die 'spoofed comparison failed without strict nested-evidence diagnosis'

# Missing required evidence is a typed comparison failure, not a silent zero.
write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["phases"] = [phase for phase in payload["phases"] if phase["name"] != "workspaceIndex"]
payload["storage"].pop("peakOwnedBytes")
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted incomplete evidence'
fi
grep -Fq 'EVIDENCE_INVALID' "$comparison" \
  || die 'invalid-evidence failure is missing'

# Exact correctness cardinalities cannot move in either direction for the same
# pinned repository, configuration, and graph probe.
for correctness_delta in -1 1; do
  for correctness_field in \
    workspaceExactTotalCount \
    refreshSymbolCount \
    graphNodeCount \
    graphEdgeOccurrenceCount \
    graphWeightedEdgeCount; do
    write_evidence "$stable" stable true 400000 100000 1073741824
    write_evidence "$candidate" candidate true 400000 100000 1073741824
    python3 - "$candidate" "$correctness_field" "$correctness_delta" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
field = sys.argv[2]
delta = int(sys.argv[3])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["correctnessEvidence"][field] += delta
if field == "workspaceExactTotalCount":
    identities = payload["correctnessEvidence"]["workspaceFileIdentities"]
    identities = identities[:-1] if delta < 0 else sorted([*identities, "src/Added.kt"])
    payload["correctnessEvidence"]["workspaceFileIdentities"] = identities
    payload["correctnessEvidence"]["workspaceFileIdentitySha256"] = hashlib.sha256(json.dumps(
        identities,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")).hexdigest()
path.write_text(json.dumps(payload), encoding="utf-8")
PY
    if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
      die "comparator accepted candidate correctness delta $correctness_delta: $correctness_field"
    fi
    grep -Fq 'CORRECTNESS_REGRESSION' "$comparison" \
      || die "correctness mismatch evidence is missing: $correctness_field"
  done
done

# Equal cardinalities cannot hide a different semantic result. Workspace, node,
# and edge identities are independent exact comparison dimensions.
for identity_dimension in workspace nodes edges; do
  write_evidence "$stable" stable true 400000 100000 1073741824
  write_evidence "$candidate" candidate true 400000 100000 1073741824
  python3 - "$candidate" "$identity_dimension" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
dimension = sys.argv[2]
payload = json.loads(path.read_text(encoding="utf-8"))
evidence = payload["correctnessEvidence"]
if dimension == "workspace":
    evidence["workspaceFileIdentities"] = ["src/A.kt", "src/Changed.kt"]
    evidence["workspaceFileIdentitySha256"] = hashlib.sha256(json.dumps(
        evidence["workspaceFileIdentities"],
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")).hexdigest()
elif dimension == "nodes":
    evidence["graphNodeIdentitySha256"] = "3" * 64
else:
    evidence["graphEdgeIdentitySha256"] = "4" * 64
path.write_text(json.dumps(payload), encoding="utf-8")
PY
  if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
    die "comparator accepted equal-count different-$identity_dimension identities"
  fi
  grep -Fq 'CORRECTNESS_IDENTITY_MISMATCH' "$comparison" \
    || die "semantic identity mismatch is missing: $identity_dimension"
done

# The removed path set is exact evidence, even when all numeric counts match.
write_evidence "$stable" stable true 400000 100000 1073741824
write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["correctnessEvidence"]["removedPaths"] = ["src/Removed.kt"]
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted a removed-path identity mismatch'
fi
grep -Fq 'CORRECTNESS_IDENTITY_MISMATCH' "$comparison" \
  || die 'removed-path identity mismatch evidence is missing'

# Isolation is an acceptance invariant, not an informational boolean. Prove
# each field independently so one enforced field cannot mask an ignored field.
for isolation_field in processTeardownProven worktreeRemoved; do
  write_evidence "$stable" stable true 400000 100000 1073741824
  write_evidence "$candidate" candidate true 400000 100000 1073741824
  python3 - "$candidate" "$isolation_field" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
field = sys.argv[2]
payload = json.loads(path.read_text(encoding="utf-8"))
payload["isolation"][field] = False
path.write_text(json.dumps(payload), encoding="utf-8")
PY
  if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
    die "comparator accepted false isolation proof: $isolation_field"
  fi
  grep -Fq 'EVIDENCE_INVALID' "$comparison" \
    || die "unproven-isolation evidence failure is missing: $isolation_field"
done

# Raw storage and resource samples must each remain typed before publication.
for sample_field in storage.databaseBytes resources.rssBytes; do
  write_evidence "$candidate" candidate true 400000 100000 1073741824
  python3 - "$candidate" "$sample_field" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
group, field = sys.argv[2].split(".", maxsplit=1)
payload = json.loads(path.read_text(encoding="utf-8"))
payload["progressSamples"][0][group][field] = "not-a-number"
path.write_text(json.dumps(payload), encoding="utf-8")
PY
  if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
    die "comparator accepted untyped progress measurement: $sample_field"
  fi
done

# Epoch and monotonic clocks are independent evidence. Reverse each one alone.
for clock in Epoch Monotonic; do
  write_evidence "$candidate" candidate true 400000 100000 1073741824
  python3 - "$candidate" "$clock" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
clock = sys.argv[2]
payload = json.loads(path.read_text(encoding="utf-8"))
phase = payload["phases"][0]
phase[f"finishedAt{clock}Millis"] = phase[f"startedAt{clock}Millis"] - 1
path.write_text(json.dumps(payload), encoding="utf-8")
PY
  if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
    die "comparator accepted reversed $clock phase timestamps"
  fi
done

write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
for sample in payload["progressSamples"]:
    sample["runtimeStatus"] = None
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted evidence without runtime progress'
fi

# Bundle identity includes the installed receipt and observed runtime backend.
write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["bundle"]["version"] = "v0.20.0"
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted a receipt version that disagrees with the candidate bundle'
fi

write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["bundle"]["fileName"] = "renamed-candidate.tar.gz"
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted a bundle name without an exact version tag'
fi

# The recorded backend identity and observed status identity are each bound to
# the exact candidate bundle version. Test them independently.
write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["bundle"]["runtimeBackendVersions"] = ["0.20.0"]
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted a recorded backend that disagrees with the candidate bundle'
fi

write_evidence "$candidate" candidate true 400000 100000 1073741824
python3 - "$candidate" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["progressSamples"][0]["runtimeStatus"]["selected"]["runtimeStatus"]["backendVersion"] = "0.20.0"
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if compare_benchmark_evidence "$stable" "$candidate" "$comparison"; then
  die 'comparator accepted an observed backend that disagrees with the candidate bundle'
fi

printf '%s\n' 'release indexing benchmark contract passed'
