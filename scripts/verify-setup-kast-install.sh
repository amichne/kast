#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/verify-setup-kast-install.sh [options]

Verify a setup-kast installation in a CI runner, Devin snapshot, or local smoke.

Options:
  --install-dir <path>              Installed current runtime directory. Defaults to $KAST_INSTALL_ROOT/current.
  --workspace-root <path>           Workspace root to start. Defaults to a temporary Kotlin workspace.
  --source-root <path>              Kotlin source root for kast developer runtime up. Defaults with temporary workspace.
  --module-name <name>              Module name for kast developer runtime up. Defaults to setup-kast-verify.
  --workspace-id <id>               KAST_WORKSPACE_ID for daemon state. Defaults to setup-kast-verify.
  --wait-timeout-ms <millis>        Startup wait timeout. Defaults to 120000.
  --gradle-root <path>              Run repo-level Gradle warm checks from this root.
  --allow-missing-gradle-cache      Do not require GRADLE_RO_DEP_CACHE/modules-2.
  --skip-daemon                     Verify install and cache only; do not start the headless backend.
  --help                           Show this help.
USAGE
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

need_dir() {
  local path="$1"
  local description="$2"
  [[ -d "$path" ]] || die "${description} not found: ${path}"
}

need_file() {
  local path="$1"
  local description="$2"
  [[ -f "$path" ]] || die "${description} not found: ${path}"
}

absolute_path() {
  local path="$1"
  local dir
  local base
  dir="$(dirname -- "$path")"
  base="$(basename -- "$path")"
  (cd -- "$dir" && printf '%s/%s\n' "$(pwd -P)" "$base")
}

assert_tree_not_writable() {
  local root="$1"
  if find "$root" \( -perm -u+w -o -perm -g+w -o -perm -o+w \) -print -quit | grep -q .; then
    find "$root" \( -perm -u+w -o -perm -g+w -o -perm -o+w \) -print >&2
    die "read-only tree has writable entries: ${root}"
  fi
}

install_dir="${KAST_INSTALL_ROOT:+${KAST_INSTALL_ROOT}/current}"
workspace_root=""
source_root=""
module_name="setup-kast-verify"
workspace_id="${KAST_WORKSPACE_ID:-setup-kast-verify}"
wait_timeout_ms="${KAST_VERIFY_WAIT_TIMEOUT_MS:-120000}"
gradle_root=""
require_gradle_cache=true
start_daemon=true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir)
      [[ $# -ge 2 ]] || die "Missing value for --install-dir"
      install_dir="$2"; shift 2 ;;
    --install-dir=*)
      install_dir="${1#--install-dir=}"; shift ;;
    --workspace-root)
      [[ $# -ge 2 ]] || die "Missing value for --workspace-root"
      workspace_root="$2"; shift 2 ;;
    --workspace-root=*)
      workspace_root="${1#--workspace-root=}"; shift ;;
    --source-root)
      [[ $# -ge 2 ]] || die "Missing value for --source-root"
      source_root="$2"; shift 2 ;;
    --source-root=*)
      source_root="${1#--source-root=}"; shift ;;
    --module-name)
      [[ $# -ge 2 ]] || die "Missing value for --module-name"
      module_name="$2"; shift 2 ;;
    --module-name=*)
      module_name="${1#--module-name=}"; shift ;;
    --workspace-id)
      [[ $# -ge 2 ]] || die "Missing value for --workspace-id"
      workspace_id="$2"; shift 2 ;;
    --workspace-id=*)
      workspace_id="${1#--workspace-id=}"; shift ;;
    --wait-timeout-ms)
      [[ $# -ge 2 ]] || die "Missing value for --wait-timeout-ms"
      wait_timeout_ms="$2"; shift 2 ;;
    --wait-timeout-ms=*)
      wait_timeout_ms="${1#--wait-timeout-ms=}"; shift ;;
    --gradle-root)
      [[ $# -ge 2 ]] || die "Missing value for --gradle-root"
      gradle_root="$2"; shift 2 ;;
    --gradle-root=*)
      gradle_root="${1#--gradle-root=}"; shift ;;
    --allow-missing-gradle-cache)
      require_gradle_cache=false; shift ;;
    --skip-daemon)
      start_daemon=false; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$install_dir" ]] || die "KAST_INSTALL_ROOT is unset; pass --install-dir when verifying a non-action install"
[[ -n "$workspace_id" ]] || die "--workspace-id must not be empty"
[[ "$wait_timeout_ms" =~ ^[0-9]+$ ]] || die "--wait-timeout-ms must be an integer"

kast_bin="$(command -v kast || true)"
[[ -n "$kast_bin" ]] || die "kast is not on PATH"
need_dir "$install_dir" "Kast install directory"
need_file "${install_dir}/bin/kast" "installed kast binary"
need_file "${install_dir}/kast-runtime-manifest.json" "runtime manifest"
install_root="$(cd -- "$(dirname -- "$install_dir")" && pwd)"
need_file "${install_root}/install.json" "install manifest"
expected_kast_bin="$(absolute_path "${install_dir}/bin/kast")"
actual_kast_bin="$(absolute_path "$kast_bin")"
[[ "$actual_kast_bin" == "$expected_kast_bin" ]] \
  || die "kast on PATH does not match install-dir: expected ${expected_kast_bin}, got ${actual_kast_bin}"

