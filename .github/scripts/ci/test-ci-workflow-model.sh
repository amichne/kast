#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'CI workflow model contract: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
checker="${repo_root}/.github/scripts/ci/ci_workflow_model.py"
model="${repo_root}/.github/ci/issue-401-workflow-model.json"
workflow="${repo_root}/.github/workflows/ci.yml"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ci-workflow-model.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT

[[ -f "$model" ]] || die "missing authoritative graph model: ${model}"
for retired_workflow in \
  ci-build-and-test.yml \
  ci-auxiliary-controls.yml \
  seed-gradle-ro-cache.yml; do
  [[ ! -e "${repo_root}/.github/workflows/${retired_workflow}" ]] \
    || die "retired workflow remains: ${retired_workflow}"
done

[[ "$(grep -Ec '^[[:space:]]*cache-read-only: false$' "$workflow")" -eq 1 ]] \
  || die "the inlined JVM test job must own the sole PR Gradle cache write"
! grep -Fq 'CI_AUX_' "$workflow" || die "CI must not depend on mutable auxiliary flags"

for required_job in \
  build-and-test-linux \
  source-bound-indexer \
  source-bound-linux-setup \
  install-linux-setup \
  indexer-performance; do
  grep -Eq "^  ${required_job}:$" "$workflow" \
    || die "missing required CI job: ${required_job}"
done

for retired_job in \
  runtime-contracts \
  prepared-generation \
  prepared-ubuntu-debian-bundle \
  analysis-server-transport \
  install-ubuntu-debian-container; do
  ! grep -Eq "^  ${retired_job}:$" "$workflow" \
    || die "redundant CI job remains: ${retired_job}"
done

grep -Fq ':indexer:test' "$workflow" || die "JVM CI must test the indexer"
grep -Fq ':indexer:portableDistZip' "$workflow" || die "CI must build the portable indexer"
grep -Fq ':indexer:verifyPortableDistLayout' "$workflow" || die "CI must verify the portable indexer layout"
grep -Fq 'kast-indexer-linux-x64' "$workflow" || die "CI must retain the source-bound indexer artifact"
grep -Fq 'kast-setup-linux-x64' "$workflow" || die "CI must retain the canonical Linux setup bundle"
grep -Fq 'setup_manifest_version="v0.7.11-ci"' "$workflow" \
  || die "CI setup activation must use a doctor-compatible indexer version"
grep -Fq -- '--version "$setup_manifest_version"' "$workflow" \
  || die "CI setup packaging must write the doctor-compatible manifest version"
grep -Fq 'setup_asset="dist/kast-linux-x64-v0.0.0-ci.tar.gz"' "$workflow" \
  || die "CI setup artifact naming must remain source-bound"
! grep -Fq 'container-image:' "$workflow" || die "CI must not advertise containers it does not run"

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
if report["comparison"]["taskCountIncrease"] != -4:
    raise SystemExit("the cleanup must remove four normalized CI executions")
if report["candidate"]["pullRequestTaskCount"] != 11:
    raise SystemExit("the normalized CI graph must contain eleven executions")
if report["candidate"]["fanoutGateSeconds"] > 90:
    raise SystemExit("the modeled static fan-out gate must not exceed 90 seconds")
if report["candidate"]["canaryTaskIds"]:
    raise SystemExit("pull-request CI must not model an off-path canary")

candidate_tasks = {task["id"]: task for task in model["candidate"]["tasks"]}
expected_tasks = {
    "workflow-contracts",
    "local-authority-contracts",
    "runtime-compatibility-contract",
    "maven-publication-contract",
    "rust-cli",
    "source-bound-cli",
    "build-and-test-linux",
    "source-bound-indexer",
    "source-bound-linux-setup",
    "install-linux-setup",
    "indexer-performance",
}
if set(candidate_tasks) != expected_tasks:
    raise SystemExit(f"unexpected candidate task inventory: {sorted(candidate_tasks)}")
if set(candidate_tasks["source-bound-linux-setup"]["needs"]) != {
    "workflow-contracts",
    "source-bound-cli",
    "source-bound-indexer",
}:
    raise SystemExit("the Linux setup bundle must consume only source-attested producers")
if candidate_tasks["install-linux-setup"]["needs"] != ["source-bound-linux-setup"]:
    raise SystemExit("the activation proof must consume only the canonical Linux setup bundle")
if candidate_tasks["indexer-performance"]["outputs"] != [
    "indexer-performance-baselines"
]:
    raise SystemExit("indexer performance must remain a distinct proof")
if report["candidate"]["provisionalTaskIds"] != sorted(candidate_tasks):
    raise SystemExit("candidate timings must remain provisional until successful runs exist")
PY

lost_output_model="${scratch_dir}/lost-output.json"
python3 - "$model" "$lost_output_model" <<'PY'
import json
import sys
from pathlib import Path

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
document["candidate"]["tasks"][0]["outputs"].pop()
Path(sys.argv[2]).write_text(json.dumps(document), encoding="utf-8")
PY

set +e
python3 "$checker" "$lost_output_model" >"${scratch_dir}/lost-output-report.json"
lost_output_status=$?
set -e
[[ "$lost_output_status" -eq 1 ]] \
  || die "output loss must fail with exit 1, received ${lost_output_status}"

invalid_replacement_model="${scratch_dir}/invalid-replacement.json"
python3 - "$model" "$invalid_replacement_model" <<'PY'
import json
import sys
from pathlib import Path

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
retired_output = next(iter(document["retiredProofOutputReplacements"]))
document["retiredProofOutputReplacements"][retired_output] = "missing-proof"
Path(sys.argv[2]).write_text(json.dumps(document), encoding="utf-8")
PY

set +e
python3 "$checker" "$invalid_replacement_model" >"${scratch_dir}/invalid-replacement-report.json"
invalid_replacement_status=$?
set -e
[[ "$invalid_replacement_status" -eq 2 ]] \
  || die "an invalid replacement target must fail validation with exit 2"

printf '%s\n' 'CI workflow model contract passed'
