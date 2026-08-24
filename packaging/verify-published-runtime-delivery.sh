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
runtime_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
release_url="https://github.com/${repository}/releases/download/${release}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-published-release.XXXXXX")"
install_root="${temporary_root}/install"
bin_root="${temporary_root}/bin"
control_root="${install_root}/versions/${version}"
runtime_root="${temporary_root}/runtime"
fixture="${temporary_root}/workspace"
endpoint_root="$(mktemp -d /tmp/kast-uds.XXXXXX)"
mkdir -p "${temporary_root}/home" "${runtime_root}" \
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
  rm -rf -- "${temporary_root}" "${endpoint_root}"
}
trap cleanup EXIT

HOME="${temporary_root}/home" \
KAST_INSTALL_ROOT="${install_root}" \
KAST_BIN_DIR="${bin_root}" \
KAST_RUNTIME_STORE="${runtime_root}/store" \
KAST_RUNTIME_DIRECTORY="${endpoint_root}" \
  bash "${repository_root}/install.sh" install \
    --version "${version}" \
    --repository "${repository}"
kast="${bin_root}/kast"
runtime_archive="${control_root}/share/kast/runtime/${runtime_name}"
[[ -x "${kast}" ]] || fail "public installer did not create an executable command"
[[ -f "${runtime_archive}" ]] ||
  fail "public installer did not download the semantic runtime"

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
    fun firstCaller(): String = greeting()
}
EOF

export KAST_RUNTIME_DIRECTORY="${endpoint_root}"
export KAST_RUNTIME_STORE="${runtime_root}/store"
unset KAST_RUNTIME_ARCHIVE

metadata_store="${runtime_root}/must-not-exist"
KAST_RUNTIME_STORE="${metadata_store}" "${kast}" --help >/dev/null
KAST_RUNTIME_STORE="${metadata_store}" "${kast}" --version >/dev/null
schema_json="$(KAST_RUNTIME_STORE="${metadata_store}" "${kast}" --schema)"
[[ ! -e "${metadata_store}" ]] || fail "local metadata touched the runtime store"
python3 - "${schema_json}" "${release_url}/${runtime_name}" \
  "${control_root}/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
expected_registry = json.loads(Path(sys.argv[3]).read_text())
assert document["operationRegistry"] == expected_registry, document
assert document["semanticRuntime"]["archive"]["url"] == sys.argv[2], document
assert document["semanticRuntime"]["runtimeId"].startswith("sha256:"), document
PY

python3 - "${control_root}/share/kast/semantic-runtime.json" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
document = json.loads(path.read_text())
document["archive"]["url"] = "http://127.0.0.1:9/unavailable"
path.write_text(json.dumps(document, separators=(",", ":")))
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

python3 "${repository_root}/packaging/topology_installed_acceptance.py" run \
  --kast "${kast}" \
  --workspace "${fixture}" \
  --registry "${control_root}/share/kast/operation-registry.json" \
  --report "${temporary_root}/topology-installed-product.json"

runtime_snapshot_after="$(
  find "${KAST_RUNTIME_STORE}" -type f -exec stat -f '%N:%z:%m' {} + | sort
)"
[[ "${runtime_snapshot_before}" == "${runtime_snapshot_after}" ]] ||
  fail "warm demand downloaded or extracted the semantic runtime again"

echo "published-runtime-delivery: ok ${release}"
