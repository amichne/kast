#!/usr/bin/env bash
set -Eeuo pipefail

RELEASES_URL="${KAST_RELEASES_URL:-https://github.com/amichne/kast/releases}"
setup_scratch=""

cleanup() {
  if [[ -n "$setup_scratch" && -d "$setup_scratch" ]]; then
    find "$setup_scratch" -depth -delete
  fi
}
trap cleanup EXIT

kast_repository_root() {
  local script_directory repository_root
  command -v git >/dev/null 2>&1 || return 1
  script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" 2>/dev/null && pwd -P)" || return 1
  repository_root="$(git -C "$script_directory" rev-parse --show-toplevel 2>/dev/null)" || return 1
  [[ "$repository_root" == "$script_directory" ]] || return 1
  [[ -x "$repository_root/gradlew" && -f "$repository_root/settings.gradle.kts" ]] || return 1
  git -C "$repository_root" ls-files --error-unmatch \
    install.sh gradlew settings.gradle.kts >/dev/null 2>&1 || return 1
  grep -Eq '^[[:space:]]*rootProject\.name[[:space:]]*=[[:space:]]*"kast"[[:space:]]*$' \
    "$repository_root/settings.gradle.kts" || return 1
  printf '%s\n' "$repository_root"
}

usage() {
  cat >&2 <<'USAGE'
Usage: install.sh [--source <bundle-directory-or-tar.gz>] [--version <vX.Y.Z>] [--force]
                  [--harness <codex|claude|copilot|none>]...

Downloads one platform bundle when --source is omitted, then delegates every
installation write to:

  libexec/kastctl setup --source <bundle>

Options:
  --harness HARNESS  Install resources for one agent harness. Repeatable.
                     Defaults to every detected harness; none disables it.
  --source PATH      Install a local bundle directory or tar.gz archive.
  --version VERSION  Install an exact release instead of the latest release.
  --force            Remove prior Kast-owned state before reinstalling.
  -h, --help         Show this help.

Environment:
  KAST_HOME          Active install root. Defaults to ~/.local/share/kast.
  KAST_RELEASES_URL  Release base URL. Defaults to the Kast GitHub releases.
USAGE
  if kast_repository_root >/dev/null; then
    cat >&2 <<'USAGE'

Repository development:
  ./install.sh --development [--clean] [--harness <codex|claude|copilot|none>]...

  --development  Build, install, and ready this Kast Git worktree.
  --clean        Reinstall Kast-owned state; preserve build caches.
USAGE
  fi
}

supports_color() {
  [[ -z "${NO_COLOR:-}" ]] || return 1
  [[ "${CLICOLOR_FORCE:-}" != "1" ]] || return 0
  [[ -t 2 && "${TERM:-}" != "dumb" ]]
}

supports_unicode() {
  [[ "${KAST_ASCII:-}" != "1" ]] || return 1
  case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
    C|POSIX) return 1 ;;
    *) return 0 ;;
  esac
}

colorize() {
  local code="$1"
  shift
  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
  else
    printf '%s' "$*"
  fi
}

ui_glyph() {
  local kind="$1"
  if supports_unicode; then
    case "$kind" in
      step) printf '◆' ;;
      success) printf '✓' ;;
      warning) printf '!' ;;
      error) printf '×' ;;
      *) printf '›' ;;
    esac
  else
    case "$kind" in
      step) printf '*' ;;
      success) printf '+' ;;
      warning) printf '!' ;;
      error) printf 'x' ;;
      *) printf '>' ;;
    esac
  fi
}

ui_line() {
  local kind="$1" color="$2"
  shift 2
  printf '  %s %s\n' "$(colorize "$color" "$(ui_glyph "$kind")")" "$*" >&2
}
ui_step() { ui_line step 36 "$*"; }
ui_success() { ui_line success 32 "$*"; }
ui_warning() { ui_line warning 33 "$*"; }
ui_info() { ui_line info 2 "$*"; }
ui_detail() { printf '    %s\n' "$(colorize 2 "$*")" >&2; }

print_banner() {
  printf '\n  %s\n\n' "$(colorize '1;36' "$(ui_glyph step) KAST INSTALLER")" >&2
}

die() {
  ui_line error 31 "$*"
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

platform() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "${os}:${arch}" in
    Darwin:x86_64) printf 'macos-x64\n' ;;
    Darwin:arm64|Darwin:aarch64) printf 'macos-arm64\n' ;;
    Linux:x86_64|Linux:amd64) printf 'linux-x64\n' ;;
    Linux:arm64|Linux:aarch64) printf 'linux-arm64\n' ;;
    *) die "unsupported platform: ${os} ${arch}" ;;
  esac
}

latest_version() {
  local effective
  effective="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "${RELEASES_URL}/latest")"
  printf '%s\n' "${effective##*/}"
}

download_artifact() {
  local label="$1" url="$2" destination="$3"
  ui_step "Downloading ${label}"
  curl -fsSL --output "$destination" "$url"
  ui_success "${label} downloaded"
}

run_quiet() {
  local output_file="${setup_scratch}/command-output"
  if "$@" >"$output_file" 2>&1; then
    return 0
  fi
  [[ ! -s "$output_file" ]] || sed -n '1,160p' "$output_file" >&2
  return 1
}

