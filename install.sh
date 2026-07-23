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

usage() {
  cat >&2 <<'USAGE'
Usage: install.sh [--source <bundle-directory-or-tar.gz>] [--version <vX.Y.Z>]
                  [--configure | --autostart | --config-defaults <path>]

Downloads one platform bundle when --source is omitted, then delegates every
installation write to:

  kast setup --source <bundle>

Options:
  --configure             Select IDEA and Codex defaults interactively.
  --autostart             Open each Codex worktree in a background IDEA instance.
  --config-defaults PATH  Install defaults from an existing TOML file.
  --source PATH           Install a local bundle directory or tar.gz archive.
  --version VERSION       Install an exact release instead of the latest release.
  -h, --help              Show this help.

Environment:
  KAST_HOME          Active install root. Defaults to ~/.local/share/kast.
  KAST_RELEASES_URL  Release base URL. Defaults to the Kast GitHub releases.
  NONINTERACTIVE=1   Never close a detected JetBrains IDE.
USAGE
}

supports_color() {
  if [[ -n "${NO_COLOR:-}" ]]; then return 1; fi
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then return 0; fi
  if [[ ! -t 2 ]]; then return 1; fi
  [[ "${TERM:-}" != "dumb" ]]
}

interactive_terminal() {
  [[ "${NONINTERACTIVE:-}" != "1" && -t 0 && -t 2 ]]
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
    return
  fi
  printf '%s' "$*"
}

print_banner() {
  printf '\n' >&2
  if interactive_terminal && supports_unicode; then
    printf '%s\n' "$(colorize '1;36' '    ██╗  ██╗ █████╗ ███████╗████████╗
    ██║ ██╔╝██╔══██╗██╔════╝╚══██╔══╝
    █████╔╝ ███████║███████╗   ██║
    ██╔═██╗ ██╔══██║╚════██║   ██║
    ██║  ██║██║  ██║███████║   ██║
    ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝   ╚═╝')" >&2
  else
    printf '  %s\n' "$(colorize '1;36' "$(ui_glyph step) KAST INSTALLER")" >&2
  fi
  printf '  %s\n' "$(colorize '2' 'Kotlin semantic tooling for agents')" >&2
  printf '\n' >&2
}

ui_glyph() {
  local kind="$1"
  if supports_unicode; then
    case "$kind" in
      step) printf '◆' ;;
      success) printf '✓' ;;
      warning) printf '!' ;;
      error) printf '×' ;;
      prompt) printf '?' ;;
      *) printf '›' ;;
    esac
  else
    case "$kind" in
      step) printf '*' ;;
      success) printf '+' ;;
      warning) printf '!' ;;
      error) printf 'x' ;;
      prompt) printf '?' ;;
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

ui_prompt() {
  printf '  %s %s' "$(colorize 33 "$(ui_glyph prompt)")" "$*" >&2
}

ui_detail() {
  printf '    %s\n' "$(colorize 2 "$*")" >&2
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

reconcile_codex() {
  command -v codex >/dev/null 2>&1 || return 0
  codex plugin remove kast@kast --json >/dev/null 2>&1 || true
  codex plugin marketplace remove kast --json >/dev/null 2>&1 || true
  codex plugin marketplace add amichne/kast-marketplace --ref main --json >/dev/null
  codex plugin add kast@kast --json >/dev/null
}

JETBRAINS_PROCESS_PIDS=()
JETBRAINS_PROCESS_PRODUCTS=()
JETBRAINS_PROCESS_EXECUTABLES=()

detect_running_jetbrains_ides() {
  local process_table pid executable product
  JETBRAINS_PROCESS_PIDS=()
  JETBRAINS_PROCESS_PRODUCTS=()
  JETBRAINS_PROCESS_EXECUTABLES=()
  process_table="$(ps -axo pid=,comm=)" || die "could not inspect running JetBrains IDEs"
  while read -r pid executable; do
    product=""
    case "$executable" in
      */IntelliJ\ IDEA*.app/Contents/MacOS/idea) product="IntelliJ IDEA" ;;
      */Android\ Studio*.app/Contents/MacOS/studio) product="Android Studio" ;;
    esac
    if [[ -n "$product" && "$pid" =~ ^[0-9]+$ ]]; then
      JETBRAINS_PROCESS_PIDS+=("$pid")
      JETBRAINS_PROCESS_PRODUCTS+=("$product")
      JETBRAINS_PROCESS_EXECUTABLES+=("$executable")
    fi
  done <<<"$process_table"
}

