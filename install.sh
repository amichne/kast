#!/usr/bin/env bash
set -Eeuo pipefail

DEFAULT_TAP="amichne/kast"

usage() {
  cat >&2 <<'USAGE'
Usage: ./install.sh [install|update|verify] [options]

macOS-only Kast developer-machine installer.

Commands:
  install   Tap Homebrew, install kast, and install the IDEA plugin.
  update    Refresh Homebrew metadata, update kast, and refresh the IDEA plugin.
  verify    Check the Homebrew formula and repository readiness.

Options:
  --tap <owner/repo>       Homebrew tap name. Defaults to amichne/kast.
  --tap-url <git-url>      Optional Git URL for custom-host taps.
  --workspace-root <path>  Repository to verify and show in guidance. Defaults to the current directory.
  -h, --help               Show this help.
USAGE
}

die() {
  log_line "$(colorize '1;31' 'x')" "$*"
  exit 1
}

supports_color() {
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then return 0; fi
  if [[ -n "${NO_COLOR:-}" ]]; then return 1; fi
  if [[ ! -t 2 ]]; then return 1; fi
  [[ "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"
  shift
  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
    return
  fi
  printf '%s' "$*"
}

log_line() {
  printf '%s %s\n' "$1" "$2" >&2
}

log_section() {
  printf '\n%s\n' "$(colorize '1;36' "$*")" >&2
}

log_step() {
  log_line "$(colorize '1;34' '>')" "$*"
}

log_success() {
  log_line "$(colorize '1;32' 'v')" "$*"
}

log_note() {
  log_line "$(colorize '33' '*')" "$*"
}

run() {
  log_step "$*"
  "$@" || die "Command failed: $*"
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "Missing required command: ${command_name}"
}

host_uname() {
  if [[ -n "${KAST_INSTALL_TEST_UNAME:-}" ]]; then
    printf '%s\n' "$KAST_INSTALL_TEST_UNAME"
    return
  fi
  uname -s
}

require_macos() {
  local host
  host="$(host_uname)"
  [[ "$host" == "Darwin" ]] || die "install.sh only supports macOS; found ${host}"
}

resolve_existing_dir() {
  local path="$1"
  [[ -d "$path" ]] || die "Workspace root does not exist: ${path}"
  (cd -- "$path" && pwd -P)
}

validate_tap() {
  local tap="$1"
  [[ -n "$tap" ]] || die "Invalid tap: ${tap}"
  [[ "$tap" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || die "Invalid tap: ${tap}"
}

validate_tap_url() {
  local tap_url="$1"
  [[ -n "$tap_url" ]] || die "Invalid tap URL: ${tap_url}"
  case "$tap_url" in
    https://*|http://*|ssh://*|git@*:*.git|git@*:*)
      ;;
    *)
      die "Invalid tap URL: ${tap_url}"
      ;;
  esac
  case "$tap_url" in
    *[[:space:]]*)
      die "Invalid tap URL: ${tap_url}"
      ;;
  esac
}

tap_homebrew() {
  local tap="$1"
  local tap_url="$2"
  if [[ -n "$tap_url" ]]; then
    run brew tap "$tap" "$tap_url"
  else
    run brew tap "$tap"
  fi
}

install_kast() {
  local tap="$1"
  local tap_url="$2"
  local workspace_root="$3"

  log_section "Kast developer install"
  log_note "Workspace: ${workspace_root}"
  require_command brew
  tap_homebrew "$tap" "$tap_url"
  run brew install kast
  require_command kast
  run kast developer machine plugin
  log_note "Open ${workspace_root} in IntelliJ IDEA or Android Studio so the plugin can prepare workspace metadata."
  log_success "Install complete"
}

update_kast() {
  local tap="$1"
  local tap_url="$2"
  local workspace_root="$3"

  log_section "Kast developer update"
  log_note "Workspace: ${workspace_root}"
  require_command brew
  tap_homebrew "$tap" "$tap_url"
  run brew update
  log_step "brew upgrade kast"
  if ! brew upgrade kast; then
    log_note "brew upgrade kast did not complete; reinstalling kast"
    run brew reinstall kast
  fi
  require_command kast
  run kast developer machine plugin --force
  log_note "Reopen ${workspace_root} in IntelliJ IDEA or Android Studio so the plugin can refresh workspace metadata."
  log_success "Update complete"
}

verify_kast() {
  local workspace_root="$1"

  log_section "Kast developer verify"
  log_note "Workspace: ${workspace_root}"
  require_command brew
  require_command kast
  log_step "brew --prefix kast"
  brew --prefix kast >/dev/null || die "Homebrew formula 'kast' is not installed"
  run kast ready --for agent --workspace-root "$workspace_root"
  log_success "Verification complete"
}

main() {
  local command_name="install"
  local tap="$DEFAULT_TAP"
  local tap_url=""
  local workspace_root=""

  if [[ $# -gt 0 ]]; then
    case "$1" in
      install|update|verify)
        command_name="$1"
        shift
        ;;
      --help|-h|help)
        usage
        exit 0
        ;;
      --*)
        ;;
      *)
        usage
        die "Unknown command: $1"
        ;;
    esac
  fi

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --tap)
        [[ $# -ge 2 ]] || die "Missing value for --tap"
        tap="$2"
        shift 2
        ;;
      --tap=*)
        tap="${1#--tap=}"
        shift
        ;;
      --tap-url)
        [[ $# -ge 2 ]] || die "Missing value for --tap-url"
        tap_url="$2"
        shift 2
        ;;
      --tap-url=*)
        tap_url="${1#--tap-url=}"
        shift
        ;;
      --workspace-root)
        [[ $# -ge 2 ]] || die "Missing value for --workspace-root"
        workspace_root="$2"
        shift 2
        ;;
      --workspace-root=*)
        workspace_root="${1#--workspace-root=}"
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        usage
        die "Unknown argument: $1"
        ;;
    esac
  done

  validate_tap "$tap"
  if [[ -n "$tap_url" ]]; then
    validate_tap_url "$tap_url"
  fi
  require_macos

  if [[ -z "$workspace_root" ]]; then
    workspace_root="$(pwd -P)"
  fi
  workspace_root="$(resolve_existing_dir "$workspace_root")"

  case "$command_name" in
    install)
      install_kast "$tap" "$tap_url" "$workspace_root"
      ;;
    update)
      update_kast "$tap" "$tap_url" "$workspace_root"
      ;;
    verify)
      verify_kast "$workspace_root"
      ;;
    *)
      die "Unknown command: ${command_name}"
      ;;
  esac
}

main "$@"
