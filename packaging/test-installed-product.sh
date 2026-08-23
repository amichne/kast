#!/usr/bin/env bash
set -euo pipefail

product_root="${KAST_INSTALLED_PRODUCT:?KAST_INSTALLED_PRODUCT must name the staged product}"
control_archive="${KAST_CONTROL_ARCHIVE:?KAST_CONTROL_ARCHIVE must name the control archive}"
runtime_archive="${KAST_SEMANTIC_RUNTIME_ARCHIVE:?KAST_SEMANTIC_RUNTIME_ARCHIVE must name the runtime archive}"
project_root="${KAST_PROJECT_ROOT:?KAST_PROJECT_ROOT must name the checkout}"
report_directory="${KAST_INSTALLED_REPORT_DIRECTORY:?KAST_INSTALLED_REPORT_DIRECTORY must name the report directory}"
kast_executable="${product_root}/bin/kast"

fail() {
  echo "installed-product-test: $*" >&2
  exit 1
}

[[ -x "${kast_executable}" ]] || fail "installed kast executable is missing"
[[ -f "${control_archive}" ]] || fail "control archive is missing"
[[ -f "${runtime_archive}" ]] || fail "semantic runtime archive is missing"

if find "${product_root}" \( -name 'kast-indexer' -o -name 'idea-home' \) -print -quit |
  grep -q .; then
  fail "control product contains semantic runtime payload"
fi

if find "${product_root}" -type f \( \
  -name 'analysis-api-*.jar' -o \
  -name 'analysis-server-*.jar' -o \
  -name 'index-store-*.jar' \
\) -print -quit | grep -q .; then
  fail "retired or fallback artifact is present in the installed product"
fi

if grep -R -E '/build/(classes|resources)|/\.gradle/caches/' \
  "${product_root}/bin" >/dev/null 2>&1; then
  fail "installed launch metadata contains a development classpath"
fi

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-installed-product.XXXXXX")"
runtime_directory="$(mktemp -d "${TMPDIR:-/tmp}/kr.XXXXXX")"
control_root="${runtime_directory}/control"
runtime_store="${runtime_directory}/store"
server_root="${runtime_directory}/server"
server_port_file="${runtime_directory}/server.port"
server_request_log="${runtime_directory}/server.requests"
server_error_log="${runtime_directory}/server.stderr"
server_pid=""
mkdir -p "${control_root}" "${server_root}"
tar -xzf "${control_archive}" -C "${control_root}"
cp "${runtime_archive}" "${server_root}/"
kast_executable="${control_root}/bin/kast"
canonical_fixture="$(cd "${fixture}" && pwd -P)"
cleanup() {
  if [[ -n "${server_pid}" ]]; then
    kill "${server_pid}" >/dev/null 2>&1 || true
    wait "${server_pid}" >/dev/null 2>&1 || true
  fi
  while IFS= read -r indexer_pid; do
    [[ "${indexer_pid}" =~ ^[0-9]+$ ]] || continue
    indexer_command="$(ps -p "${indexer_pid}" -o command= 2>/dev/null || true)"
    if [[ "${indexer_command}" == *"io.github.amichne.kast.indexer.KastIndexerMainKt"* &&
      "${indexer_command}" == *"--workspace-root=${canonical_fixture}"* ]]; then
      kill "${indexer_pid}" >/dev/null 2>&1 || true
      for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "${indexer_pid}" >/dev/null 2>&1 || break
        sleep 0.1
      done
      indexer_command="$(ps -p "${indexer_pid}" -o command= 2>/dev/null || true)"
      if [[ "${indexer_command}" == *"io.github.amichne.kast.indexer.KastIndexerMainKt"* &&
        "${indexer_command}" == *"--workspace-root=${canonical_fixture}"* ]]; then
        kill -KILL "${indexer_pid}" >/dev/null 2>&1 || true
      fi
    fi
  done < <(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)
  rm -rf -- "${fixture}" "${runtime_directory}"
}
trap cleanup EXIT

mkdir -p "${fixture}/src/main/kotlin/example"
cat >"${fixture}/settings.gradle.kts" <<'EOF'
rootProject.name = "installed-product-fixture"
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

export KAST_RUNTIME_DIRECTORY="${runtime_directory}"
export KAST_RUNTIME_STORE="${runtime_store}"
unset KAST_RUNTIME_ARCHIVE

