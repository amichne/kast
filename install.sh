#!/usr/bin/env bash
# shellcheck disable=SC2016 # Single-quoted fragments generate the installed launcher.
set -euo pipefail
IFS=$'\n\t'

PROGRAM="kast-install"
DEFAULT_REPOSITORY="amichne/kast"

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
  local kind="$1"
  local color="$2"
  shift 2
  printf '  %s %s\n' "$(colorize "$color" "$(ui_glyph "$kind")")" "$*" >&2
}

print_banner() {
  printf '\n' >&2
  if supports_unicode; then
    printf '%s\n' "$(colorize '1;36' '    ██╗  ██╗ █████╗ ███████╗████████╗
    ██║ ██╔╝██╔══██╗██╔════╝╚══██╔══╝
    █████╔╝ ███████║███████╗   ██║
    ██╔═██╗ ██╔══██║╚════██║   ██║
    ██║  ██║██║  ██║███████║   ██║
    ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝   ╚═╝')" >&2
  else
    printf '  %s\n' "$(colorize '1;36' "$(ui_glyph step) KAST INSTALLER")" >&2
  fi
  printf '  %s\n\n' "$(colorize '2' 'Compiler-grounded Kotlin evidence from your terminal')" >&2
}

fail() {
  ui_line error 31 "$PROGRAM: $*"
  exit 1
}

note() {
  ui_line step 36 "$*"
}

success() { ui_line success 32 "$*"; }
info() { ui_line info 2 "$*"; }
warning() { ui_line warning 33 "$*"; }

usage() {
  cat <<'USAGE'
Install or completely remove Kast-owned machine state.

Usage:
  install.sh [install] [--purge-existing] [--version <major.minor.patch>]
             [--install-root <absolute-path>] [--bin-dir <absolute-path>]
             [--runtime-store <absolute-path>]
             [--runtime-directory <absolute-path>]
             [--cache-root <absolute-path>]
             [--enable-launchd <0-or-1>]
             [--idea-home <absolute-app-or-contents-path>]
             [--repository <owner/name>]
             [--release-base-url <https-or-file-url>]
             [--assets-directory <absolute-path>]
  install.sh uninstall [--installation-only] [--install-root <absolute-path>]
             [--bin-dir <absolute-path>]
             [--runtime-store <absolute-path>]
             [--runtime-directory <absolute-path>]
             [--cache-root <absolute-path>]

Defaults:
  version       latest stable GitHub release
  install-root  ${XDG_DATA_HOME:-$HOME/.local/share}/kast
  bin-dir       $HOME/.local/bin
  runtime-store $HOME/.cache/kast/semantic-runtimes
  runtime-dir   ${TMPDIR:-/tmp}/kast-runtime
  cache-root    $HOME/.cache/kast/intellij-caches
  launchd       0 (direct process ownership)
  IDEA home     the sole IntelliJ IDEA found in standard macOS locations
  repository    amichne/kast
  release URL   https://github.com/<repository>/releases/download
  config file   ${XDG_CONFIG_HOME:-$HOME/.config}/kast/environment

Environment equivalents:
  KAST_VERSION
  KAST_INSTALL_ROOT
  KAST_BIN_DIR
  KAST_RUNTIME_STORE
  KAST_RUNTIME_DIRECTORY
  KAST_CACHE_ROOT
  KAST_ENABLE_LAUNCHD
  KAST_INSTALL_IDEA_HOME
  KAST_INSTALL_IDEA_SEARCH_ROOT
  KAST_REPOSITORY
  KAST_RELEASE_BASE_URL

KAST_INSTALL_IDEA_HOME bypasses automatic discovery. It may name either the
IntelliJ IDEA application bundle or its Contents directory. The optional
KAST_INSTALL_IDEA_SEARCH_ROOT limits automatic traversal to one absolute root.

--assets-directory installs the four exact local archive/checksum files without
downloading. It requires --version and preserves the release URL, checksums,
archive validation, and manifest identity used for a downloaded installation.

The installer writes the four runtime settings to the config file as literal
KEY=value records. Edit that file after installation or override any setting
in the process environment; process values take precedence.

uninstall is the destructive recovery operation. It removes current and
historical Kast commands, installation roots, runtime caches and sockets,
configuration, launchd services, indexer processes, Homebrew formula, and old
Kast IDE plugins. It preserves repositories and non-Kast JetBrains state.

--installation-only removes only the selected installation, configuration,
runtime store, and cache. First stop its workspaces with `kast stop`; this mode
refuses active selected indexers and never signals another installation.

--purge-existing runs that exact operation after the requested release has
been downloaded and verified, but before any installation path is changed.

The release intentionally has two matched payloads but installs one public
command. The control distribution owns CLI parsing, schemas, lifecycle, and
wire transport. The private semantic runtime owns the headless IntelliJ
indexer and compiler integration. The control manifest pins the sidecar URL,
size, and digest, so neither payload can be substituted or installed alone.
The split keeps IntelliJ/compiler classes out of the always-available control
and lets the semantic payload be realized and cached only on semantic demand.
Neither payload contains an IDEA home or writes JetBrains plugin directories;
Kast uses a release-line-compatible local IDEA and its bundled JBR.
USAGE
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

require_absolute_path() {
  local name="$1"
  local value="$2"
  case "$value" in
    /*) ;;
    *) fail "$name must be an absolute path: $value" ;;
  esac
}

require_literal_configuration_value() {
  local name="$1"
  local value="$2"
  [[ -n "$value" ]] || fail "$name must not be empty"
  case "$value" in
    *$'\n'*|*$'\r'*) fail "$name must be one literal line" ;;
  esac
}

validate_enable_launchd() {
  case "$1" in
    0|1) ;;
    *) fail "enable launchd must be 0 or 1: $1" ;;
  esac
}

require_safe_cleanup_root() {
  local name="$1"
  local value="$2"
  local leaf
  require_absolute_path "$name" "$value"
  case "$value" in
    /|*/|*/../*|*/..|*/./*|*/.) fail "$name is not a safe cleanup root: $value" ;;
  esac
  [[ "$value" != "$HOME" ]] || fail "$name cannot be HOME: $value"
  leaf="${value##*/}"
  case "$leaf" in
    bin|etc|home|Library|local|opt|private|share|tmp|usr|Users|var)
      fail "$name is too broad for cleanup: $value"
      ;;
  esac
}

