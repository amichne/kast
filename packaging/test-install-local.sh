#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "install-local-test: $*" >&2
  exit 1
}

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-install-local.XXXXXX")"
install_prefix="${fixture}/installed prefix"
relocated_prefix="${fixture}/relocated"
sentinel="${fixture}/symlink-target"

cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT

assert_task_absent() {
  local task_name="$1"
  local task_log="${fixture}/${task_name}.log"

  if ./gradlew help --task "${task_name}" --console=plain >"${task_log}" 2>&1; then
    fail "legacy Gradle task remains callable: ${task_name}"
  fi
  grep -Fq "Task '${task_name}' not found" "${task_log}" ||
    fail "Gradle task lookup failed for an unrelated reason: ${task_name}"
}

for legacy_task in \
  installLocalControl \
  installLocalSemanticRuntime \
  installLocalLauncher; do
  assert_task_absent "${legacy_task}"
done

mkdir -p "${install_prefix}/bin"
mkdir -p "${install_prefix}/share/kast/control"
mkdir -p "${install_prefix}/share/kast/runtime"
printf '%s\n' 'must remain unchanged' >"${sentinel}"
printf '%s\n' 'legacy control payload' >"${install_prefix}/share/kast/control/legacy.txt"
printf '%s\n' 'legacy runtime payload' >"${install_prefix}/share/kast/runtime/legacy.zip"
ln -s "${sentinel}" "${install_prefix}/bin/kast"

./gradlew installLocal -PkastLocalPrefix="${install_prefix}" --console=plain

[[ -x "${install_prefix}/bin/kast" ]] || fail "launcher is missing or not executable"
[[ ! -L "${install_prefix}/bin/kast" ]] || fail "launcher remained a symbolic link"
[[ "$(<"${sentinel}")" == "must remain unchanged" ]] ||
  fail "installation followed and modified the previous launcher symlink"
[[ ! -e "${install_prefix}/share/kast/control" ]] ||
  fail "legacy split control root remains"
[[ ! -e "${install_prefix}/share/kast/runtime" ]] ||
  fail "legacy split runtime root remains"

local_product="${install_prefix}/share/kast/local"
[[ -x "${local_product}/bin/kast" ]] ||
  fail "control product is missing"
[[ -f "${local_product}/share/kast/semantic-runtime.json" ]] ||
  fail "control is missing the sidecar manifest"

runtime_archive="$(find "${local_product}/share/kast/runtime" -maxdepth 1 -type f \
  -name 'kast-semantic-runtime-*.zip' -print -quit)"
[[ -n "${runtime_archive}" ]] || fail "small private sidecar payload is missing"
grep -Fq 'KAST_RUNTIME_ARCHIVE' "${install_prefix}/bin/kast" ||
  fail "launcher does not bind the installed sidecar payload"
grep -Fq 'share/kast/local' "${install_prefix}/bin/kast" ||
  fail "launcher does not bind the coherent local product"
if grep -Eq '(^|/)idea-home/|product-info\.json|kast-ide-plugin' \
  < <(unzip -Z1 "${runtime_archive}"); then
  fail "local sidecar payload contains IDEA or a public plugin"
fi

if grep -F "${PWD}/build" "${install_prefix}/bin/kast" >/dev/null; then
  fail "launcher refers to the repository build directory"
fi

printf '%s\n' 'stale product content' >"${local_product}/stale.txt"
./gradlew installLocal -PkastLocalPrefix="${install_prefix}" --console=plain
[[ ! -e "${local_product}/stale.txt" ]] ||
  fail "reinstallation retained stale product content"

mv "${install_prefix}" "${relocated_prefix}"
"${relocated_prefix}/bin/kast" --version >/dev/null
schema_json="$("${relocated_prefix}/bin/kast" --schema)"

python3 - "${schema_json}" \
  "${relocated_prefix}/share/kast/local/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
expected_registry = json.loads(Path(sys.argv[2]).read_text())
assert document["operationRegistry"] == expected_registry, document
PY

echo "install-local-test: PASS"
