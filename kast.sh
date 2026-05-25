#!/usr/bin/env bash
# kast.sh - repo-local Kast build tooling
#
# Subcommands:
#   build    Build portable distribution artifacts  ->  dist/
#   install  Retired; use Homebrew or scripts/install-ubuntu-debian.sh
#
# Explicit subcommand:
#   ./kast.sh build [plugin] [backend] [--all]
#   ./kast.sh install  # prints the supported install paths
set -euo pipefail

# ---------------------------------------------------------------------------
# Script location -- graceful when curl-piped (BASH_SOURCE[0] may be /dev/stdin)
# ---------------------------------------------------------------------------

_SCRIPT_SRC="${BASH_SOURCE[0]:-}"
if [[ -n "$_SCRIPT_SRC" && -f "$_SCRIPT_SRC" ]]; then
  SCRIPT_DIR="$(cd -- "$(dirname -- "$_SCRIPT_SRC")" >/dev/null 2>&1 && pwd)"
else
  SCRIPT_DIR=""
fi

REPO_ROOT="$SCRIPT_DIR"
GRADLEW="${REPO_ROOT}/gradlew"
DIST_ROOT="${REPO_ROOT}/dist"

# Build paths are only meaningful when SCRIPT_DIR is set.
PLUGIN_DIST_DIR="${REPO_ROOT}/backend-intellij/build/distributions"
BACKEND_PORTABLE_DIST_DIR="${REPO_ROOT}/backend-standalone/build/portable-dist/backend-standalone"
BACKEND_PORTABLE_ZIP_DIR="${REPO_ROOT}/backend-standalone/build/distributions"

tmp_dir=""
cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
}

trap cleanup EXIT

# ===========================================================================
# Logging and UI utilities
# ===========================================================================

supports_color() {
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then return 0; fi
  if [[ -n "${NO_COLOR:-}" ]]; then return 1; fi
  if [[ ! -t 2 ]]; then return 1; fi
  [[ "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"; shift
  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
    return
  fi
  printf '%s' "$*"
}

log_line() { printf '%s %s\n' "$1" "$2" >&2; }
log()         { log_line "$(colorize '2' '|')" "$*"; }
log_section() { printf '\n%s\n' "$(colorize '1;36' "$*")" >&2; }
log_step()    { log_line "$(colorize '1;34' '>')" "$*"; }
log_success() { log_line "$(colorize '1;32' 'v')" "$*"; }
log_note()    { log_line "$(colorize '33' '*')" "$*"; }

die() {
  log_line "$(colorize '1;31' 'x')" "$*"
  exit 1
}

can_prompt() { [[ -r /dev/tty && -w /dev/tty ]] && { : </dev/tty >/dev/tty; } 2>/dev/null; }

# ===========================================================================
# Shared utilities
# ===========================================================================

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
}

# ===========================================================================
# cmd_build -- local dev build / packaging
# ===========================================================================

_BUILD_ALL_TARGETS=(plugin backend)
_build_selected_targets=()

_build_verify_prerequisites() {
  [[ -n "$REPO_ROOT" && -d "$REPO_ROOT" ]] || die "Could not determine the repo root (run kast.sh from the repo)"
  [[ -x "$GRADLEW" ]] || die "Missing executable gradlew at ${GRADLEW}"
}

_build_ensure_healthy_daemon() {
  if ! "$GRADLEW" --status >/dev/null 2>&1; then
    log_step "Stopping stale Gradle daemons"
    "$GRADLEW" --stop >/dev/null 2>&1 || true
  fi
}

_build_select_targets_fzf() {
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && _build_selected_targets+=("$line")
  done < <(
    printf '%s\n' "${_BUILD_ALL_TARGETS[@]}" | fzf \
      --multi \
      --prompt="Select build targets: " \
      --header="<tab> toggle  <ctrl-a> select all  <enter> confirm" \
      --bind="ctrl-a:select-all" \
      --height="~50%" \
      --layout=reverse \
      --border=rounded
  )
  [[ "${#_build_selected_targets[@]}" -gt 0 ]] || die "No targets selected"
}

_build_select_targets_interactive() {
  if command -v fzf >/dev/null 2>&1 && can_prompt; then
    _build_select_targets_fzf
  else
    log_note "fzf not found or no TTY -- building all targets"
    _build_selected_targets=("${_BUILD_ALL_TARGETS[@]}")
  fi
}

_GRADLE_EXTRA_ARGS=()

_build_run_gradle_tasks() {
  ( cd "$REPO_ROOT"; "$GRADLEW" "${_GRADLE_EXTRA_ARGS[@]}" "$@" )
}

_build_run_gradle_tasks_with_retry() {
  if _build_run_gradle_tasks "$@"; then return 0; fi
  log_note "Gradle build failed; stopping daemon and retrying"
  "$GRADLEW" --stop >/dev/null 2>&1 || true
  _build_run_gradle_tasks "$@" --offline
}

_build_resolve_plugin_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PLUGIN_DIST_DIR}"/*.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a plugin zip under ${PLUGIN_DIST_DIR}"
  printf '%s\n' "$newest"
}

_build_resolve_backend_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${BACKEND_PORTABLE_ZIP_DIR}"/backend-standalone-*-portable.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a backend portable zip under ${BACKEND_PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

_build_plugin() {
  log_section "Building target: plugin"
  _build_run_gradle_tasks_with_retry buildIntellijPlugin

  local source_zip; source_zip="$(_build_resolve_plugin_zip)"
  local dist_zip="${DIST_ROOT}/plugin.zip"
  log_step "Publishing plugin zip into ${dist_zip}"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"
}