runtime_socket_directory_for() {
  local logical_directory normalized_directory namespace
  logical_directory="$1"
  require_absolute_path "logical runtime directory" "$logical_directory"
  normalized_directory="$(printf '%s' "$logical_directory" | sed -E 's:/+:/:g')"
  namespace="$(
    printf '%s' "$normalized_directory" |
      shasum -a 256 |
      awk '{ print substr($1, 1, 24) }'
  )"
  [[ "$namespace" =~ ^[0-9a-f]{24}$ ]] ||
    fail "unable to derive the physical runtime socket namespace"
  printf '/tmp/kast-runtime-%s\n' "$namespace"
}

remove_owned_path() {
  local path="$1"
  if [[ -e "$path" || -L "$path" ]]; then
    note "removing $path"
    rm -rf -- "$path"
  fi
}

remove_kast_launch_services() {
  local launchctl_command service_table label extra
  launchctl_command="$(command -v launchctl || true)"
  [[ -n "$launchctl_command" ]] || return 0
  service_table="$("$launchctl_command" list 2>/dev/null || true)"
  while IFS=$' \t' read -r _ _ label extra; do
    [[ -z "$extra" && "$label" == io.github.amichne.kast.indexer.* ]] || continue
    note "removing launchd service $label"
    "$launchctl_command" remove "$label"
  done <<< "$service_table"
}

remove_orphaned_indexers() {
  local process_table pid command alive
  # Bash 3.2 treats an empty array as unset under nounset, so retain an empty
  # sentinel and skip it at the process boundary.
  local -a indexer_pids=("")
  process_table="$("$process_table_command" -ax -o pid=,command=)"
  while IFS=$' \t' read -r pid command; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    [[ "$pid" != "$$" && "$pid" != "$PPID" ]] || continue
    case "$command" in
      *io.github.amichne.kast.indexer.KastIndexerMainKt*)
        indexer_pids+=("$pid")
        note "stopping orphaned Kast indexer process $pid"
        "$process_kill_command" -TERM "$pid" >/dev/null 2>&1 || true
        ;;
    esac
  done <<< "$process_table"

  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    alive=false
    for pid in "${indexer_pids[@]}"; do
      [[ -n "$pid" ]] || continue
      if "$process_kill_command" -0 "$pid" >/dev/null 2>&1; then
        alive=true
      fi
    done
    [[ "$alive" == true ]] || return 0
    sleep 0.1
  done
  for pid in "${indexer_pids[@]}"; do
    [[ -n "$pid" ]] || continue
    "$process_kill_command" -KILL "$pid" >/dev/null 2>&1 || true
  done
}

remove_homebrew_kast() {
  local brew_command
  brew_command="$(command -v brew || true)"
  [[ -n "$brew_command" ]] || return 0
  if "$brew_command" list --formula kast >/dev/null 2>&1; then
    note "uninstalling Homebrew formula kast"
    "$brew_command" uninstall --force kast
  fi
  if "$brew_command" list --cask kast >/dev/null 2>&1; then
    note "uninstalling Homebrew cask kast"
    "$brew_command" uninstall --cask --force kast
  fi
}

remove_kast_children() {
  local root pattern target
  root="$1"
  pattern="$2"
  [[ -d "$root" ]] || return 0
  while IFS= read -r target; do
    remove_owned_path "$target"
  done < <(find "$root" -path "$pattern" -prune -print)
}

validate_cleanup_plan() {
  require_safe_cleanup_root "install root" "$install_root"
  require_safe_cleanup_root "default install root" "$default_install_root"
  require_safe_cleanup_root "legacy install root" "$legacy_install_root"
  require_safe_cleanup_root "runtime cache root" "$runtime_cache_root"
  require_safe_cleanup_root "runtime store" "$runtime_store"
  require_safe_cleanup_root "sidecar cache root" "$cache_root"
  require_safe_cleanup_root "runtime directory" "$runtime_directory"
  require_safe_cleanup_root "default runtime directory" "$default_runtime_directory"
  require_safe_cleanup_root "runtime socket directory" "$runtime_socket_directory"
  require_safe_cleanup_root \
    "default runtime socket directory" "$default_runtime_socket_directory"
  require_safe_cleanup_root "configuration root" "$config_root"
  require_safe_cleanup_root "legacy configuration root" "$legacy_config_root"
  require_safe_cleanup_root "application support root" "$application_support_root"
  require_absolute_path "binary directory" "$bin_dir"
}

purge_kast() {
  validate_cleanup_plan
  note "purging prior Kast installations"
  remove_kast_launch_services
  remove_orphaned_indexers
  remove_homebrew_kast
  remove_kast_children "$HOME/Library/LaunchAgents" \
    "$HOME/Library/LaunchAgents/io.github.amichne.kast*.plist"
  remove_kast_children "$HOME/Library/Application Support/JetBrains" "*/plugins/kast"
  remove_kast_children "$HOME/Library/Application Support/JetBrains" "*/plugins/kast-indexer"
  remove_kast_children "$HOME/Library/Application Support/Google" "*/plugins/kast"
  remove_kast_children "$HOME/Library/Application Support/Google" "*/plugins/kast-indexer"
  remove_owned_path "$bin_dir/kast"
  remove_owned_path "$HOME/.local/bin/kast"
  remove_owned_path "$HOME/.local/bin/_kastctl"
  remove_owned_path "$install_root"
  remove_owned_path "$default_install_root"
  remove_owned_path "$legacy_install_root"
  remove_owned_path "$runtime_store"
  remove_owned_path "$cache_root"
  remove_owned_path "$runtime_cache_root"
  remove_owned_path "$runtime_directory"
  remove_owned_path "$default_runtime_directory"
  remove_owned_path "$runtime_socket_directory"
  remove_owned_path "$default_runtime_socket_directory"
  remove_owned_path "$config_root"
  remove_owned_path "$legacy_config_root"
  remove_owned_path "$application_support_root"
  note "prior Kast installations removed"
}

validate_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "version must be <major>.<minor>.<patch>: $1"
}

