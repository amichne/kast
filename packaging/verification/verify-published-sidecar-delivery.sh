#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "published-sidecar-delivery: $*" >&2
  exit 1
}

release=""
repository=""
idea_platform_home=""
java_home=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) release="${2:?}"; shift 2 ;;
    --repository) repository="${2:?}"; shift 2 ;;
    --idea-platform-home) idea_platform_home="${2:?}"; shift 2 ;;
    --java-home) java_home="${2:?}"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ "${release}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "release must be v<version>"
[[ "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  fail "repository must be owner/name"
case "${idea_platform_home}" in
  /*) ;;
  *) fail "IDEA platform home must be absolute" ;;
esac
case "${java_home}" in
  /*) ;;
  *) fail "Java home must be absolute" ;;
esac
[[ -d "${idea_platform_home}" ]] || fail "IDEA platform home is unavailable"
[[ -f "${idea_platform_home}/build.txt" ]] || fail "IDEA platform build identity is absent"
[[ -d "${idea_platform_home}/plugins/Kotlin" ]] || fail "IDEA Kotlin plugin is absent"
[[ -d "${java_home}" ]] || fail "Java home is unavailable"
[[ -x "${java_home}/bin/java" ]] || fail "Java executable is unavailable"
[[ -f "${java_home}/release" ]] || fail "Java release identity is absent"
idea_platform_home="$(CDPATH='' cd -- "${idea_platform_home}" && pwd -P)"
java_home="$(CDPATH='' cd -- "${java_home}" && pwd -P)"
version="${release#v}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-published-sidecar.XXXXXX")"
cleanup() {
  rm -rf -- "${temporary_root}"
}
trap cleanup EXIT
install_root="${temporary_root}/install"
bin_root="${temporary_root}/bin"
home="${temporary_root}/home"
runtime_directory="${temporary_root}/runtime"
runtime_store="${temporary_root}/store"
xdg_config_home="${home}/.config"
xdg_data_home="${home}/.local/share"
idea_home="${home}/idea-home"
mkdir -p \
  "${xdg_config_home}" \
  "${xdg_data_home}" \
  "${runtime_directory}" \
  "${idea_home}/Resources" \
  "${idea_home}/plugins" \
  "${idea_home}/jbr/Contents"
ln -s "${idea_platform_home}/build.txt" "${idea_home}/Resources/build.txt"
ln -s "${idea_platform_home}/plugins/Kotlin" "${idea_home}/plugins/Kotlin"
ln -s "${java_home}" "${idea_home}/jbr/Contents/Home"

HOME="${home}" XDG_CONFIG_HOME="${xdg_config_home}" XDG_DATA_HOME="${xdg_data_home}" \
KAST_INSTALL_IDEA_HOME="${idea_home}" \
KAST_INSTALL_ROOT="${install_root}" KAST_BIN_DIR="${bin_root}" \
KAST_RUNTIME_DIRECTORY="${runtime_directory}" KAST_RUNTIME_STORE="${runtime_store}" \
  bash "${repository_root}/install.sh" install \
    --version "${version}" --repository "${repository}"

kast="${bin_root}/kast"
control_root="${install_root}/versions/${version}"
manifest="${control_root}/share/kast/semantic-runtime.json"
sidecar="${control_root}/share/kast/runtime/kast-semantic-runtime-${version}-macos-aarch64.zip"
[[ -x "${kast}" ]] || fail "public installer did not create the command"
[[ -f "${manifest}" ]] || fail "installed control has no sidecar manifest"
[[ -f "${sidecar}" ]] || fail "public installer did not retain the private sidecar"
if find "${control_root}" \( -name 'idea-home' -o -name 'product-info.json' \
  -o -name 'kast-ide-plugin*' \) -print -quit | grep -q .; then
  fail "published install contains an IDEA home or public plugin"
fi
if grep -Eq '(^|/)idea-home/|product-info\.json|kast-ide-plugin' \
  < <(unzip -Z1 "${sidecar}"); then
  fail "published sidecar contains an IDEA home or public plugin"
fi
grep -Eq '^private-plugins/kast-indexer/lib/.+' < <(unzip -Z1 "${sidecar}") ||
  fail "published sidecar has no private Kast extension"
[[ "$(HOME="${home}" XDG_CONFIG_HOME="${xdg_config_home}" \
  XDG_DATA_HOME="${xdg_data_home}" "${kast}" --version)" == \
  "kast ${version} (IntelliJ sidecar)" ]] ||
  fail "installed version identity is not the sidecar product"

schema="$(HOME="${home}" XDG_CONFIG_HOME="${xdg_config_home}" \
  XDG_DATA_HOME="${xdg_data_home}" "${kast}" --schema)"
python3 - "${schema}" "${control_root}/share/kast/operation-registry.json" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(sys.argv[1])
assert document["operationRegistry"] == json.loads(Path(sys.argv[2]).read_text()), document
assert document["cliProjection"]["localCommands"] == [
    "product inspect",
    "broker serve",
], document
PY

fixture="${temporary_root}/workspace"
mkdir -p "${fixture}"
git init -q "${fixture}"
printf 'rootProject.name = "published-sidecar"\n' >"${fixture}/settings.gradle.kts"
inspection="$(cd "${fixture}" && HOME="${home}" XDG_CONFIG_HOME="${xdg_config_home}" \
  XDG_DATA_HOME="${xdg_data_home}" KAST_RUNTIME_DIRECTORY="${runtime_directory}" \
  KAST_RUNTIME_STORE="${runtime_store}" "${kast}" product inspect)"
python3 - "${inspection}" <<'PY'
import json
import sys

document = json.loads(sys.argv[1])
assert document["status"] == "complete", document
assert document["control"]["execution"] == "isolated-intellij-sidecar", document
assert document["workspace"]["cache"]["type"] == "absent", document
PY

before="$(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)"
(cd "${fixture}" && HOME="${home}" XDG_CONFIG_HOME="${xdg_config_home}" \
  XDG_DATA_HOME="${xdg_data_home}" KAST_RUNTIME_DIRECTORY="${runtime_directory}" \
  KAST_RUNTIME_STORE="${runtime_store}" "${kast}" status) >/dev/null
after="$(pgrep -f 'io\.github\.amichne\.kast\.indexer\.KastIndexerMainKt' || true)"
[[ "${before}" == "${after}" ]] || fail "passive status started a sidecar"
[[ ! -e "${home}/Library/Application Support/JetBrains" ]] ||
  fail "published product wrote a JetBrains plugin path"

echo "published-sidecar-delivery: ok ${release}"
