#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "install-local-test: $*" >&2
  exit 1
}

fixture="$(mktemp -d "${TMPDIR:-/tmp}/kast-install-local.XXXXXX")"
install_prefix="${fixture}/installed"
relocated_prefix="${fixture}/relocated"
sentinel="${fixture}/symlink-target"

cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT

mkdir -p "${install_prefix}/bin"
printf '%s\n' 'must remain unchanged' >"${sentinel}"
ln -s "${sentinel}" "${install_prefix}/bin/kast"

./gradlew installLocal -PkastLocalPrefix="${install_prefix}"

[[ -x "${install_prefix}/bin/kast" ]] || fail "launcher is missing or not executable"
[[ ! -L "${install_prefix}/bin/kast" ]] || fail "launcher remained a symbolic link"
[[ "$(<"${sentinel}")" == "must remain unchanged" ]] ||
  fail "installation followed and modified the previous launcher symlink"
[[ -x "${install_prefix}/share/kast/control/bin/kast" ]] ||
  fail "control product is missing"

runtime_archive_count="$(
  find "${install_prefix}/share/kast/runtime" -mindepth 1 -maxdepth 1 \
    -type f -name 'kast-semantic-runtime-*-macos-aarch64.zip' | wc -l | tr -d ' '
)"
[[ "${runtime_archive_count}" == "1" ]] ||
  fail "expected exactly one semantic runtime archive"

if grep -F "${PWD}/build" "${install_prefix}/bin/kast" >/dev/null; then
  fail "launcher refers to the repository build directory"
fi

mv "${install_prefix}" "${relocated_prefix}"
"${relocated_prefix}/bin/kast" --version >/dev/null
schema_json="$("${relocated_prefix}/bin/kast" --schema)"

python3 - "${schema_json}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert len(document["operationRegistry"]["operationIds"]) == 11, document
assert document["semanticRuntime"]["runtimeId"].startswith("sha256:"), document
PY

echo "install-local-test: PASS"
