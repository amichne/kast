#!/usr/bin/env bash
set -euo pipefail

product_root="${KAST_INSTALLED_PRODUCT:?KAST_INSTALLED_PRODUCT must name the staged product}"
kast_executable="${product_root}/bin/kast"
indexer_executable="${product_root}/libexec/kast-indexer/kast-indexer"

fail() {
  echo "installed-product-test: $*" >&2
  exit 1
}

[[ -x "${kast_executable}" ]] || fail "installed kast executable is missing"
[[ -x "${indexer_executable}" ]] || fail "packaged kast-indexer executable is missing"

if find "${product_root}" -type f \( \
  -name 'cargo' -o \
  -name 'Cargo.toml' -o \
  -name 'Cargo.lock' -o \
  -name 'analysis-api-*.jar' -o \
  -name 'analysis-server-*.jar' -o \
  -name 'index-store-*.jar' \
\) -print -quit | grep -q .; then
  fail "retired or fallback artifact is present in the installed product"
fi

if grep -R -E '/build/(classes|resources)|/\.gradle/caches/' \
  "${product_root}/bin" "${product_root}/libexec" >/dev/null 2>&1; then
  fail "installed launch metadata contains a development classpath"
fi

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-installed-product.XXXXXX")"
runtime_directory="$(mktemp -d "${TMPDIR:-/tmp}/kast-installed-runtime.XXXXXX")"
cleanup() {
  if [[ -n "${indexer_pid:-}" ]]; then
    kill "${indexer_pid}" >/dev/null 2>&1 || true
    wait "${indexer_pid}" >/dev/null 2>&1 || true
  fi
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
}
EOF

export KAST_RUNTIME_DIRECTORY="${runtime_directory}"

workspace_json="$(cd "${fixture}" && "${kast_executable}" workspace inspect)"
python3 - "${workspace_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "workspace.inspect", document
assert document["status"] in {"complete", "qualified"}, document
PY

discover_json="$(
  cd "${fixture}" &&
    "${kast_executable}" symbol discover --query Greeter --limit 10
)"
candidate="$(python3 - "${discover_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "symbol.discover", document
assert document["status"] in {"complete", "qualified"}, document
print(document["candidateSelectors"][0])
PY
)"

resolve_json="$(
  cd "${fixture}" &&
    "${kast_executable}" symbol resolve --candidate "${candidate}"
)"
exact_selector="$(python3 - "${resolve_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "symbol.resolve", document
assert document["status"] == "complete", document
print(document["exactSelector"])
PY
)"

describe_json="$(
  cd "${fixture}" &&
    "${kast_executable}" symbol describe --selector "${exact_selector}"
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
  cd "${fixture}" &&
    "${kast_executable}" change plan \
      --intent add-declaration \
      --target "${exact_selector}" \
      --declaration 'fun farewell(): String = "goodbye"'
)"
plan_identity="$(python3 - "${plan_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "change.plan", document
assert document["status"] == "complete", document
print(document["planIdentity"])
PY
)"

apply_json="$(
  cd "${fixture}" &&
    "${kast_executable}" change apply --plan "${plan_identity}"
)"
application_identity="$(python3 - "${apply_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["operation"] == "change.apply", document
assert document["status"] == "complete", document
print(document["applicationIdentity"])
PY
)"

verify_json="$(
  cd "${fixture}" &&
    "${kast_executable}" change verify --application "${application_identity}"
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
  fail "verified mutation did not reach the fixture source"