KAST_RUNTIME_ARCHIVE='' KAST_RUNTIME_STORE="${runtime_directory}/must-not-exist" \
  "${kast_executable}" --help >/dev/null
KAST_RUNTIME_ARCHIVE='' KAST_RUNTIME_STORE="${runtime_directory}/must-not-exist" \
  "${kast_executable}" --version >/dev/null
schema_json="$(
  KAST_RUNTIME_ARCHIVE='' KAST_RUNTIME_STORE="${runtime_directory}/must-not-exist" \
    "${kast_executable}" --schema
)"
python3 - "${schema_json}" "${control_root}/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
expected_registry = json.loads(Path(sys.argv[2]).read_text())
assert document["operationRegistry"] == expected_registry, document
assert document["semanticRuntime"]["runtimeId"].startswith("sha256:"), document
PY
[[ ! -e "${runtime_directory}/must-not-exist" ]] ||
  fail "local metadata touched the semantic runtime store"

python3 - "${server_root}" "${server_port_file}" "${server_request_log}" \
  2>"${server_error_log}" <<'PY' &
import functools
import http.server
from pathlib import Path
import sys

root = sys.argv[1]
port_file = Path(sys.argv[2])
request_log = Path(sys.argv[3])

class RecordingHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        request_log.write_text(
            request_log.read_text() + self.requestline + "\n"
            if request_log.exists()
            else self.requestline + "\n"
        )

handler = functools.partial(RecordingHandler, directory=root)
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
port_file.write_text(str(server.server_port))
server.serve_forever()
PY
server_pid="$!"
server_start_deadline=$((SECONDS + 60))
while [[ ! -s "${server_port_file}" && ${SECONDS} -lt ${server_start_deadline} ]]; do
  if ! kill -0 "${server_pid}" >/dev/null 2>&1; then
    server_error="$(<"${server_error_log}")"
    fail "managed runtime HTTP server exited: ${server_error:-no stderr}"
  fi
  sleep 0.1
done
if [[ ! -s "${server_port_file}" ]]; then
  server_error="$(<"${server_error_log}")"
  fail "managed runtime HTTP server did not become ready: ${server_error:-no stderr}"
fi
runtime_url="http://127.0.0.1:$(<"${server_port_file}")/$(basename "${runtime_archive}")"
python3 - "${control_root}/share/kast/semantic-runtime.json" "${runtime_url}" <<'PY'
import json
from pathlib import Path
import sys

manifest_path = Path(sys.argv[1])
document = json.loads(manifest_path.read_text())
document["archive"]["url"] = sys.argv[2]
manifest_path.write_text(json.dumps(document, separators=(",", ":")))
PY

workspace_json="$(cd "${fixture}" && "${kast_executable}" workspace inspect)"
python3 - "${workspace_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "workspace.inspect", document
assert document["status"] in {"complete", "qualified"}, document
PY

runtime_count="$(find "${runtime_store}" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[[ "${runtime_count}" == "1" ]] || fail "cold demand did not install exactly one runtime"
find "${runtime_store}" -name '*.partial*' -print -quit | grep -q . &&
  fail "cold demand left partial runtime state"
request_count="$(wc -l <"${server_request_log}" | tr -d ' ')"
[[ "${request_count}" == "1" ]] ||
  fail "cold managed demand fetched the runtime ${request_count} times"
runtime_snapshot_before="$(
  find "${runtime_store}" -type f -exec stat -f '%N:%z:%m' {} + | sort
)"
kill "${server_pid}" >/dev/null 2>&1 || true
wait "${server_pid}" >/dev/null 2>&1 || true
server_pid=""
rm -f -- "${server_root}/$(basename "${runtime_archive}")"

mkdir -p -- "${report_directory}"
python3 "${project_root}/packaging/topology_installed_acceptance.py" run \
  --kast "${kast_executable}" \
  --workspace "${fixture}" \
  --registry "${control_root}/share/kast/operation-registry.json" \
  --report "${report_directory}/topology-installed-product.json"

runtime_snapshot_after="$(
  find "${runtime_store}" -type f -exec stat -f '%N:%z:%m' {} + | sort
)"
[[ "${runtime_snapshot_before}" == "${runtime_snapshot_after}" ]] ||
  fail "warm semantic demand modified or re-extracted the installed runtime"