"$kast_bin" --version
"$kast_bin" ready

if [[ "$require_gradle_cache" == "true" ]]; then
  [[ -n "${GRADLE_RO_DEP_CACHE:-}" ]] || die "GRADLE_RO_DEP_CACHE is unset"
  need_dir "${GRADLE_RO_DEP_CACHE}/modules-2" "Gradle read-only dependency cache"
  assert_tree_not_writable "$GRADLE_RO_DEP_CACHE"
else
  if [[ -n "${GRADLE_RO_DEP_CACHE:-}" && -d "${GRADLE_RO_DEP_CACHE}/modules-2" ]]; then
    assert_tree_not_writable "$GRADLE_RO_DEP_CACHE"
  fi
fi

if [[ "$require_gradle_cache" == "true" || -n "${GRADLE_RO_DEP_CACHE:-}" ]]; then
  [[ -n "${GRADLE_USER_HOME:-}" ]] || die "GRADLE_USER_HOME is unset"
fi
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
  need_dir "$GRADLE_USER_HOME" "Gradle writable user home"
  [[ -w "$GRADLE_USER_HOME" ]] || die "Gradle user home is not writable: ${GRADLE_USER_HOME}"
fi
[[ -n "${KAST_CACHE_HOME:-}" ]] || die "KAST_CACHE_HOME is unset"
need_dir "$KAST_CACHE_HOME" "Kast cache home"

run_gradle_warm_command() {
  local -a gradle_command=("$gradle_root/gradlew" "$@")
  if [[ -x "${gradle_root}/scripts/ci-gradle-retry.sh" ]]; then
    "${gradle_root}/scripts/ci-gradle-retry.sh" "${gradle_command[@]}"
  else
    "${gradle_command[@]}"
  fi
}

if [[ -n "$gradle_root" ]]; then
  need_dir "$gradle_root" "Gradle warm root"
  gradle_root="$(cd -- "$gradle_root" && pwd -P)"
  [[ -x "${gradle_root}/gradlew" ]] || die "Gradle wrapper is not executable: ${gradle_root}/gradlew"
  (
    cd -- "$gradle_root"
    run_gradle_warm_command --version --no-daemon
    run_gradle_warm_command dependencies --no-daemon
    run_gradle_warm_command buildEnvironment --no-daemon
  )
fi

scratch_dir=""
cleanup() {
  local status="$?"
  if [[ "$status" -ne 0 && -n "$scratch_dir" ]]; then
    printf 'setup-kast verifier scratch: %s\n' "$scratch_dir" >&2
  fi
  if [[ -n "$scratch_dir" ]]; then
    rm -rf "$scratch_dir"
  fi
}
trap cleanup EXIT

if [[ "$start_daemon" == "true" ]]; then
  if [[ -z "$workspace_root" ]]; then
    scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-verify-setup.XXXXXX")"
    workspace_root="${scratch_dir}/workspace"
    source_root="${workspace_root}/src/main/kotlin"
    mkdir -p "$source_root"
    printf '%s\n' "package smoke" "class Smoke" > "${source_root}/Smoke.kt"
  fi
  need_dir "$workspace_root" "Kast smoke workspace"
  if [[ -n "$source_root" ]]; then
    need_dir "$source_root" "Kast smoke source root"
  fi

  up_args=(
    developer
    runtime
    up
    --backend=headless
    "--workspace-root=${workspace_root}"
    "--module-name=${module_name}"
    --accept-indexing=true
    "--wait-timeout-ms=${wait_timeout_ms}"
  )
  if [[ -n "$source_root" ]]; then
    up_args+=("--source-roots=${source_root}")
  fi

  KAST_WORKSPACE_ID="$workspace_id" "$kast_bin" "${up_args[@]}"
  KAST_WORKSPACE_ID="$workspace_id" "$kast_bin" developer runtime status \
    --backend=headless \
    "--workspace-root=${workspace_root}" \
    --no-auto-start=true
  KAST_WORKSPACE_ID="$workspace_id" "$kast_bin" developer runtime capabilities \
    --backend=headless \
    "--workspace-root=${workspace_root}" \
    --accept-indexing=true \
    --no-auto-start=true
  KAST_WORKSPACE_ID="$workspace_id" "$kast_bin" developer runtime stop \
    --backend=headless \
    "--workspace-root=${workspace_root}" || true

  need_dir "${KAST_CACHE_HOME}/workspaces/${workspace_id}" "Kast workspace cache directory"
fi

if find "$install_root" \( -name '*.sock' -o -name 'daemon.json' \) -print -quit | grep -q .; then
  find "$install_root" \( -name '*.sock' -o -name 'daemon.json' \) -print >&2
  die "Kast install directory contains daemon state"
fi

printf '%s\n' "setup-kast install verification passed"
