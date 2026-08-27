#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "published-hosted-delivery: $*" >&2
  exit 1
}

release=""
repository=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) release="${2:?}"; shift 2 ;;
    --repository) repository="${2:?}"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "release must be v<version>"
[[ "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  fail "repository must be owner/name"
version="${release#v}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-published-hosted.XXXXXX")"
cleanup() {
  rm -rf -- "${temporary_root}"
}
trap cleanup EXIT
install_root="${temporary_root}/install"
bin_root="${temporary_root}/bin"
plugin_root="${temporary_root}/ide/plugins"
home="${temporary_root}/home"
mkdir -p "${home}"

HOME="${home}" KAST_INSTALL_ROOT="${install_root}" KAST_BIN_DIR="${bin_root}" \
KAST_IDE_PLUGIN_DIRECTORY="${plugin_root}" \
  bash "${repository_root}/install.sh" install \
    --version "${version}" --repository "${repository}"

kast="${bin_root}/kast"
control_root="${install_root}/versions/${version}"
[[ -x "${kast}" ]] || fail "public installer did not create the command"
[[ -L "${plugin_root}/kast-indexer" ]] || fail "public installer did not link the hosted plugin"
[[ ! -e "${control_root}/share/kast/semantic-runtime.json" ]] ||
  fail "published install retained a semantic-runtime manifest"
if find "${control_root}" \( -name 'idea-home' -o -name 'semantic-runtime*.zip' \) \
  -print -quit | grep -q .; then
  fail "published install retained isolated-runtime payload"
fi
[[ "$("${kast}" --version)" == "kast ${version} (IDE-hosted)" ]] ||
  fail "installed version identity is not hosted"
schema="$("${kast}" --schema)"
python3 - "${schema}" "${control_root}/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
assert "semanticRuntime" not in document, document
assert document["operationRegistry"] == json.loads(Path(sys.argv[2]).read_text()), document
PY

fixture="${temporary_root}/workspace"
git init -q "${fixture}"
before="$(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)"
if (cd "${fixture}" && "${kast}" workspace inspect) >/dev/null 2>&1; then
  fail "missing hosted endpoint did not fail closed"
fi
after="$(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)"
[[ "${before}" == "${after}" ]] || fail "published command started an isolated indexer"

echo "published-hosted-delivery: ok ${release}"
