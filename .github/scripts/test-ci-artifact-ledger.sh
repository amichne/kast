#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

repo_root="$(resolve_repo_root)"
verifier="${repo_root}/scripts/verify-ci-artifact-ledger.py"
[[ -x "$verifier" ]] || die "CI artifact ledger verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-ci-ledger.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

artifact="${scratch_dir}/artifact.txt"
ledger="${scratch_dir}/ledger.json"
git_sha="0123456789abcdef0123456789abcdef01234567"

printf 'artifact contents\n' > "$artifact"

"$verifier" record \
  --output "$ledger" \
  --git-sha "$git_sha" \
  --source-ref refs/heads/main \
  --workflow-run-id 12345 \
  --artifact-kind ci-test-artifact \
  --artifact-name ci-test-artifact \
  --artifact-path "$artifact" \
  --producer-job test-producer \
  --build-command-id test-command

"$verifier" verify \
  --ledger "$ledger" \
  --git-sha "$git_sha" \
  --require-kind ci-test-artifact \
  --artifact "ci-test-artifact=${artifact}"

printf 'tampered\n' >> "$artifact"
if "$verifier" verify \
  --ledger "$ledger" \
  --git-sha "$git_sha" \
  --artifact "ci-test-artifact=${artifact}" \
  >"${scratch_dir}/tampered.out" 2>"${scratch_dir}/tampered.err"; then
  die "tampered artifact unexpectedly verified"
fi
grep -Fq "sha256 mismatch" "${scratch_dir}/tampered.err" \
  || die "tampered artifact failure did not mention sha256 mismatch"

python3 - "$ledger" "${scratch_dir}/duplicate.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["artifacts"].append(dict(payload["artifacts"][0]))
Path(sys.argv[2]).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

if "$verifier" verify --ledger "${scratch_dir}/duplicate.json" >"${scratch_dir}/duplicate.out" 2>"${scratch_dir}/duplicate.err"; then
  die "duplicate artifact kind unexpectedly verified"
fi
grep -Fq "duplicate artifactKind" "${scratch_dir}/duplicate.err" \
  || die "duplicate failure did not mention duplicate artifactKind"

python3 - "$ledger" "${scratch_dir}/wrong-schema.json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["schemaVersion"] = 999
Path(sys.argv[2]).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

if "$verifier" verify --ledger "${scratch_dir}/wrong-schema.json" >"${scratch_dir}/schema.out" 2>"${scratch_dir}/schema.err"; then
  die "wrong schema version unexpectedly verified"
fi
grep -Fq "schemaVersion" "${scratch_dir}/schema.err" \
  || die "schema failure did not mention schemaVersion"

printf '%s\n' "CI artifact ledger test passed"
