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
mkdir -p "${install_prefix}/share/kast/runtime"
printf '%s\n' 'must remain unchanged' >"${sentinel}"
printf '%s\n' 'legacy runtime payload' >"${install_prefix}/share/kast/runtime/legacy.zip"
ln -s "${sentinel}" "${install_prefix}/bin/kast"

./gradlew installLocal -PkastLocalPrefix="${install_prefix}"

[[ -x "${install_prefix}/bin/kast" ]] || fail "launcher is missing or not executable"
[[ ! -L "${install_prefix}/bin/kast" ]] || fail "launcher remained a symbolic link"
[[ "$(<"${sentinel}")" == "must remain unchanged" ]] ||
  fail "installation followed and modified the previous launcher symlink"
[[ -x "${install_prefix}/share/kast/control/bin/kast" ]] ||
  fail "control product is missing"
[[ -f "${install_prefix}/share/kast/control/share/kast/semantic-runtime.json" ]] ||
  fail "control is missing the sidecar manifest"

runtime_archive="$(find "${install_prefix}/share/kast/runtime" -maxdepth 1 -type f \
  -name 'kast-semantic-runtime-*.zip' -print -quit)"
[[ -n "${runtime_archive}" ]] || fail "small private sidecar payload is missing"
grep -Fq 'KAST_RUNTIME_ARCHIVE' "${install_prefix}/bin/kast" ||
  fail "launcher does not bind the installed sidecar payload"
if grep -Eq '(^|/)idea-home/|product-info\.json|kast-ide-plugin' \
  < <(unzip -Z1 "${runtime_archive}"); then
  fail "local sidecar payload contains IDEA or a public plugin"
fi

if grep -F "${PWD}/build" "${install_prefix}/bin/kast" >/dev/null; then
  fail "launcher refers to the repository build directory"
fi

mv "${install_prefix}" "${relocated_prefix}"
"${relocated_prefix}/bin/kast" --version >/dev/null
schema_json="$("${relocated_prefix}/bin/kast" --schema)"

python3 - "${schema_json}" \
  "${relocated_prefix}/share/kast/control/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
expected_registry = json.loads(Path(sys.argv[2]).read_text())
assert document["operationRegistry"] == expected_registry, document
PY

echo "install-local-test: PASS"
