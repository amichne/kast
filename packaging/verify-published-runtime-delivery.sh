#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "published-runtime-delivery: $*" >&2
  exit 1
}

release=""
repository=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      [[ $# -ge 2 ]] || fail "--release requires a value"
      release="$2"
      shift 2
      ;;
    --repository)
      [[ $# -ge 2 ]] || fail "--repository requires a value"
      repository="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "release must be v<major>.<minor>.<patch>"
[[ "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  fail "repository must be owner/name"
version="${release#v}"
control_name="kast-control-v${version}-macos-aarch64.tar.gz"
runtime_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
release_url="https://github.com/${repository}/releases/download/${release}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-published-release.XXXXXX")"
download_root="${temporary_root}/download"
control_root="${temporary_root}/control"
runtime_root="${temporary_root}/runtime"
fixture="${temporary_root}/workspace"
mkdir -p "${download_root}" "${control_root}" "${runtime_root}" \
  "${fixture}/src/main/kotlin/example"
canonical_fixture="$(cd "${fixture}" && pwd -P)"

cleanup() {
  while IFS= read -r indexer_pid; do
    [[ "${indexer_pid}" =~ ^[0-9]+$ ]] || continue
    indexer_command="$(ps -p "${indexer_pid}" -o command= 2>/dev/null || true)"
    if [[ "${indexer_command}" == *"io.github.amichne.kast.indexer.KastIndexerMainKt"* &&
      "${indexer_command}" == *"--workspace-root=${canonical_fixture}"* ]]; then
      kill "${indexer_pid}" >/dev/null 2>&1 || true
    fi
  done < <(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)
  rm -rf -- "${temporary_root}"
}
trap cleanup EXIT

for asset in \
  "${control_name}" \
  "${control_name}.sha256" \
  "${runtime_name}" \
  "${runtime_name}.sha256"; do
  curl \
    --fail \
    --location \
    --retry 5 \
    --retry-delay 2 \
    --retry-all-errors \
    --output "${download_root}/${asset}" \
    "${release_url}/${asset}"
done

python3 "${repository_root}/.github/scripts/release/verify-assets.py" \
  --directory "${download_root}" \
  --release "${release}" \
  --repository "${repository}"
tar -xzf "${download_root}/${control_name}" -C "${control_root}"
kast="${control_root}/bin/kast"
[[ -x "${kast}" ]] || fail "downloaded control launcher is not executable"

cat >"${fixture}/settings.gradle.kts" <<'EOF'
rootProject.name = "published-runtime-delivery"
EOF
cat >"${fixture}/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.3.10"
}

repositories {
    mavenCentral()
}
EOF
cat >"${fixture}/src/main/kotlin/example/Greeter.kt" <<'EOF'
package example

class Greeter {
    fun greeting(): String = "hello"
}
EOF

export KAST_RUNTIME_DIRECTORY="${runtime_root}/endpoints"
export KAST_RUNTIME_STORE="${runtime_root}/store"
unset KAST_RUNTIME_ARCHIVE

metadata_store="${runtime_root}/must-not-exist"
KAST_RUNTIME_STORE="${metadata_store}" "${kast}" --help >/dev/null
KAST_RUNTIME_STORE="${metadata_store}" "${kast}" --version >/dev/null
schema_json="$(KAST_RUNTIME_STORE="${metadata_store}" "${kast}" --schema)"
[[ ! -e "${metadata_store}" ]] || fail "local metadata touched the runtime store"
python3 - "${schema_json}" "${release_url}/${runtime_name}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert len(document["operationRegistry"]["operationIds"]) == 11, document
assert document["semanticRuntime"]["archive"]["url"] == sys.argv[2], document
assert document["semanticRuntime"]["runtimeId"].startswith("sha256:"), document
PY

workspace_json="$(cd "${fixture}" && "${kast}" workspace inspect)"
python3 - "${workspace_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "workspace.inspect", document
assert document["status"] in {"complete", "qualified"}, document
PY

runtime_count="$(find "${KAST_RUNTIME_STORE}" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[[ "${runtime_count}" == "1" ]] || fail "cold demand did not install exactly one runtime"
if find "${KAST_RUNTIME_STORE}" -name '*.partial*' -print -quit | grep -q .; then
  fail "cold demand left partial runtime state"
fi
runtime_snapshot_before="$(
  find "${KAST_RUNTIME_STORE}" -type f -exec stat -f '%N:%z:%m' {} + | sort
)"

python3 - "${control_root}/share/kast/semantic-runtime.json" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
document = json.loads(path.read_text())
document["archive"]["url"] = "http://127.0.0.1:9/unavailable"
path.write_text(json.dumps(document, separators=(",", ":")))
PY

discover_json="$(
  cd "${fixture}" && "${kast}" symbol discover --query Greeter --limit 1000
)"
candidate="$(python3 - "${discover_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "symbol.discover", document
assert document["status"] in {"complete", "qualified"}, document
assert document["candidateSelectors"], document
print(document["candidateSelectors"][0])
PY
)"

resolve_json="$(
  cd "${fixture}" && "${kast}" symbol resolve --candidate "${candidate}"
)"
selector="$(python3 - "${resolve_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "symbol.resolve", document
assert document["status"] == "complete", document
print(document["exactSelector"])
PY
)"

describe_json="$(
  cd "${fixture}" && "${kast}" symbol describe --selector "${selector}"
)"
python3 - "${describe_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "symbol.describe", document
assert document["status"] == "complete", document
assert "Greeter" in document["declaration"], document
PY

plan_json="$(
  cd "${fixture}" && "${kast}" change plan \
    --intent add-declaration \
    --target "${selector}" \
    --declaration 'fun farewell(): String = "goodbye"'
)"
plan="$(python3 - "${plan_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "change.plan", document
assert document["status"] == "complete", document
print(document["planIdentity"])
PY
)"

apply_json="$(cd "${fixture}" && "${kast}" change apply --plan "${plan}")"
application="$(python3 - "${apply_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "change.apply", document
assert document["status"] == "complete", document
print(document["applicationIdentity"])
PY
)"

verify_json="$(
  cd "${fixture}" && "${kast}" change verify --application "${application}"
)"
python3 - "${verify_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "change.verify", document
assert document["status"] == "complete", document
assert document["receiptIdentity"], document
PY

grep -F 'fun farewell(): String = "goodbye"' \
  "${fixture}/src/main/kotlin/example/Greeter.kt" >/dev/null ||
  fail "verified mutation did not reach the published fixture"
runtime_snapshot_after="$(
  find "${KAST_RUNTIME_STORE}" -type f -exec stat -f '%N:%z:%m' {} + | sort
)"
[[ "${runtime_snapshot_before}" == "${runtime_snapshot_after}" ]] ||
  fail "warm demand downloaded or extracted the semantic runtime again"

echo "published-runtime-delivery: ok ${release}"
