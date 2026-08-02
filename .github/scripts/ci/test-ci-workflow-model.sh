#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'CI workflow model contract: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
checker="${repo_root}/.github/scripts/ci/ci_workflow_model.py"
model="${repo_root}/.github/ci/issue-401-workflow-model.json"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ci-workflow-model.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT

[[ -f "$model" ]] || die "missing authoritative graph model: ${model}"
[[ "$(awk '/^[[:space:]]*cache-read-only: false$/ { count++ } END { print count + 0 }' \
  "${repo_root}/.github/workflows/ci-build-and-test.yml")" -eq 1 ]] \
  || die "reusable build-and-test must explicitly own the sole PR Gradle cache write"
[[ "$(awk '/^[[:space:]]*cache-read-only: false$/ { count++ } END { print count + 0 }' \
  "${repo_root}/.github/workflows/ci.yml")" -eq 0 ]] \
  || die "fanout jobs must not write Gradle cache state"

report="${scratch_dir}/report.json"
python3 "$checker" "$model" >"$report"
python3 - "$report" "$model" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
model = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
if report["status"] != "provisional":
    raise SystemExit(f"expected provisional timing evidence, received {report['status']}")
if not report["comparison"]["outputEquivalent"]:
    raise SystemExit("candidate proof outputs must match or have an explicit replacement")
expected_replacements = {}
actual_replacements = report["comparison"]["retiredProofOutputReplacements"]
if actual_replacements != expected_replacements:
    raise SystemExit(f"this graph change must not retire proof outputs: {actual_replacements}")
if report["comparison"]["taskCountIncrease"] != 0:
    raise SystemExit("the headless-only candidate must not add an execution node")
if report["candidate"]["pullRequestTaskCount"] != report["baseline"]["pullRequestTaskCount"]:
    raise SystemExit("retiring the public plugin must keep the normalized task count stable")
if report["candidate"]["fanoutGateSeconds"] > 90:
    raise SystemExit("the modeled static fanout gate must not exceed 90 seconds")
if report["candidate"]["canaryTaskIds"]:
    raise SystemExit("pull-request CI must not model an off-path canary")
if "test-intellij-runtime" not in report["baseline"]["criticalPathTaskIds"]:
    raise SystemExit("the baseline must expose the serialized IntelliJ runtime test bottleneck")
if "test-intellij-runtime" in report["candidate"]["criticalPathTaskIds"]:
    raise SystemExit("independent IntelliJ runtime tests must not delay the artifact consumer path")

candidate_tasks = {task["id"]: task for task in model["candidate"]["tasks"]}
for graph_name in ("baseline", "candidate"):
    action_tasks = [
        task["id"]
        for task in model[graph_name]["tasks"]
        if task["id"] == "kast-action"
        or "kast-action-v2-installed-runtime-contract" in task["outputs"]
    ]
    if action_tasks:
        raise SystemExit(
            f"{graph_name} must not retain the deprecated kast-action proof: "
            f"{action_tasks}"
        )
if report["candidate"]["provisionalTaskIds"] != sorted(candidate_tasks):
    raise SystemExit(
        "candidate projected timings must remain provisional without declared runs"
    )
if set(candidate_tasks["prepared-ubuntu-debian-bundle"]["needs"]) != {
    "prepared-generation",
}:
    raise SystemExit("the prepared bundle must depend only on the prepared headless generation")
if candidate_tasks["workflow-contracts"]["outputs"] != [
    "repository-shape-contract",
    "ci-local-source-snapshot",
    "ci-artifact-ledger-local-source-snapshot",
    "release-workflow-contract",
    "ci-workflow-model-contract",
    "kast-build-contract",
    "docs-navigation-contract",
    "docs-content-contract",
    "macos-installer-contract",
    "release-asset-verifier",
    "release-provenance-assembler",
    "ci-artifact-ledger",
    "headless-runtime-packagers",
    "ci-gradle-retry",
]:
    raise SystemExit("the static gate must inventory every current contract and source snapshot proof")
if candidate_tasks["prepared-generation"]["outputs"] != [
    "prepared-local-generation",
    "ci-artifact-ledger-prepared-generation",
]:
    raise SystemExit("prepared generation must own its artifact and ledger proofs")
