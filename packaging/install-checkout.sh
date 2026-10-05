#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
IFS=$'\n\t'

fail() { printf 'kast-install: %s\n' "$*" >&2; exit 1; }
installer="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd -P)/install.sh"
mode=${1:-}
case "$mode" in persistent) shift ;; session) fail 'session installations are retired; use persistent to replace the sole user installation' ;; *) fail 'usage: packaging/install-checkout.sh persistent --idea-home <path> [--force] [--register-codex-mcp|--skip-codex-mcp]' ;; esac
[[ -n "${HOME:-}" ]] || fail 'HOME is required'
[[ -z "${KAST_INSTALL_ROOT+x}" || "$KAST_INSTALL_ROOT" == "$HOME/.local/share/kast" ]] || fail 'KAST_INSTALL_ROOT must bind the sole per-user installation'
[[ -z "${KAST_BIN_DIR+x}" || "$KAST_BIN_DIR" == "$HOME/.local/bin" ]] || fail 'KAST_BIN_DIR must bind the sole per-user binary directory'
export KAST_INSTALL_ROOT="$HOME/.local/share/kast" KAST_BIN_DIR="$HOME/.local/bin"
checkout=$(pwd -P)
[[ -x "$checkout/gradlew" && -f "$checkout/packaging/install-local.sh" && -f "$checkout/build.gradle.kts" ]] ||
  fail 'run packaging/install-checkout.sh from the root of a Kast checkout'
[[ -z ${KAST_SESSION_ROOT:-} ]] ||
  fail 'run persistent installation from a shell without an active Kast session'

# Admit development options before invoking Gradle or creating state.
options=()
component=pair
idea_home="${KAST_INSTALL_IDEA_HOME:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --control-only|--host-only)
      [[ "$component" == pair ]] || fail "select one component installation"
      if [[ "$1" == --control-only ]]; then component=control; else component=host; fi
      options+=("$1"); shift; continue ;;
    --force) options+=(--force); shift; continue ;;
    --register-codex-mcp|--skip-codex-mcp)
      options+=("$1"); shift; continue ;;
    --idea-home) idea_home="${2:-}" ;;
    *) fail "unsupported checkout installation option: $1" ;;
  esac
  [[ $# -ge 2 && -n $2 ]] || fail "$1 requires a value"
  options+=("$1" "$2")
  shift 2
done

case "$idea_home" in *.app) idea_home="$idea_home/Contents" ;; esac
[[ -n "$idea_home" && -f "$idea_home/Resources/product-info.json" ]] || fail 'an admitted IDEA home is required'
idea_home="$(CDPATH='' cd -- "$idea_home" && pwd -P)"
java_home="$idea_home/jbr/Contents/Home"
[[ -x "$java_home/bin/java" && -f "$java_home/release" ]] ||
  fail 'selected IDEA has no bundled JBR; repair the IDEA installation before building Kast'
java_feature="$(/usr/bin/awk '
  /^JAVA_VERSION=/ {
    declarations++
    if ($0 ~ /^JAVA_VERSION="[0-9]+[^"\r]*"\r?$/) {
      feature = $0
      sub(/^JAVA_VERSION="/, "", feature)
      sub(/[^0-9].*$/, "", feature)
    }
  }
  END { if (declarations == 1 && feature != "") print feature }
' "$java_home/release")"
[[ "$java_feature" =~ ^[0-9]+$ ]] && (( java_feature >= 25 )) ||
  fail 'selected IDEA bundled JBR requires Java 25 or newer; select a supported IDEA installation'
# Scope the runtime choice to this installer and its children, before Gradle starts.
export JAVA_HOME="$java_home" JAVA="$java_home/bin/java" KAST_INSTALL_JAVA_HOME="$java_home"
idea_build=$(python3 - "$idea_home/Resources/product-info.json" <<'PYTHON'
import json, re, sys
value = json.load(open(sys.argv[1])).get("buildNumber")
if not isinstance(value, str) or re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", value) is None:
    raise SystemExit("kast-install: invalid IDEA build identity")
print(value)
PYTHON
)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/kast-checkout.XXXXXX")
cleanup() {
  local status=$?
  rm -rf -- "$scratch"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# A new numeric build identity preserves the release installer's immutable
# version contract, including builds of uncommitted working-tree changes.
version="0.$(date -u +%Y%m%d).$(date -u +%H%M%S | sed 's/^0*//;s/^$/0/')"
base_url="https://github.com/amichne/kast/releases/download"
printf 'kast-install: building checkout %s (%s)\n' "$checkout" "$version" >&2
build_options=("-PhostedIdeaHome=$idea_home")
build_tasks=()
case "$component" in
  control) build_options+=("-PcontrolVersion=$version"); build_tasks+=(assembleKastControlDist) ;;
  host) build_options+=("-PhostedPluginVersion=$version"); build_tasks+=(generateHostReleaseRecord) ;;
  pair) build_options+=("-PcontrolVersion=$version" "-PhostedPluginVersion=$version"); build_tasks+=(assembleKastControlDist generateHostReleaseRecord) ;;
esac
"$checkout/gradlew" --console=plain "${build_options[@]}" "${build_tasks[@]}" >&2
if [[ "$component" != host ]]; then
  name="kast-control-v$version-macos-aarch64.tar.gz"
  cp "$checkout/build/distributions/$name" "$scratch/$name"
  (cd "$scratch" && shasum -a 256 "$name" > "$name.sha256")
fi
if [[ "$component" != control ]]; then
  plugin_name="kast-ide-hosted-v$version-idea-${idea_build%%.*}.zip"
  cp "$checkout/runtime/hosted/build/distributions/$plugin_name" "$scratch/$plugin_name"
  cp "$checkout/packaging/host-installation.py" "$scratch/host-installation.py"
  for name in "$plugin_name" host-installation.py; do
    (cd "$scratch" && shasum -a 256 "$name" > "$name.sha256")
  done
  record_name="kast-host-release-v$version.json"
  cp "$checkout/build/generated/host-release/$record_name" "$scratch/$record_name"
  cp "$checkout/build/generated/host-release/$record_name.sha256" "$scratch/$record_name.sha256"
fi

host_version=""
[[ "$component" == control ]] || host_version="$version"
KAST_INSTALL_PROFILE="$mode" KAST_VERSION="$version" KAST_HOST_VERSION="$host_version" KAST_RELEASE_BASE_URL="$base_url" \
KAST_INSTALL_ASSETS_DIRECTORY="$scratch" \
  bash "$installer" ${options[@]+"${options[@]}"} >&2