validate_repository() {
  [[ "$1" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
    fail "repository must be <owner>/<name>: $1"
}

validate_release_base_url() {
  case "$1" in
    https://?*|file:///*) ;;
    *) fail "release base URL must use https:// or an absolute file:// URL: $1" ;;
  esac
  case "$1" in
    *\?*|*\#*) fail "release base URL must not contain a query or fragment: $1" ;;
  esac
}

java_major_version() {
  local java_executable="$1"
  local version_line version major
  version_line="$("$java_executable" -version 2>&1 | awk 'NR == 1 { print; exit }')"
  version="$(printf '%s\n' "$version_line" | sed -nE 's/.*version "([^"]+)".*/\1/p')"
  [[ -n "$version" ]] || return 1
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    version="${version#*.}"
    major="${version%%.*}"
  fi
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$major"
}

java_runtime_home() {
  local java_executable="$1"
  local reported_home physical_home executable_directory
  reported_home="$("$java_executable" -XshowSettings:properties -version 2>&1 |
    sed -nE 's/^[[:space:]]*java\.home = (.*)$/\1/p' |
    awk 'NR == 1 { print; exit }')"
  if [[ -n "$reported_home" ]]; then
    require_absolute_path "Java runtime home" "$reported_home"
    physical_home="$(CDPATH='' cd -- "$reported_home" && pwd -P)" || return 1
    [[ -x "$physical_home/bin/java" ]] || return 1
    printf '%s\n' "$physical_home"
    return 0
  fi
  executable_directory="$(CDPATH='' cd -- "$(dirname -- "$java_executable")" && pwd -P)" ||
    return 1
  physical_home="$(CDPATH='' cd -- "$executable_directory/.." && pwd -P)" || return 1
  [[ -x "$physical_home/bin/java" ]] || return 1
  printf '%s\n' "$physical_home"
}

idea_bundled_java_major() {
  local release_file="$1"
  local version major architecture
  [[ -f "$release_file" ]] || return 1
  version="$(
    sed -nE 's/^JAVA_VERSION="([^"]+)".*$/\1/p' "$release_file" |
      awk 'NR == 1 { print; exit }'
  )"
  [[ -n "$version" ]] || return 1
  major="${version%%.*}"
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  architecture="$(
    sed -nE 's/^OS_ARCH="([^"]+)".*$/\1/p' "$release_file" |
      awk 'NR == 1 { print; exit }'
  )"
  case "$architecture" in
    aarch64|arm64) ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$major"
}

canonical_idea_home() {
  local requested="$1"
  local candidate physical_home java_major
  case "$requested" in
    *.app) candidate="$requested/Contents" ;;
    *) candidate="$requested" ;;
  esac
  [[ -d "$candidate" ]] || return 1
  physical_home="$(CDPATH='' cd -- "$candidate" && pwd -P)" || return 1
  [[ -f "$physical_home/Resources/build.txt" ]] || return 1
  [[ -d "$physical_home/plugins/Kotlin" ]] || return 1
  [[ -x "$physical_home/jbr/Contents/Home/bin/java" ]] || return 1
  java_major="$(
    idea_bundled_java_major "$physical_home/jbr/Contents/Home/release"
  )" || return 1
  (( java_major >= 25 )) || return 1
  printf '%s\n' "$physical_home"
}

discover_installed_idea_home() {
  local configured_root root application candidate existing
  local selected_count=0
  local -a search_roots=("")
  local -a candidates=("")

  configured_root="${KAST_INSTALL_IDEA_SEARCH_ROOT:-}"
  if [[ -n "$configured_root" ]]; then
    require_absolute_path "IDEA search root" "$configured_root"
    [[ -d "$configured_root" ]] || fail "IDEA search root is unavailable: $configured_root"
    search_roots+=("$configured_root")
  else
    search_roots+=(
      "/Applications"
      "$HOME/Applications"
      "$HOME/Library/Application Support/JetBrains/Toolbox/apps"
    )
  fi

  for root in "${search_roots[@]}"; do
    [[ -n "$root" && -d "$root" ]] || continue
    while IFS= read -r -d '' application; do
      candidate="$(canonical_idea_home "$application" || true)"
      [[ -n "$candidate" ]] || continue
      for existing in "${candidates[@]}"; do
        [[ "$existing" != "$candidate" ]] || continue 2
      done
      candidates+=("$candidate")
      selected_count=$((selected_count + 1))
    done < <(
      find "$root" \( -type d -o -type l \) -name '*.app' -prune \
        -name 'IntelliJ IDEA*.app' -print0
    )
  done

  case "$selected_count" in
    1)
      for candidate in "${candidates[@]}"; do
        [[ -z "$candidate" ]] || printf '%s\n' "$candidate"
      done
      ;;
    0)
      fail "no IntelliJ IDEA with a bundled JBR was found; install IDEA or set KAST_INSTALL_IDEA_HOME"
      ;;
    *)
      warning "multiple IntelliJ IDEA installations provide a bundled JBR"
      for candidate in "${candidates[@]}"; do
        [[ -z "$candidate" ]] || info "candidate: $candidate"
      done
      fail "IDEA discovery is ambiguous; rerun with --idea-home"
      ;;
  esac
}

resolve_installer_idea_home() {
  local requested="$1"
  local resolved
  if [[ -n "$requested" ]]; then
    require_absolute_path "IDEA home" "$requested"
    resolved="$(canonical_idea_home "$requested" || true)"
    [[ -n "$resolved" ]] ||
      fail "IDEA home has no build metadata, Kotlin plugin, or bundled JBR: $requested"
    printf '%s\n' "$resolved"
  else
    discover_installed_idea_home
  fi
}

load_persisted_runtime_configuration() {
  local path="$1"
  local line
  [[ -e "$path" || -L "$path" ]] || return 0
  [[ -f "$path" && ! -L "$path" ]] || fail "runtime configuration is not a regular file: $path"
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      ''|\#*) ;;
      KAST_RUNTIME_STORE=*)
        [[ "$persisted_runtime_store_set" == false ]] ||
          fail "runtime configuration repeats KAST_RUNTIME_STORE"
        persisted_runtime_store="${line#*=}"
        persisted_runtime_store_set=true
        ;;
      KAST_RUNTIME_DIRECTORY=*)
        [[ "$persisted_runtime_directory_set" == false ]] ||
          fail "runtime configuration repeats KAST_RUNTIME_DIRECTORY"
        persisted_runtime_directory="${line#*=}"
        persisted_runtime_directory_set=true
        ;;
      KAST_CACHE_ROOT=*)
        [[ "$persisted_cache_root_set" == false ]] ||
          fail "runtime configuration repeats KAST_CACHE_ROOT"
        persisted_cache_root="${line#*=}"
        persisted_cache_root_set=true
        ;;
      KAST_ENABLE_LAUNCHD=*)
        [[ "$persisted_enable_launchd_set" == false ]] ||
          fail "runtime configuration repeats KAST_ENABLE_LAUNCHD"
        persisted_enable_launchd="${line#*=}"
        persisted_enable_launchd_set=true
        ;;
      *) fail "runtime configuration has an unsupported record: $line" ;;
    esac
  done < "$path"
}