_build_backend() {
  log_section "Building target: backend"
  rm -rf "${REPO_ROOT}/backend-standalone/build/portable-dist" "${REPO_ROOT}/backend-standalone/build/distributions"
  _build_run_gradle_tasks_with_retry stageBackendDist buildBackendPortableZip

  log_step "Verifying staged backend tree in ${BACKEND_PORTABLE_DIST_DIR}"
  [[ -x "${BACKEND_PORTABLE_DIST_DIR}/kast-standalone" ]]             || die "Missing staged backend-standalone launcher"
  [[ -d "${BACKEND_PORTABLE_DIST_DIR}/runtime-libs" ]]                || die "Missing staged runtime-libs directory"
  [[ -f "${BACKEND_PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]]  || die "Missing staged runtime classpath file"
  local jars=()
  shopt -s nullglob
  jars=("${BACKEND_PORTABLE_DIST_DIR}"/libs/backend-standalone-*-all.jar)
  shopt -u nullglob
  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${BACKEND_PORTABLE_DIST_DIR}/libs"

  local dist_dir="${DIST_ROOT}/backend"
  local dist_zip="${DIST_ROOT}/backend.zip"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing backend tree into ${dist_dir}"
  mkdir -p "$DIST_ROOT"
  cp -R "$BACKEND_PORTABLE_DIST_DIR" "${tmp_dir}/backend"
  rm -rf "$dist_dir"
  mv "${tmp_dir}/backend" "$dist_dir"
  log_success "Published ${dist_dir}"

  local source_zip; source_zip="$(_build_resolve_backend_zip)"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"

  rm -rf "$tmp_dir"; tmp_dir=""
}

_build_openapi() {
  log_section "Generating OpenAPI specification"
  _build_run_gradle_tasks_with_retry stageOpenApiSpec
  local dist_spec="${DIST_ROOT}/openapi.yaml"
  [[ -f "$dist_spec" ]] || die "Missing generated OpenAPI spec at ${dist_spec}"
  log_success "openapi -> ${dist_spec}"
}

_build_clean_stale_outputs() {
  local backend_dir="${DIST_ROOT}/backend"
  if [[ -d "$backend_dir" && (! -f "${backend_dir}/kast-standalone" || ! -d "${backend_dir}/runtime-libs") ]]; then
    log_step "Removing incomplete ${backend_dir} from a previous run"
    rm -rf "$backend_dir"
  fi

  shopt -s nullglob
  local stale
  for stale in "${TMPDIR:-/tmp}"/kast-build.??????; do
    [[ -d "$stale" ]] && rm -rf "$stale"
  done
  shopt -u nullglob
}

cmd_build() {
  _build_selected_targets=()
  _GRADLE_EXTRA_ARGS=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      plugin|backend)
        _build_selected_targets+=("$1"); shift ;;
      --all)
        _build_selected_targets=("${_BUILD_ALL_TARGETS[@]}"); shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh build [target...] [options]

Builds selected Kast components and publishes artifacts to dist/.

Targets (positional, repeatable):
  plugin       IDEA plugin zip           -> dist/plugin.zip
  backend      Standalone server         -> dist/backend/  dist/backend.zip

Options:
  --all            Build all targets.
  --help, -h       Show this help.

When no targets are supplied and a TTY is available, fzf is used for
interactive multi-selection. Falls back to building all targets when
fzf is not installed.
USAGE
        return 0
        ;;
      *)
        die "Unknown argument: $1" ;;
    esac
  done

  _build_verify_prerequisites
  _build_clean_stale_outputs

  if [[ "${#_build_selected_targets[@]}" -eq 0 ]]; then
    _build_select_targets_interactive
  fi

  log_section "Kast local build"
  _build_ensure_healthy_daemon

  for target in "${_build_selected_targets[@]}"; do
    case "$target" in
      plugin)  _build_plugin ;;
      backend) _build_backend ;;
    esac
  done

  _build_openapi

  log_section "Build complete"
  for target in "${_build_selected_targets[@]}"; do
    case "$target" in
      plugin)  log_success "plugin  ->  ${DIST_ROOT}/plugin.zip" ;;
      backend) log_success "backend ->  ${DIST_ROOT}/backend/  ${DIST_ROOT}/backend.zip" ;;
    esac
  done
}

# ===========================================================================
# Top-level dispatch
# ===========================================================================

usage_main() {
  cat >&2 << 'USAGE'
Usage: ./kast.sh <subcommand> [options]

Subcommands:
  build    Build portable distribution artifacts  ->  dist/
  install  Show the supported install paths

Run ./kast.sh <subcommand> --help for subcommand-specific options.

Recommended local CLI install:
  brew tap amichne/kast
  brew install kast

Ubuntu/Debian install:
  ./scripts/install-ubuntu-debian.sh install
USAGE
}

cmd_install_retired() {
  cat >&2 <<'USAGE'
The kast.sh shell installer is retired.

Supported install paths:
  brew tap amichne/kast && brew install kast
  ./scripts/install-ubuntu-debian.sh install
USAGE
  exit 2
}

main() {
  local cmd="${1:-}"

  if [[ -z "$SCRIPT_DIR" ]] && [[ -z "$cmd" || "$cmd" == --* ]]; then
    cmd_install_retired
  fi

  case "$cmd" in
    build)          shift; cmd_build "$@" ;;
    install)        shift; cmd_install_retired "$@" ;;
    --help|-h|help) usage_main ;;
    "")             usage_main; exit 1 ;;
    *)              die "Unknown subcommand: ${cmd}. Run ./kast.sh --help for usage." ;;
  esac
}

main "$@"