if candidate_tasks["prepared-ubuntu-debian-bundle"]["outputs"] != [
    "ubuntu-debian-bundle",
    "ci-artifact-ledger-prepared-ubuntu-debian-bundle",
]:
    raise SystemExit("the prepared bundle must own its artifact and ledger proofs")
if candidate_tasks["test-intellij-runtime"]["outputs"] != [
    "intellij-runtime-tests",
    "intellij-runtime-performance-baselines",
]:
    raise SystemExit("the shared IntelliJ runtime job must retain both test proof sets")
PY

blocking_required_task_model="${scratch_dir}/blocking-required-task-timing.json"
python3 - "$model" "$blocking_required_task_model" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
document = json.loads(source.read_text(encoding="utf-8"))
candidate = document["candidate"]
canary_ids = set(candidate["canaryTaskIds"])
for task in candidate["tasks"]:
    if task["id"] not in canary_ids:
        task["durationSamplesSeconds"] *= 5
candidate["observedWorkflowDurationSamplesSeconds"] *= 5
document["expectations"]["timingEvidenceMode"] = "blocking"
document["expectations"]["maximumMedianModelDriftRatio"] = 1
target.write_text(json.dumps(document), encoding="utf-8")
PY

blocking_required_task_report="${scratch_dir}/blocking-required-task-report.json"
set +e
python3 "$checker" "$blocking_required_task_model" >"$blocking_required_task_report"
blocking_required_task_status=$?
set -e
[[ "$blocking_required_task_status" -eq 1 ]] \
  || die "projected candidate timing must fail blocking evidence with exit 1"
python3 - "$blocking_required_task_report" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report["status"] != "fail":
    raise SystemExit("projected candidate timing must not satisfy blocking evidence")
if not any("candidate required tasks below minimumTaskSamples" in finding for finding in report["failures"]):
    raise SystemExit("blocking evidence must reject candidate tasks without declared runs")
if "candidate workflow is below minimumWorkflowSamples" not in report["failures"]:
    raise SystemExit("blocking evidence must reject candidate workflow timing without declared runs")
PY

lost_output_model="${scratch_dir}/lost-output.json"
python3 - "$model" "$lost_output_model" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
document = json.loads(source.read_text(encoding="utf-8"))
document["candidate"]["tasks"][0]["outputs"].pop()
target.write_text(json.dumps(document), encoding="utf-8")
PY

lost_output_report="${scratch_dir}/lost-output-report.json"
set +e
python3 "$checker" "$lost_output_model" >"$lost_output_report"
lost_output_status=$?
set -e
[[ "$lost_output_status" -eq 1 ]] \
  || die "output loss must fail with exit 1, received ${lost_output_status}"
python3 - "$lost_output_report" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report["status"] != "fail":
    raise SystemExit("output loss must produce a failed comparison")
if not report["comparison"]["missingOutputIds"]:
    raise SystemExit("output loss must name the missing proof identifier")
PY

invalid_replacement_model="${scratch_dir}/invalid-replacement.json"
python3 - "$model" "$invalid_replacement_model" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
document = json.loads(source.read_text(encoding="utf-8"))
retired_output = document["candidate"]["tasks"][0]["outputs"].pop()
document["retiredProofOutputReplacements"][retired_output] = "missing-proof"
target.write_text(json.dumps(document), encoding="utf-8")
PY

invalid_replacement_report="${scratch_dir}/invalid-replacement-report.json"
set +e
python3 "$checker" "$invalid_replacement_model" >"$invalid_replacement_report"
invalid_replacement_status=$?
set -e
[[ "$invalid_replacement_status" -eq 2 ]] \
  || die "an invalid replacement target must fail validation with exit 2, received ${invalid_replacement_status}"
python3 - "$invalid_replacement_report" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report["status"] != "invalid":
    raise SystemExit("an unknown replacement target must make the model invalid")
if "missing-proof" not in report["errors"][0]:
    raise SystemExit("model validation must name the unknown replacement target")
PY

printf '%s\n' 'CI workflow model contract passed'