install_agent_harnesses() {
  (($# > 0)) || return 0
  local agent_path="${KAST_HOME:-${HOME}/.local/share/kast}/current/bin/kast"
  local harness
  local -a args=(__internal resources install)
  [[ -x "$agent_path" ]] || die "installed Kast agent CLI is missing: $agent_path"
  for harness in "$@"; do
    args+=(--harness "$harness")
  done
  ui_step "Connecting agent harnesses"
  "$agent_path" "${args[@]}" || die "agent harness installation failed"
  ui_success "Agent harnesses connected"
}

finish_install() {
  local bin_dir="${HOME}/.local/bin"
  local install_root="${KAST_HOME:-${HOME}/.local/share/kast}/current/bin"
  ui_success "Kast is ready"
  ui_detail "${bin_dir}/kast -> ${install_root}/kast"
  if [[ ":${PATH:-}:" != *":${bin_dir}:"* ]]; then
    ui_warning "${bin_dir} is not on PATH"
    ui_detail 'export PATH="$HOME/.local/bin:$PATH"'
  fi
}

main() {
  local source="" version="" bundle_root="" bundle_archive="" platform_id=""
  local force=0 development=0 development_clean=0 repository_root="" active_agent=""
  local harness requested none_selected=0 already_selected
  local -a setup_args=() gradle_args=() requested_harnesses=() selected_harnesses=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --source) [[ $# -ge 2 ]] || die '--source requires a value'; source="$2"; shift 2 ;;
      --version) [[ $# -ge 2 ]] || die '--version requires a value'; version="$2"; shift 2 ;;
      --force) force=1; shift ;;
      --development) development=1; shift ;;
      --clean) development_clean=1; shift ;;
      --harness)
        [[ $# -ge 2 ]] || die '--harness requires a value'
        case "$2" in
          codex|claude|copilot|none) requested_harnesses+=("$2") ;;
          *) die "unknown harness: $2" ;;
        esac
        shift 2
        ;;
      -h|--help|help) usage; return 0 ;;
      *) die "unknown argument: $1" ;;
    esac
  done

  if ((development == 1 || development_clean == 1)); then
    repository_root="$(kast_repository_root)" \
      || die 'development options are available only from the Kast Git repository'
  fi
  ((development_clean == 0 || development == 1)) || die '--clean requires --development'
  if ((development == 1)) && { [[ -n "$source" || -n "$version" ]] || ((force == 1)); }; then
    die '--development cannot be combined with release installer options'
  fi

  if ((${#requested_harnesses[@]} == 0)); then
    for harness in codex claude copilot; do
      command -v "$harness" >/dev/null 2>&1 && selected_harnesses+=("$harness")
    done
  else
    for harness in "${requested_harnesses[@]}"; do
      if [[ "$harness" == "none" ]]; then
        none_selected=1
        continue
      fi
      already_selected=0
      for requested in "${selected_harnesses[@]}"; do
        [[ "$requested" == "$harness" ]] && already_selected=1
      done
      ((already_selected == 1)) || selected_harnesses+=("$harness")
    done
    if ((none_selected == 1 && ${#selected_harnesses[@]} > 0)); then
      die 'none cannot be combined with another harness'
    fi
  fi

  print_banner
  setup_scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-setup.XXXXXX")"

  if ((development == 1)); then
    gradle_args=("$repository_root/gradlew" "--project-dir" "$repository_root")
    ((development_clean == 0)) || gradle_args+=("-PkastDevelopmentClean=true")
    gradle_args+=(refreshDevelopmentMachine --no-daemon --console=plain)
    ui_step "Refreshing the local development installation"
    run_quiet "${gradle_args[@]}" || die "local development setup failed"
    ui_success "Local development installation refreshed"
    install_agent_harnesses "${selected_harnesses[@]}"
    active_agent="${KAST_HOME:-${HOME}/.local/share/kast}/current/bin/kast"
    [[ -x "$active_agent" ]] || die "installed Kast agent CLI is missing: $active_agent"
    ui_step "Building the repository database"
    (cd -- "$repository_root" && run_quiet "$active_agent" up) \
      || die "repository database did not become ready"
    ui_success "Repository database ready"
    finish_install
    return 0
  fi

  if [[ -z "$source" ]]; then
    require curl
    ui_step "Resolving release"
    version="${version:-$(latest_version)}"
    platform_id="$(platform)"
    ui_info "${version} · ${platform_id}"
    bundle_archive="${setup_scratch}/kast-bundle.tar.gz"
    source="${RELEASES_URL}/download/${version}/kast-${platform_id}-${version}.tar.gz"
    download_artifact "Kast bundle" "$source" "$bundle_archive"
    source="$bundle_archive"
  fi

  if [[ -d "$source" ]]; then
    bundle_root="$(cd -- "$source" && pwd -P)"
  else
    require tar
    [[ -f "$source" ]] || die "bundle source does not exist: $source"
    ui_step "Extracting Kast bundle"
    mkdir -p "${setup_scratch}/bundle"
    tar -xzf "$source" -C "${setup_scratch}/bundle"
    bundle_root="$(find "${setup_scratch}/bundle" -mindepth 1 -maxdepth 1 -type d -print -quit)"
    [[ -n "$bundle_root" ]] || die "bundle archive has no root directory: $source"
  fi

  [[ -x "${bundle_root}/libexec/kastctl" ]] \
    || die "bundle control CLI is missing: ${bundle_root}/libexec/kastctl"
  [[ -x "${bundle_root}/bin/kast" ]] \
    || die "bundle agent CLI is missing: ${bundle_root}/bin/kast"
  ui_step "Installing Kast"
  setup_args=("${bundle_root}/libexec/kastctl" setup --source "$bundle_root")
  ((force == 0)) || setup_args+=(--force)
  run_quiet "${setup_args[@]}" || die "Kast setup failed"
  ui_success "Kast installed"
  install_agent_harnesses "${selected_harnesses[@]}"
  finish_install
}

main "$@"