require_jetbrains_ides_closed() {
  local index reply="" deadline
  detect_running_jetbrains_ides
  ((${#JETBRAINS_PROCESS_PIDS[@]} > 0)) || return 0
  ui_warning "A JetBrains IDE must close before its plugin is updated"
  for ((index = 0; index < ${#JETBRAINS_PROCESS_PIDS[@]}; index += 1)); do
    ui_detail "Detected ${JETBRAINS_PROCESS_PRODUCTS[$index]} (PID ${JETBRAINS_PROCESS_PIDS[$index]}): ${JETBRAINS_PROCESS_EXECUTABLES[$index]}"
  done
  if [[ "${NONINTERACTIVE:-}" == "1" ]]; then
    die "close the detected JetBrains IDE before installing the plugin"
  fi
  ui_prompt 'Close the detected editor and continue? [y/N]: '
  IFS= read -r reply || die "could not read editor closure confirmation"
  [[ "$reply" == "y" || "$reply" == "Y" ]] || die "aborted while a JetBrains IDE is running"
  env kill -TERM "${JETBRAINS_PROCESS_PIDS[@]}" || die "could not stop the detected JetBrains IDE"
  deadline=$((SECONDS + 30))
  while ((SECONDS < deadline)); do
    detect_running_jetbrains_ides
    if ((${#JETBRAINS_PROCESS_PIDS[@]} == 0)); then
      ui_success "JetBrains IDE closed"
      return 0
    fi
    sleep 1
  done
  die "timed out waiting for the detected JetBrains IDE to stop"
}

prompt_boolean() {
  local label="$1" default="$2" reply="" suffix="[y/N]"
  local choice options
  if use_fzf; then
    if [[ "$default" == "true" ]]; then
      options=($'true\tEnabled (default)' $'false\tDisabled')
    else
      options=($'false\tDisabled (default)' $'true\tEnabled')
    fi
    choice="$(select_one "$label" "${options[@]}")" || die "configuration cancelled"
    printf '%s\n' "$choice"
    return
  fi
  [[ "$default" == "true" ]] && suffix="[Y/n]"
  while true; do
    ui_prompt "$label $suffix: "
    IFS= read -r reply || die "could not read configuration selection"
    case "$reply" in
      y|Y) printf 'true\n'; return ;;
      n|N) printf 'false\n'; return ;;
      "") printf '%s\n' "$default"; return ;;
    esac
  done
}

prompt_backend() {
  local reply="" choice
  if use_fzf; then
    choice="$(select_one 'Default backend' \
      $'idea\tIDEA — compiler context from the open project (default)' \
      $'auto\tAutomatic — select the available semantic backend')" || die "configuration cancelled"
    printf '%s\n' "$choice"
    return
  fi
  while true; do
    ui_prompt 'Default backend (idea/auto) [idea]: '
    IFS= read -r reply || die "could not read backend selection"
    case "$reply" in
      idea|auto) printf '%s\n' "$reply"; return ;;
      "") printf 'idea\n'; return ;;
    esac
  done
}

use_fzf() {
  interactive_terminal && command -v fzf >/dev/null 2>&1
}

select_one() {
  local prompt="$1" selection="" reply="" index option key label
  shift
  local options=("$@")
  if use_fzf; then
    local pointer='>' marker='+'
    supports_unicode && pointer='›' && marker='✓'
    local fzf_args=(
      --height='~40%'
      --layout=reverse
      --border=rounded
      --border-label=" ${prompt} "
      --prompt="Select ${pointer} "
      --header='↑↓ move • enter select • esc cancel'
      --delimiter=$'\t'
      --with-nth=2
      --pointer="$pointer"
      --marker="$marker"
    )
    if [[ -n "${NO_COLOR:-}" ]]; then
      fzf_args+=(--no-color)
    else
      fzf_args+=(--color='border:cyan,prompt:cyan,pointer:green,marker:yellow,header:bright-black')
    fi
    selection="$(printf '%s\n' "${options[@]}" | fzf "${fzf_args[@]}")" || return 1
    printf '%s\n' "${selection%%$'\t'*}"
    return
  fi

  ui_info "$prompt"
  index=1
  for option in "${options[@]}"; do
    key="${option%%$'\t'*}"
    label="${option#*$'\t'}"
    ui_detail "${index}. ${label} [${key}]"
    index=$((index + 1))
  done
  while true; do
    ui_prompt 'Select [1]: '
    IFS= read -r reply || return 1
    [[ -n "$reply" ]] || reply=1
    if [[ "$reply" =~ ^[0-9]+$ ]] && ((reply >= 1 && reply <= ${#options[@]})); then
      selection="${options[$((reply - 1))]}"
      printf '%s\n' "${selection%%$'\t'*}"
      return
    fi
    for option in "${options[@]}"; do
      key="${option%%$'\t'*}"
      if [[ "$reply" == "$key" ]]; then
        printf '%s\n' "$key"
        return
      fi
    done
  done
}

choose_install_mode() {
  local choice
  choice="$(select_one 'Choose setup' \
    $'recommended\tRecommended — IDEA plugin and Codex hooks' \
    $'autostart\tAutostart — open each worktree in a background IDEA instance' \
    $'configure\tCustomize — review every installer default' \
    $'cancel\tCancel installation')" || die "installation cancelled"
  case "$choice" in
    autostart) autostart=1 ;;
    configure) configure=1 ;;
    cancel) ui_info "Installation cancelled"; return 1 ;;
  esac
}

write_idea_defaults() {
  local path="$1" backend="$2" strict="$3" autostart="$4"
  local profile_auto_init="$5" gradle_load="$6" auto_exclude_git="$7"
  local hooks="$8" session_start="$9" post_tool_use="${10}"
  printf '%s\n' \
    '[runtime]' \
    "defaultBackend = \"${backend}\"" \
    "strictPluginMatching = ${strict}" \
    '' \
    '[runtime.ideaLaunch]' \
    "enabled = ${autostart}" \
    'command = "idea"' \
    'waitTimeoutMillis = 90000' \
    '' \
    '[projectOpen]' \
    "profileAutoInit = ${profile_auto_init}" \
    'profile = "jetbrains-plugin"' \
    "autoExcludeGit = ${auto_exclude_git}" \
    "gradleLoadEnabled = ${gradle_load}" \
    '' \
    '[codex.hooks]' \
    "enabled = ${hooks}" \
    "sessionStart = ${session_start}" \
    "postToolUse = ${post_tool_use}" \
    '' \
    '[backends.headless]' \
    'enabled = false' \
    '' \
    '[backends.idea]' \
    'enabled = true' >"$path"
}

configure_idea_defaults() {
  local path="$1" backend strict autostart profile_auto_init gradle_load auto_exclude_git
  local hooks session_start post_tool_use
  backend="$(prompt_backend)"
  strict="$(prompt_boolean 'Require matching Kast plugin version' true)"
  autostart="$(prompt_boolean 'Open new worktrees in a background IDEA instance' false)"
  profile_auto_init="$(prompt_boolean 'Prepare Kast workspaces when projects open' true)"
  gradle_load="$(prompt_boolean 'Load the Gradle project model on open' true)"
  auto_exclude_git="$(prompt_boolean 'Exclude managed setup files from Git' true)"
  hooks="$(prompt_boolean 'Enable Codex hooks' true)"
  session_start="$(prompt_boolean 'Open worktrees on Codex session start' true)"
  post_tool_use="$(prompt_boolean 'Diagnose Kotlin files after writes' true)"
  write_idea_defaults "$path" "$backend" "$strict" "$autostart" "$profile_auto_init" \
    "$gradle_load" "$auto_exclude_git" "$hooks" "$session_start" "$post_tool_use"
  ui_success "Configuration selected"
}

run_setup() {
  local output_file="${setup_scratch}/setup-output"
  if "$@" >"$output_file"; then
    return 0
  fi
  [[ ! -s "$output_file" ]] || sed -n '1,160p' "$output_file" >&2
  return 1
}

finish_install() {
  local bin_dir="${HOME}/.local/bin"
  local path="${KAST_HOME:-${HOME}/.local/share/kast}/current/bin/kast"
  ui_success "Kast is ready"
  ui_detail "${bin_dir}/kast -> ${path}"
  if [[ ":${PATH:-}:" != *":${bin_dir}:"* ]]; then
    ui_warning "${bin_dir} is not on PATH"
    ui_detail 'export PATH="$HOME/.local/bin:$PATH"'
  fi
}

main() {
  local source="" version="" bundle_root="" bundle_archive="" platform_id=""
  local cli_archive="" cli_url="" plugin_archive="" plugin_url=""
  local configure=0 autostart=0 config_defaults=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --source) [[ $# -ge 2 ]] || die '--source requires a value'; source="$2"; shift 2 ;;
      --version) [[ $# -ge 2 ]] || die '--version requires a value'; version="$2"; shift 2 ;;
      --configure) configure=1; shift ;;
      --autostart) autostart=1; shift ;;
      --config-defaults) [[ $# -ge 2 ]] || die '--config-defaults requires a value'; config_defaults="$2"; shift 2 ;;
      -h|--help|help) usage; return 0 ;;
      *) die "unknown argument: $1" ;;
    esac
  done

  ((configure + autostart + (${#config_defaults} > 0) <= 1)) || \
    die 'pass only one of --configure, --autostart, or --config-defaults'
  if [[ -n "$source" && ($configure == 1 || $autostart == 1 || -n "$config_defaults") ]]; then
    die 'IDEA defaults require the downloaded macOS installer'
  fi

  print_banner
  setup_scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-setup.XXXXXX")"

  if [[ -z "$source" ]]; then
    require curl
    ui_step "Resolving release"
    version="${version:-$(latest_version)}"
    platform_id="$(platform)"
    ui_info "${version} · ${platform_id}"
    if [[ "$platform_id" == macos-* ]]; then
      if ((configure == 0 && autostart == 0)) && [[ -z "$config_defaults" ]] && interactive_terminal; then
        choose_install_mode || return 0
      fi
      require unzip
      cli_archive="${setup_scratch}/kast-${version}-${platform_id}.zip"
      plugin_archive="${setup_scratch}/kast-idea-${version}.zip"
      cli_url="${RELEASES_URL}/download/${version}/kast-${version}-${platform_id}.zip"
      plugin_url="${RELEASES_URL}/download/${version}/kast-idea-${version}.zip"
      download_artifact "Kast CLI" "$cli_url" "$cli_archive"
      download_artifact "IDEA plugin" "$plugin_url" "$plugin_archive"
      ui_step "Preparing installer"
      mkdir -p "${setup_scratch}/cli"
      unzip -q "$cli_archive" -d "${setup_scratch}/cli"
      [[ -f "${setup_scratch}/cli/kast" ]] || die "native CLI bundle is missing kast"
      chmod 755 "${setup_scratch}/cli/kast"
      ui_success "Installer prepared"
      require ps
      require_jetbrains_ides_closed
      if ((configure == 1)); then
        config_defaults="${setup_scratch}/config.toml"
        configure_idea_defaults "$config_defaults"
      elif ((autostart == 1)); then
        config_defaults="${setup_scratch}/config.toml"
        write_idea_defaults "$config_defaults" idea true true true true true true true true
      elif [[ -n "$config_defaults" ]]; then
        [[ -f "$config_defaults" ]] || die "config defaults do not exist: $config_defaults"
      fi
      ui_step "Installing Kast and the IDEA plugin"
      if [[ -n "$config_defaults" ]]; then
        run_setup "${setup_scratch}/cli/kast" setup --idea-plugin "$plugin_archive" --config-defaults "$config_defaults" || die "Kast setup failed"
      else
        run_setup "${setup_scratch}/cli/kast" setup --idea-plugin "$plugin_archive" || die "Kast setup failed"
      fi
      ui_success "Kast and the IDEA plugin installed"
      if command -v codex >/dev/null 2>&1; then
        ui_step "Connecting Codex"
        reconcile_codex
        ui_success "Codex connected"
      fi
      finish_install
      return 0
    fi
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

  [[ -x "${bundle_root}/bin/kast" ]] || die "bundle CLI is missing: ${bundle_root}/bin/kast"
  ui_step "Installing Kast"
  run_setup "${bundle_root}/bin/kast" setup --source "$bundle_root" || die "Kast setup failed"
  ui_success "Kast installed"
  if command -v codex >/dev/null 2>&1; then
    ui_step "Connecting Codex"
    reconcile_codex
    ui_success "Codex connected"
  fi
  finish_install
}

main "$@"
