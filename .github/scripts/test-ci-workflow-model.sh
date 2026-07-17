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
    raise SystemExit("candidate proof outputs must exactly match the baseline")
if report["comparison"]["taskCountIncrease"] != 1:
    raise SystemExit("the fanout split must add exactly one execution node")
if report["candidate"]["fanoutGateSeconds"] > 90:
    raise SystemExit("the modeled static fanout gate must not exceed 90 seconds")
if not any("provisional" in warning for warning in report["warnings"]):
    raise SystemExit("timing evidence must remain explicitly provisional below five samples")
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

printf '%s\n' 'CI workflow model contract passed'
