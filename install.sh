#!/usr/bin/env bash
set -Eeuo pipefail

DEFAULT_TAP="amichne/kast"
INSTALL_URL="https://raw.githubusercontent.com/amichne/kast/main/install.sh"

usage() {
  cat >&2 <<'USAGE'
Usage:
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- [install|update|verify] [options]
  ./install.sh [install|update|verify] [options]

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

Environment:
  NONINTERACTIVE=1          Skip the install/update confirmation prompt for automation.
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

tap_description() {
  local tap="$1"
  local tap_url="$2"
  if [[ -n "$tap_url" ]]; then
    printf '%s from %s' "$tap" "$tap_url"
    return
  fi
  printf '%s' "$tap"
}

print_mutation_plan() {
  local command_name="$1"
  local tap="$2"
  local tap_url="$3"
  local workspace_root="$4"
  local tap_target
  tap_target="$(tap_description "$tap" "$tap_url")"

  log_section "Kast developer ${command_name} plan"
  log_note "Workspace: ${workspace_root}"
  log_note "Installer: ${INSTALL_URL}"
  log_note "This script will:"
  case "$command_name" in
    install)
      log_note "  - tap Homebrew repository ${tap_target}"
      log_note "  - install the Homebrew formula kast"
      log_note "  - run kast developer machine plugin"
      log_note "  - leave workspace metadata setup to IntelliJ IDEA or Android Studio"
      ;;
    update)
      log_note "  - tap Homebrew repository ${tap_target}"
      log_note "  - run brew update"
      log_note "  - upgrade or reinstall the Homebrew formula kast"
      log_note "  - converge the version-coupled IDEA plugin through the Homebrew Kast binary"
      log_note "  - leave workspace metadata refresh to IntelliJ IDEA or Android Studio"
      ;;
    *)
      die "No mutation plan for command: ${command_name}"
      ;;
  esac
}

confirm_mutation() {
  local command_name="$1"
  local tap="$2"
  local tap_url="$3"
  local workspace_root="$4"
  local reply=""

  print_mutation_plan "$command_name" "$tap" "$tap_url" "$workspace_root"
  if [[ "${NONINTERACTIVE:-}" == "1" ]]; then
    log_note "NONINTERACTIVE=1 set; skipping confirmation prompt"
    return
  fi

  printf '%s' "Press RETURN/ENTER to continue or any other key to abort: " >&2
  if ! IFS= read -r reply; then
    printf '\n' >&2
    die "Could not read confirmation. Set NONINTERACTIVE=1 to run without a prompt."
  fi
  [[ -z "$reply" ]] || die "Aborted. Set NONINTERACTIVE=1 to run without a prompt."
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

require_jetbrains_ides_closed() {
  local process_args
  local products=""
  process_args="$(ps -axo args)" || die "Could not inspect running JetBrains IDEs"
  if [[ "$process_args" == *"/IntelliJ IDEA"*".app/Contents/MacOS/idea"* ]]; then
    products="IntelliJ IDEA"
  fi
  if [[ "$process_args" == *"/Android Studio"*".app/Contents/MacOS/studio"* ]]; then
    if [[ -n "$products" ]]; then
      products="${products}, Android Studio"
    else
      products="Android Studio"
    fi
  fi
  [[ -z "$products" ]] || die "Close ${products} before installing or updating the Kast plugin, then rerun this command."
}

resolve_homebrew_kast() {
  local formula_prefix
  local kast_binary
  formula_prefix="$(brew --prefix kast)" || die "Homebrew formula 'kast' is not installed"
  kast_binary="${formula_prefix}/bin/kast"
  [[ -x "$kast_binary" ]] || die "Homebrew Kast executable is missing or not executable: ${kast_binary}"
  printf '%s\n' "$kast_binary"
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
  local kast_binary
  kast_binary="$(resolve_homebrew_kast)"
  run "$kast_binary" developer machine plugin
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
  local kast_binary
  kast_binary="$(resolve_homebrew_kast)"
  run "$kast_binary" developer machine plugin
  log_note "Reopen ${workspace_root} in IntelliJ IDEA or Android Studio so the plugin can refresh workspace metadata."
  log_success "Update complete"
}

verify_kast() {
  local workspace_root="$1"

  log_section "Kast developer verify"
  log_note "Workspace: ${workspace_root}"
  require_command brew
  log_step "brew --prefix kast"
  local kast_binary
  kast_binary="$(resolve_homebrew_kast)"
  run "$kast_binary" ready --for agent --workspace-root "$workspace_root"
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
    install|update)
      require_jetbrains_ides_closed
      confirm_mutation "$command_name" "$tap" "$tap_url" "$workspace_root"
      ;;
  esac

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
