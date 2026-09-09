#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
IFS=$'\n\t'

fail() { printf 'kast-install: %s\n' "$*" >&2; exit 1; }
quote() { printf "'"; printf '%s' "$1" | sed "s/'/'\"'\"'/g"; printf "'"; }
installer=$1
shift
mode=${1:-}
case "$mode" in session|persistent) shift ;; *) fail '--local requires session or persistent' ;; esac
checkout=$(pwd -P)
[[ -x "$checkout/gradlew" && -f "$checkout/packaging/install-local.sh" && -f "$checkout/build.gradle.kts" ]] ||
  fail 'run --local from the root of a Kast checkout'
[[ -z ${KAST_SESSION_ROOT:-} || $mode != persistent ]] ||
  fail 'run persistent installation from a shell without an active Kast session'

# Admit options before invoking Gradle or creating any installation state.
options=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --idea-home) ;;
    --install-root|--bin-dir|--runtime-store|--runtime-directory|--cache-root|--app-server-tools)
      [[ $mode == persistent ]] || fail "$1 is only available for persistent checkout installs" ;;
    *) fail "unsupported checkout installation option: $1" ;;
  esac
  [[ $# -ge 2 && -n $2 ]] || fail "$1 requires a value"
  options+=("$1" "$2")
  shift 2
done

scratch=$(mktemp -d "${TMPDIR:-/tmp}/kast-checkout.XXXXXX")
session_root=""
cleanup() {
  local status=$?
  rm -rf -- "$scratch"
  if [[ $status -ne 0 && -n $session_root ]]; then
    printf 'kast-install: failed session installation retained at %s\n' "$session_root" >&2
  fi
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
KAST_RUNTIME_BASE_URL="$base_url/v$version" "$checkout/gradlew" --console=plain \
  "-Pversion=$version" assembleKastControlDist assembleKastSemanticRuntimeDist >&2
for name in "kast-control-v$version-macos-aarch64.tar.gz" "kast-semantic-runtime-$version-macos-aarch64.zip"; do
  cp "$checkout/build/distributions/$name" "$scratch/$name"
  (cd "$scratch" && shasum -a 256 "$name" > "$name.sha256")
done

if [[ $mode == session ]]; then
  session_root=$(mktemp -d "${TMPDIR:-/tmp}/kast-session.XXXXXX")
  # Ignore inherited persistent settings; the launcher captures its own config.
  export KAST_INSTALL_ROOT="$session_root/install" KAST_BIN_DIR="$session_root/bin"
  unset KAST_RUNTIME_STORE KAST_RUNTIME_DIRECTORY KAST_CACHE_ROOT
  export KAST_ENABLE_LAUNCHD=0 KAST_ENABLE_APP_SERVER=0
  export XDG_CONFIG_HOME="$session_root/config"
else
  options+=(--enable-launchd 1 --enable-app-server 1)
fi

# Bash 3.2 needs the guarded expansion for an empty array under nounset.
if [[ $mode == persistent ]]; then
  options+=(--refresh-app-server)
fi
bash "$installer" --version "$version" --release-base-url "$base_url" \
  --assets-directory "$scratch" ${options[@]+"${options[@]}"} >&2

if [[ $mode == session ]]; then
  physical_release=$(CDPATH='' cd -- "$KAST_INSTALL_ROOT/current" && pwd -P)
  case "$physical_release" in "$KAST_INSTALL_ROOT/versions/"*) ;; *) echo 'kast-install: session release ownership rejected' >&2; exit 1 ;; esac
  export KAST_RUNTIME_STORE="$physical_release/runtime-payloads" KAST_RUNTIME_DIRECTORY="$physical_release/state/run"
  export KAST_CACHE_ROOT="$physical_release/state/cache"
  activation="$session_root/activate.sh"
  {
    printf '# Source in Bash or Zsh. Session files remain available until explicitly removed.\n'
    for key in KAST_INSTALL_ROOT KAST_BIN_DIR KAST_RUNTIME_STORE KAST_RUNTIME_DIRECTORY KAST_CACHE_ROOT KAST_ENABLE_LAUNCHD KAST_ENABLE_APP_SERVER; do
      printf 'export %s=%s\n' "$key" "$(quote "${!key}")"
    done
    printf 'export KAST_SESSION_ROOT=%s\n' "$(quote "$session_root")"
    printf 'case ":${PATH:-}:" in *":$KAST_BIN_DIR:"*) ;; *) export PATH="$KAST_BIN_DIR${PATH:+:$PATH}" ;; esac\n'
  } > "$activation"
  printf 'kast-install: source %s to activate; persistent services are disabled\n' "$activation" >&2
  printf '%s\n' "$activation"
fi
