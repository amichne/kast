#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'CI workflow model contract: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
checker="${repo_root}/.github/scripts/ci_workflow_model.py"
model="${repo_root}/.github/ci/issue-401-workflow-model.json"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ci-workflow-model.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT

[[ -f "$model" ]] || die "missing authoritative graph model: ${model}"

report="${scratch_dir}/report.json"
python3 "$checker" "$model" >"$report"
python3 - "$report" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report["status"] != "provisional":
    raise SystemExit(f"expected provisional timing evidence, received {report['status']}")
if not report["comparison"]["outputEquivalent"]:
    raise SystemExit("candidate proof outputs must match or have an explicit replacement")
expected_replacements = {
    "headless-portable-no-fat-jar-macos": "headless-portable-no-fat-jar-linux",
    "headless-portable-artifact-macos": "headless-portable-artifact-linux",
    "ci-artifact-ledger-headless-macos": "ci-artifact-ledger-headless-linux",
}
actual_replacements = report["comparison"]["retiredProofOutputReplacements"]
if actual_replacements != expected_replacements:
    raise SystemExit(f"retired macOS proofs must name their Linux replacements: {actual_replacements}")
if report["comparison"]["taskCountIncrease"] != 4:
    raise SystemExit("the final graph must add exactly four execution nodes after removing the duplicate macOS producer")
if report["candidate"]["pullRequestTaskCount"] != report["baseline"]["pullRequestTaskCount"] + 3:
    raise SystemExit("the final graph must add exactly three pull-request execution nodes after relocating the full canary and deleting the macOS duplicate")
if report["candidate"]["fanoutGateSeconds"] > 90:
    raise SystemExit("the modeled static fanout gate must not exceed 90 seconds")
if report["candidate"]["canaryTaskIds"] != ["local-development-semantic-e2e"]:
    raise SystemExit("the full installed semantic E2E must remain modeled as a canary")
if "local-development-semantic-e2e" in report["candidate"]["criticalPathTaskIds"]:
    raise SystemExit("the full installed semantic E2E must not remain on the pull-request critical path")
if not any("provisional" in warning for warning in report["warnings"]):
    raise SystemExit("timing evidence must remain explicitly provisional below five samples")
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
if [[ "$blocking_required_task_status" -ne 0 ]]; then
  cat "$blocking_required_task_report" >&2
  die "blocking required-task timing model failed with exit ${blocking_required_task_status}"
fi
python3 - "$blocking_required_task_report" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report["status"] != "pass":
    raise SystemExit(
        "blocking PR timing must not require five samples from an off-PR canary: "
        + "; ".join(report["failures"])
    )
if not any(
    warning.startswith(
        "baseline required tasks below minimumTaskSamples: installed-semantic-fixture"
    )
    for warning in report["warnings"]
):
    raise SystemExit("undersampled historical baseline tasks must remain explicit warnings")
PY

required_canary_model="${scratch_dir}/required-canary.json"
python3 - "$model" "$required_canary_model" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
document = json.loads(source.read_text(encoding="utf-8"))
document["candidate"]["canaryTaskIds"] = []
target.write_text(json.dumps(document), encoding="utf-8")
PY

required_canary_report="${scratch_dir}/required-canary-report.json"
python3 "$checker" "$required_canary_model" >"$required_canary_report"
python3 - "$report" "$required_canary_report" <<'PY'
import json
import sys
from pathlib import Path

canary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
required = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
if "local-development-semantic-e2e" not in required["candidate"]["criticalPathTaskIds"]:
    raise SystemExit("clearing canaryTaskIds must restore the E2E to the required critical path")
if required["candidate"]["criticalPathSeconds"] <= canary["candidate"]["criticalPathSeconds"]:
    raise SystemExit("a required full E2E must lengthen the modeled pull-request critical path")
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

unexplained_retirement_model="${scratch_dir}/unexplained-retirement.json"
python3 - "$model" "$unexplained_retirement_model" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
document = json.loads(source.read_text(encoding="utf-8"))
document["retiredProofOutputReplacements"].pop("headless-portable-no-fat-jar-macos")
target.write_text(json.dumps(document), encoding="utf-8")
PY

unexplained_retirement_report="${scratch_dir}/unexplained-retirement-report.json"
set +e
python3 "$checker" "$unexplained_retirement_model" >"$unexplained_retirement_report"
unexplained_retirement_status=$?
set -e
[[ "$unexplained_retirement_status" -eq 1 ]] \
  || die "an unexplained retired proof must fail with exit 1, received ${unexplained_retirement_status}"
python3 - "$unexplained_retirement_report" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report["status"] != "fail":
    raise SystemExit("an unexplained retired proof must fail comparison")
if "headless-portable-no-fat-jar-macos" not in report["comparison"]["missingOutputIds"]:
    raise SystemExit("the failed comparison must name the unexplained retired proof")
PY

invalid_replacement_model="${scratch_dir}/invalid-replacement.json"
python3 - "$model" "$invalid_replacement_model" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
document = json.loads(source.read_text(encoding="utf-8"))
document["retiredProofOutputReplacements"]["ci-artifact-ledger-headless-macos"] = "missing-proof"
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