shell_single_quote() {
  printf "'"
  printf '%s' "$1" | sed "s/'/'\"'\"'/g"
  printf "'"
}

resolve_latest_version() {
  local repository="$1"
  local effective_url tag
  effective_url="$(
    curl \
      --fail \
      --location \
      --silent \
      --show-error \
      --retry 5 \
      --retry-delay 2 \
      --output /dev/null \
      --write-out '%{url_effective}' \
      "https://github.com/${repository}/releases/latest"
  )"
  tag="${effective_url%/}"
  tag="${tag##*/}"
  tag="${tag#v}"
  validate_version "$tag"
  printf '%s\n' "$tag"
}

verify_checksum() {
  local payload="$1"
  local checksum_file="$2"
  local expected_name="$3"
  local line_count expected observed_name extra actual

  line_count="$(awk 'END { print NR }' "$checksum_file")"
  [[ "$line_count" == "1" ]] || fail "checksum file must contain exactly one record"

  expected=""
  observed_name=""
  extra=""
  IFS=' ' read -r expected observed_name extra < "$checksum_file" ||
    fail "checksum file is unreadable"
  [[ -z "$extra" ]] || fail "checksum file contains unexpected fields"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail "checksum is not SHA-256"
  [[ "$observed_name" == "$expected_name" ]] ||
    fail "checksum identifies $observed_name, expected $expected_name"

  actual="$(shasum -a 256 "$payload" | awk '{ print $1 }')"
  [[ "$actual" == "$expected" ]] || fail "SHA-256 mismatch for $expected_name"
  printf '%s\n' "$actual"
}

verify_archive_paths() {
  local archive="$1"
  local listing="$2"
  local entry

  tar -tzf "$archive" > "$listing"
  while IFS= read -r entry || [[ -n "$entry" ]]; do
    [[ -n "$entry" ]] || fail "control archive contains an empty path"
    case "$entry" in
      /*|../*|*/../*|*/..)
        fail "control archive contains an unsafe path: $entry"
        ;;
    esac
  done < "$listing"
}

verify_runtime_archive_paths() {
  local archive="$1"
  local listing="$2"
  local entry
  local has_executable=false
  local has_runtime_libraries=false
  local has_private_plugin=false

  unzip -Z1 "$archive" > "$listing"
  while IFS= read -r entry || [[ -n "$entry" ]]; do
    [[ -n "$entry" ]] || fail "sidecar payload contains an empty path"
    case "$entry" in
      /*|../*|*/../*|*/..)
        fail "sidecar payload contains an unsafe path: $entry"
        ;;
      idea-home|idea-home/*|*/product-info.json|*/plugins/Kotlin/*|*/plugins/gradle/*)
        fail "sidecar payload contains an IDEA distribution entry: $entry"
        ;;
      kast-indexer) has_executable=true ;;
      runtime-libs/*) has_runtime_libraries=true ;;
      private-plugins|private-plugins/|private-plugins/kast-indexer|\
        private-plugins/kast-indexer/|private-plugins/kast-indexer/lib|\
        private-plugins/kast-indexer/lib/) ;;
      private-plugins/kast-indexer/lib/*) has_private_plugin=true ;;
      *) fail "sidecar payload contains an unexpected root: $entry" ;;
    esac
  done < "$listing"
  [[ "$has_executable" == true ]] || fail "sidecar payload has no kast-indexer executable"
  [[ "$has_runtime_libraries" == true ]] || fail "sidecar payload has no launcher runtime"
  [[ "$has_private_plugin" == true ]] || fail "sidecar payload has no private Kast extension"
}

verify_runtime_manifest() {
  local manifest="$1"
  local expected_name="$2"
  local expected_url="$3"
  local expected_digest="$4"
  local expected_bytes="$5"

  grep -Fq "\"fileName\":\"$expected_name\"" "$manifest" ||
    fail "control manifest identifies a different sidecar archive"
  grep -Fq "\"url\":\"$expected_url\"" "$manifest" ||
    fail "control manifest identifies a different sidecar URL"
  grep -Fq "\"sha256\":\"sha256:$expected_digest\"" "$manifest" ||
    fail "control manifest identifies a different sidecar digest"
  grep -Fq "\"bytes\":$expected_bytes" "$manifest" ||
    fail "control manifest identifies a different sidecar size"
}

install_runtime_archive() {
  local root="$1"
  local source_archive="$2"
  local source_checksum="$3"
  local expected_digest="$4"
  local runtime_root="$root/share/kast/runtime"
  local installed_archive="$runtime_root/$runtime_name"
  local installed_checksum="$runtime_root/$runtime_name.sha256"

  if [[ -e "$runtime_root" || -L "$runtime_root" ]]; then
    [[ -d "$runtime_root" && ! -L "$runtime_root" ]] ||
      fail "installed runtime root is invalid: $runtime_root"
  else
    mkdir -p "$runtime_root"
  fi
  if [[ -e "$installed_archive" || -L "$installed_archive" ]]; then
    [[ -f "$installed_archive" && ! -L "$installed_archive" ]] ||
      fail "installed sidecar archive path is invalid: $installed_archive"
    [[ "$(shasum -a 256 "$installed_archive" | awk '{ print $1 }')" == "$expected_digest" ]] ||
      fail "installed sidecar archive does not match the immutable release"
  else
    staged_runtime="$(mktemp "$runtime_root/.runtime.XXXXXX")"
    mv -f "$source_archive" "$staged_runtime"
    [[ "$(shasum -a 256 "$staged_runtime" | awk '{ print $1 }')" == "$expected_digest" ]] ||
      fail "staged sidecar archive digest changed"
    mv "$staged_runtime" "$installed_archive"
    staged_runtime=""
  fi
  staged_runtime_checksum="$(mktemp "$runtime_root/.runtime-checksum.XXXXXX")"
  cp "$source_checksum" "$staged_runtime_checksum"
  mv -f "$staged_runtime_checksum" "$installed_checksum"
  staged_runtime_checksum=""
}

install_runtime_configuration() {
  local path="$1"
  local root
  root="$(dirname -- "$path")"
  if [[ -e "$root" || -L "$root" ]]; then
    [[ -d "$root" && ! -L "$root" ]] ||
      fail "runtime configuration root is invalid: $root"
  else
    mkdir -p "$root"
  fi
  if [[ -e "$path" || -L "$path" ]]; then
    [[ -f "$path" && ! -L "$path" ]] ||
      fail "runtime configuration path is invalid: $path"
  fi
  staged_configuration="$(mktemp "$root/.environment.XXXXXX")"
  {
    printf '%s\n' \
      '# Kast runtime configuration. Values are literal; shell syntax is not evaluated.' \
      '# A variable already present in the process environment takes precedence.' \
      '# Verified and extracted versions of the private semantic runtime.'
    printf 'KAST_RUNTIME_STORE=%s\n' "$runtime_store"
    printf '%s\n' '# Logical root used to derive short per-repository socket namespaces.'
    printf 'KAST_RUNTIME_DIRECTORY=%s\n' "$runtime_directory"
    printf '%s\n' '# Private IntelliJ config, system, plugin, log, and optional seed caches.'
    printf 'KAST_CACHE_ROOT=%s\n' "$cache_root"
    printf '%s\n' '# 0 starts detached processes directly; 1 delegates ownership to launchd.'
    printf 'KAST_ENABLE_LAUNCHD=%s\n' "$enable_launchd"
  } > "$staged_configuration"
  chmod 600 "$staged_configuration"
  mv -f "$staged_configuration" "$path"
  staged_configuration=""
}

install_complete_launcher() {
  local root="$1"
  local java_executable="$2"
  local java_home="$3"
  local config_file="$4"
  local launcher="$root/bin/kast-complete"
  staged_launcher="$(mktemp "$root/bin/.kast-complete.XXXXXX")"
  {
    printf '%s\n' '#!/bin/sh' 'set -eu' ''
    printf '%s\n' 'script_path="$0"' 'link_count=0'
    printf '%s\n' 'while [ -L "$script_path" ]; do'
    printf '%s\n' '  link_count=$((link_count + 1))'
    printf '%s\n' '  [ "$link_count" -le 16 ] || { echo "kast: launcher symlink cycle" >&2; exit 1; }'
    printf '%s\n' '  link_target="$(readlink "$script_path")"'
    printf '%s\n' '  case "$link_target" in'
    printf '%s\n' '    /*) script_path="$link_target" ;;'
    printf '%s\n' '    *) script_path="$(dirname -- "$script_path")/$link_target" ;;'
    printf '%s\n' '  esac'
    printf '%s\n' 'done'
    printf '%s\n' 'script_dir="$(CDPATH= cd -- "$(dirname -- "$script_path")" && pwd -P)"'
    printf 'config_file=%s\n' "$(shell_single_quote "$config_file")"
    printf '%s\n' 'if [ -e "$config_file" ] || [ -L "$config_file" ]; then'
    printf '%s\n' '  if [ ! -f "$config_file" ] || [ -L "$config_file" ]; then'
    printf '%s\n' '    echo "kast: runtime configuration is not a regular file: $config_file" >&2'
    printf '%s\n' '    exit 1'
    printf '%s\n' '  fi'
    printf '%s\n' \
      '  seen_runtime_store=false' \
      '  seen_runtime_directory=false' \
      '  seen_cache_root=false' \
      '  seen_enable_launchd=false'
    printf '%s\n' '  while IFS= read -r config_line || [ -n "$config_line" ]; do'
    printf '%s\n' '    case "$config_line" in'
    printf '%s\n' "      ''|'#'*) ;;"
    printf '%s\n' '      KAST_RUNTIME_STORE=*)'
    printf '%s\n' '        [ "$seen_runtime_store" = false ] || { echo "kast: runtime configuration repeats KAST_RUNTIME_STORE" >&2; exit 1; }'
    printf '%s\n' '        seen_runtime_store=true'
    printf '%s\n' '        [ "${KAST_RUNTIME_STORE+x}" = x ] || KAST_RUNTIME_STORE=${config_line#*=}'
    printf '%s\n' '        ;;'
    printf '%s\n' '      KAST_RUNTIME_DIRECTORY=*)'
    printf '%s\n' '        [ "$seen_runtime_directory" = false ] || { echo "kast: runtime configuration repeats KAST_RUNTIME_DIRECTORY" >&2; exit 1; }'
    printf '%s\n' '        seen_runtime_directory=true'
    printf '%s\n' '        [ "${KAST_RUNTIME_DIRECTORY+x}" = x ] || KAST_RUNTIME_DIRECTORY=${config_line#*=}'
    printf '%s\n' '        ;;'
    printf '%s\n' '      KAST_CACHE_ROOT=*)'
    printf '%s\n' '        [ "$seen_cache_root" = false ] || { echo "kast: runtime configuration repeats KAST_CACHE_ROOT" >&2; exit 1; }'
    printf '%s\n' '        seen_cache_root=true'
    printf '%s\n' '        [ "${KAST_CACHE_ROOT+x}" = x ] || KAST_CACHE_ROOT=${config_line#*=}'
    printf '%s\n' '        ;;'
    printf '%s\n' '      KAST_ENABLE_LAUNCHD=*)'
    printf '%s\n' '        [ "$seen_enable_launchd" = false ] || { echo "kast: runtime configuration repeats KAST_ENABLE_LAUNCHD" >&2; exit 1; }'
    printf '%s\n' '        seen_enable_launchd=true'
    printf '%s\n' '        [ "${KAST_ENABLE_LAUNCHD+x}" = x ] || KAST_ENABLE_LAUNCHD=${config_line#*=}'
    printf '%s\n' '        ;;'
    printf '%s\n' '      *) echo "kast: runtime configuration has an unsupported record: $config_line" >&2; exit 1 ;;'
    printf '%s\n' '    esac'
    printf '%s\n' '  done < "$config_file"'
    printf '%s\n' '  export KAST_RUNTIME_STORE KAST_RUNTIME_DIRECTORY KAST_CACHE_ROOT KAST_ENABLE_LAUNCHD'
    printf '%s\n' 'fi'
    printf '%s\n' "runtime_archive=\"\$script_dir/../share/kast/runtime/$runtime_name\""
    printf '%s\n' 'control_executable="$script_dir/kast"'
    printf '%s\n' 'if [ ! -x "$control_executable" ] || [ ! -f "$runtime_archive" ]; then'
    printf '%s\n' '  echo "kast: installed control or sidecar payload is missing" >&2'
    printf '%s\n' '  exit 1'
    printf '%s\n' 'fi'
    printf 'export JAVA=%s\n' "$(shell_single_quote "$java_executable")"
    printf 'export JAVA_HOME=%s\n' "$(shell_single_quote "$java_home")"
    printf '%s\n' 'export KAST_RUNTIME_ARCHIVE="$runtime_archive"'
    printf '%s\n' 'exec "$control_executable" "$@"'
  } > "$staged_launcher"
  chmod 755 "$staged_launcher"
  mv -f "$staged_launcher" "$launcher"
  staged_launcher=""
}

verify_control_root() {
  local root="$1"
  local expected_version="$2"
  local java_executable="$3"
  local java_home="$4"
  local version_output link

  [[ -x "$root/bin/kast" ]] || fail "control archive has no executable bin/kast"
  [[ -f "$root/share/kast/semantic-runtime.json" ]] ||
    fail "control archive has no semantic-runtime manifest"
  [[ -f "$root/share/kast/operation-registry.json" ]] ||
    fail "control archive has no operation registry"
  [[ -f "$root/share/kast/wire-schema.json" ]] ||
    fail "control archive has no wire schema"

  link="$(find "$root" -type l -print -quit)"
  [[ -z "$link" ]] || fail "control archive contains a symbolic link: $link"
  [[ ! -e "$root/kast-indexer" && ! -e "$root/idea-home" ]] ||
    fail "control archive contains semantic runtime content"

  version_output="$(
    JAVA="$java_executable" JAVA_HOME="$java_home" KAST_RUNTIME_STORE="$runtime_store" \
      "$root/bin/kast" --version
  )"
  [[ "$version_output" == "kast ${expected_version} (IntelliJ sidecar)" ]] ||
    fail "installed metadata reports an unexpected version: $version_output"
  JAVA="$java_executable" JAVA_HOME="$java_home" KAST_RUNTIME_STORE="$runtime_store" \
    "$root/bin/kast" --schema >/dev/null
}

[[ -n "${HOME:-}" ]] || fail "HOME is unavailable"
data_home="${XDG_DATA_HOME:-${HOME}/.local/share}"
config_home="${XDG_CONFIG_HOME:-${HOME}/.config}"
default_install_root="${data_home}/kast"
legacy_install_root="${HOME}/.local/share/kast"
runtime_cache_root="${HOME}/.cache/kast"
default_runtime_directory="${TMPDIR:-/tmp}/kast-runtime"
config_root="${config_home}/kast"
config_file="$config_root/environment"
legacy_config_root="${HOME}/.config/kast"
application_support_root="${HOME}/Library/Application Support/Kast"

if [[ $# -eq 1 ]]; then
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
  esac
elif [[ $# -eq 2 ]]; then
  case "$1:$2" in
    install:--help|install:-h|uninstall:--help|uninstall:-h)
      usage
      exit 0
      ;;
  esac
fi

persisted_runtime_store=""
persisted_runtime_store_set=false
persisted_runtime_directory=""
persisted_runtime_directory_set=false
persisted_cache_root=""
persisted_cache_root_set=false
persisted_enable_launchd=""
persisted_enable_launchd_set=false
load_persisted_runtime_configuration "$config_file"

action="install"
installation_only=false
purge_existing=false
version="${KAST_VERSION:-}"
repository="${KAST_REPOSITORY:-$DEFAULT_REPOSITORY}"
release_base_url="${KAST_RELEASE_BASE_URL:-}"
assets_directory=""
install_root="${KAST_INSTALL_ROOT:-$default_install_root}"
bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"
if [[ ${KAST_RUNTIME_STORE+x} == x ]]; then
  runtime_store="$KAST_RUNTIME_STORE"
elif [[ "$persisted_runtime_store_set" == true ]]; then
  runtime_store="$persisted_runtime_store"
else
  runtime_store="$runtime_cache_root/semantic-runtimes"
fi
if [[ ${KAST_RUNTIME_DIRECTORY+x} == x ]]; then
  runtime_directory="$KAST_RUNTIME_DIRECTORY"
elif [[ "$persisted_runtime_directory_set" == true ]]; then
  runtime_directory="$persisted_runtime_directory"
else
  runtime_directory="$default_runtime_directory"
fi
if [[ ${KAST_CACHE_ROOT+x} == x ]]; then
  cache_root="$KAST_CACHE_ROOT"
elif [[ "$persisted_cache_root_set" == true ]]; then
  cache_root="$persisted_cache_root"
else
  cache_root="$runtime_cache_root/intellij-caches"
fi
if [[ ${KAST_ENABLE_LAUNCHD+x} == x ]]; then
  enable_launchd="$KAST_ENABLE_LAUNCHD"
elif [[ "$persisted_enable_launchd_set" == true ]]; then
  enable_launchd="$persisted_enable_launchd"
else
  enable_launchd=0
fi
idea_home="${KAST_INSTALL_IDEA_HOME:-}"
runtime_socket_directory=""
default_runtime_socket_directory=""
process_table_command="${KAST_INSTALL_PROCESS_TABLE_COMMAND:-ps}"
process_kill_command="${KAST_INSTALL_PROCESS_KILL_COMMAND:-kill}"
version_option_set=false
repository_option_set=false
release_base_url_option_set=false
enable_launchd_option_set=false
idea_home_option_set=false

if [[ ${#} -gt 0 ]]; then
  case "$1" in
    install|uninstall)
      action="$1"
      shift
      ;;
  esac
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --purge-existing)
      purge_existing=true
      shift
      ;;
    --installation-only)
      installation_only=true
      shift
      ;;
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="$2"
      version_option_set=true
      shift 2
      ;;
    --install-root)
      [[ $# -ge 2 ]] || fail "--install-root requires a value"
      install_root="$2"
      shift 2
      ;;
    --bin-dir)
      [[ $# -ge 2 ]] || fail "--bin-dir requires a value"
      bin_dir="$2"
      shift 2
      ;;
    --runtime-store)
      [[ $# -ge 2 ]] || fail "--runtime-store requires a value"
      runtime_store="$2"
      shift 2
      ;;
    --runtime-directory)
      [[ $# -ge 2 ]] || fail "--runtime-directory requires a value"
      runtime_directory="$2"
      shift 2
      ;;
    --cache-root)
      [[ $# -ge 2 ]] || fail "--cache-root requires a value"
      cache_root="$2"
      shift 2
      ;;
    --enable-launchd)
      [[ $# -ge 2 ]] || fail "--enable-launchd requires a value"
      enable_launchd="$2"
      enable_launchd_option_set=true
      shift 2
      ;;
    --idea-home)
      [[ $# -ge 2 ]] || fail "--idea-home requires a value"
      idea_home="$2"
      idea_home_option_set=true
      shift 2
      ;;
    --repository)
      [[ $# -ge 2 ]] || fail "--repository requires a value"
      repository="$2"
      repository_option_set=true
      shift 2
      ;;
    --release-base-url)
      [[ $# -ge 2 ]] || fail "--release-base-url requires a value"
      release_base_url="$2"
      release_base_url_option_set=true
      shift 2
      ;;
    --assets-directory)
      [[ $# -ge 2 ]] || fail "--assets-directory requires a value"
      assets_directory="$2"
      [[ "$assets_directory" == /* && -d "$assets_directory" && ! -L "$assets_directory" ]] ||
        fail "--assets-directory must name an existing absolute directory, not a symlink"
      assets_directory="$(CDPATH='' cd -- "$assets_directory" && pwd -P)"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$idea_home_option_set" == false || -n "$idea_home" ]] ||
  fail "--idea-home must not be empty"

if [[ "$action" == "uninstall" ]]; then
  [[ "$purge_existing" == false ]] ||
    fail "--purge-existing is valid only with install"
  [[ -z "$assets_directory" ]] || fail "--assets-directory is valid only with install"
  [[ "$version_option_set" == false && "$repository_option_set" == false && \
    "$release_base_url_option_set" == false && "$enable_launchd_option_set" == false && \
    "$idea_home_option_set" == false ]] ||
    fail "--version, --repository, --release-base-url, --enable-launchd, and --idea-home are valid only with install"
  require_command rm
  require_command find
  require_command shasum
  require_command awk
  require_command sed
  require_command "$process_table_command"
  require_command "$process_kill_command"
  require_command sleep
  runtime_socket_directory="$(runtime_socket_directory_for "$runtime_directory")"
  default_runtime_socket_directory="$(
    runtime_socket_directory_for "$default_runtime_directory"
  )"
  if [[ "$installation_only" == true ]]; then
    validate_cleanup_plan
    process_table="$("$process_table_command" -ax -o pid=,command=)"
    while IFS=$' \t' read -r selected_pid selected_command; do
      case "$selected_command" in
        *io.github.amichne.kast.indexer.KastIndexerMainKt*)
          case "$selected_command" in
            *"$runtime_directory/"*|*"$runtime_socket_directory/"*)
              fail "selected installation has an active indexer; run kast stop in its workspace first"
              ;;
          esac
          ;;
      esac
    done <<< "$process_table"
    for selected_path in "$bin_dir/kast" "$install_root" "$runtime_store" \
      "$cache_root" "$runtime_directory" "$runtime_socket_directory" "$config_root"; do
      remove_owned_path "$selected_path"
    done
    note "uninstalled selected Kast installation"
    exit 0
  fi
  purge_kast
  note "uninstalled Kast"
  exit 0
fi

[[ "$installation_only" == false ]] || fail "--installation-only is valid only with uninstall"

if [[ -z "$assets_directory" ]]; then
  require_command curl
else
  [[ "$version_option_set" == true && -n "$version" && "$version" != latest ]] ||
    fail "--assets-directory requires an explicit --version"
fi
require_command tar
require_command unzip
require_command shasum
require_command awk
require_command sed
require_command grep
require_command wc
require_command tr
require_command mktemp
require_command find
require_command dirname
require_command mkdir
require_command readlink
require_command mv
require_command cp
require_command chmod
require_command ln
require_command uname

[[ "$(uname -s)" == "Darwin" ]] || fail "only macOS is supported"
case "$(uname -m)" in
  arm64|aarch64) ;;
  *) fail "only macOS/AArch64 is supported" ;;
esac

print_banner
note "discovering an installed IntelliJ IDEA and its bundled runtime"
idea_home="$(resolve_installer_idea_home "$idea_home")"
bundled_java_home="$(CDPATH='' cd -- "$idea_home/jbr/Contents/Home" && pwd -P)" ||
  fail "unable to resolve IntelliJ IDEA's bundled runtime home: $idea_home"
java_executable="$bundled_java_home/bin/java"
java_home="$(java_runtime_home "$java_executable")" ||
  fail "unable to identify IntelliJ IDEA's bundled runtime home"
[[ "$java_home" == "$bundled_java_home" ]] ||
  fail "IntelliJ IDEA's bundled Java reports an external runtime home: $java_home"
java_executable="$java_home/bin/java"
java_major="$(java_major_version "$java_executable")" ||
  fail "unable to identify IntelliJ IDEA's bundled Java version"
(( java_major >= 25 )) ||
  fail "IntelliJ IDEA's bundled runtime must be Java 25 or newer; found Java $java_major"
info "IDEA home: $idea_home"
info "installer runtime: $java_home (Java $java_major)"

validate_repository "$repository"
if [[ -n "$release_base_url" ]]; then
  validate_release_base_url "$release_base_url"
  [[ "$repository_option_set" == false ]] ||
    fail "--repository and --release-base-url are mutually exclusive"
fi
require_absolute_path "install root" "$install_root"
require_absolute_path "binary directory" "$bin_dir"
require_absolute_path "runtime store" "$runtime_store"
require_absolute_path "runtime directory" "$runtime_directory"
require_absolute_path "sidecar cache root" "$cache_root"
require_literal_configuration_value "runtime store" "$runtime_store"
require_literal_configuration_value "runtime directory" "$runtime_directory"
require_literal_configuration_value "sidecar cache root" "$cache_root"
validate_enable_launchd "$enable_launchd"
runtime_socket_directory="$(runtime_socket_directory_for "$runtime_directory")"
default_runtime_socket_directory="$(
  runtime_socket_directory_for "$default_runtime_directory"
)"
if [[ "$purge_existing" == true ]]; then
  require_command rm
  require_command "$process_table_command"
  require_command "$process_kill_command"
  require_command sleep
  validate_cleanup_plan
fi

if [[ -z "$version" || "$version" == "latest" ]]; then
  [[ -z "$release_base_url" ]] ||
    fail "an explicit --version is required with a release base URL"
  version="$(resolve_latest_version "$repository")"
else
  version="${version#v}"
  validate_version "$version"
fi

release="v${version}"
control_name="kast-control-v${version}-macos-aarch64.tar.gz"
runtime_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
if [[ -n "$release_base_url" ]]; then
  release_url="${release_base_url%/}/${release}"
else
  release_url="https://github.com/${repository}/releases/download/${release}"
fi

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
staged_root=""
staged_runtime=""
staged_runtime_checksum=""
staged_launcher=""
staged_configuration=""
cleanup() {
  rm -rf "$temporary_root"
  if [[ -n "$staged_root" && -e "$staged_root" ]]; then
    rm -rf "$staged_root"
  fi
  [[ -z "$staged_runtime" ]] || rm -f "$staged_runtime"
  [[ -z "$staged_runtime_checksum" ]] || rm -f "$staged_runtime_checksum"
  [[ -z "$staged_launcher" ]] || rm -f "$staged_launcher"
  [[ -z "$staged_configuration" ]] || rm -f "$staged_configuration"
}
trap cleanup EXIT

archive="$temporary_root/$control_name"
checksum="$temporary_root/$control_name.sha256"
listing="$temporary_root/control.list"
runtime_archive="$temporary_root/$runtime_name"
runtime_checksum="$temporary_root/$runtime_name.sha256"
runtime_listing="$temporary_root/runtime.list"

note "downloading the matched Kast $version control and private semantic runtime"
for asset in \
  "$control_name" \
  "$control_name.sha256" \
  "$runtime_name" \
  "$runtime_name.sha256"; do
  if [[ -n "$assets_directory" ]]; then
    [[ -f "$assets_directory/$asset" && ! -L "$assets_directory/$asset" ]] ||
      fail "local release asset must be a regular file: $asset"
    cp "$assets_directory/$asset" "$temporary_root/$asset"
  else
    curl \
      --fail \
      --location \
      --silent \
      --show-error \
      --retry 5 \
      --retry-delay 2 \
      --output "$temporary_root/$asset" \
      "$release_url/$asset"
  fi
done

control_digest="$(verify_checksum "$archive" "$checksum" "$control_name")"
runtime_digest="$(verify_checksum "$runtime_archive" "$runtime_checksum" "$runtime_name")"
verify_archive_paths "$archive" "$listing"
verify_runtime_archive_paths "$runtime_archive" "$runtime_listing"

verified_root="$temporary_root/verified-control"
mkdir -p "$verified_root"
tar -xzf "$archive" -C "$verified_root"
verify_control_root "$verified_root" "$version" "$java_executable" "$java_home"
verify_runtime_manifest \
  "$verified_root/share/kast/semantic-runtime.json" \
  "$runtime_name" \
  "$release_url/$runtime_name" \
  "$runtime_digest" \
  "$(wc -c < "$runtime_archive" | tr -d ' ')"

if [[ "$purge_existing" == true ]]; then
  purge_kast
fi

versions_root="$install_root/versions"
target_root="$versions_root/$version"
current_link="$install_root/current"
command_link="$bin_dir/kast"
mkdir -p "$versions_root" "$bin_dir"

if [[ -e "$target_root" || -L "$target_root" ]]; then
  [[ -d "$target_root" && ! -L "$target_root" ]] ||
    fail "existing version path is not a directory: $target_root"
  [[ -f "$target_root/.kast-control-sha256" ]] ||
    fail "existing version has no control identity: $target_root"
  [[ "$(< "$target_root/.kast-control-sha256")" == "$control_digest" ]] ||
    fail "existing version does not match the immutable release: $target_root"
  verify_control_root "$target_root" "$version" "$java_executable" "$java_home"
  [[ -f "$target_root/.kast-runtime-sha256" ]] ||
    fail "existing version has no sidecar identity: $target_root"
  [[ "$(< "$target_root/.kast-runtime-sha256")" == "$runtime_digest" ]] ||
    fail "existing version sidecar does not match the immutable release: $target_root"
  install_runtime_archive "$target_root" "$runtime_archive" "$runtime_checksum" "$runtime_digest"
  install_complete_launcher \
    "$target_root" "$java_executable" "$java_home" "$config_file"
else
  staged_root="$(mktemp -d "$versions_root/.install-${version}.XXXXXX")"
  tar -xzf "$archive" -C "$staged_root"
  verify_control_root "$staged_root" "$version" "$java_executable" "$java_home"
  install_runtime_archive "$staged_root" "$runtime_archive" "$runtime_checksum" "$runtime_digest"
  install_complete_launcher \
    "$staged_root" "$java_executable" "$java_home" "$config_file"
  printf '%s\n' "$control_digest" > "$staged_root/.kast-control-sha256"
  printf '%s\n' "$runtime_digest" > "$staged_root/.kast-runtime-sha256"
  mv "$staged_root" "$target_root"
  staged_root=""
fi

if [[ -e "$current_link" && ! -L "$current_link" ]]; then
  fail "managed current path is not a symbolic link: $current_link"
fi
if [[ -L "$current_link" ]]; then
  prior_current="$(readlink "$current_link")"
  case "$prior_current" in
    versions/*|"$install_root/versions/"*) ;;
    *) fail "managed current link points outside the installation: $prior_current" ;;
  esac
fi

if [[ -e "$command_link" || -L "$command_link" ]]; then
  [[ -L "$command_link" ]] || fail "command path already exists and is not managed: $command_link"
  prior_command="$(readlink "$command_link")"
  case "$prior_command" in
    "$install_root/current/bin/kast-complete"|"$install_root/versions/"*/bin/kast-complete) ;;
    *) fail "command path is owned by another installation: $command_link" ;;
  esac
fi

install_runtime_configuration "$config_file"

# `ln -sfn` replaces only the installer-owned links checked above. It does not
# follow a `current` symlink to the installed version directory.
ln -sfn "versions/$version" "$current_link"
ln -sfn "$install_root/current/bin/kast-complete" "$command_link"

verify_control_root "$target_root" "$version" "$java_executable" "$java_home"
"$command_link" --version >/dev/null

note "installed Kast $version"
note "command: $command_link"
note "configuration: $config_file"
note "private sidecar: $target_root/share/kast/runtime/$runtime_name"
info "runtime knobs: KAST_RUNTIME_STORE, KAST_RUNTIME_DIRECTORY, KAST_CACHE_ROOT, KAST_ENABLE_LAUNCHD"
case ":${PATH:-}:" in
  *":$bin_dir:"*) ;;
  *) warning "add $bin_dir to PATH" ;;
esac
