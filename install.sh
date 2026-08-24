#!/usr/bin/env bash
# shellcheck disable=SC2016 # Single-quoted fragments generate the installed launcher.
set -euo pipefail
IFS=$'\n\t'

PROGRAM="kast-install"
DEFAULT_REPOSITORY="amichne/kast"

fail() {
  printf '%s: %s\n' "$PROGRAM" "$*" >&2
  exit 1
}

note() {
  printf '%s: %s\n' "$PROGRAM" "$*" >&2
}

usage() {
  cat <<'USAGE'
Install or completely remove Kast-owned machine state.

Usage:
  install.sh [install] [--purge-existing] [--version <major.minor.patch>]
             [--install-root <absolute-path>] [--bin-dir <absolute-path>]
             [--runtime-store <absolute-path>]
             [--runtime-directory <absolute-path>]
             [--repository <owner/name>]
  install.sh uninstall [--install-root <absolute-path>]
             [--bin-dir <absolute-path>] [--runtime-store <absolute-path>]
             [--runtime-directory <absolute-path>]

Defaults:
  version       latest stable GitHub release
  install-root  ${XDG_DATA_HOME:-$HOME/.local/share}/kast
  bin-dir       $HOME/.local/bin
  runtime-store $HOME/.cache/kast/semantic-runtimes
  runtime-dir   ${TMPDIR:-/tmp}/kast-runtime
  repository    amichne/kast

Environment equivalents:
  KAST_VERSION
  KAST_INSTALL_ROOT
  KAST_BIN_DIR
  KAST_RUNTIME_STORE
  KAST_RUNTIME_DIRECTORY
  KAST_REPOSITORY

uninstall is the destructive recovery operation. It removes current and
historical Kast commands, installation roots, runtime caches and sockets,
configuration, launchd services, indexer processes, Homebrew formula, and old
Kast IDE plugins. It preserves repositories and non-Kast JetBrains state.

--purge-existing runs that exact operation after the requested release has
been downloaded and verified, but before any installation path is changed.

The installer downloads and verifies the control distribution and its exact
semantic runtime. Semantic commands realize that local archive into the
content-addressed runtime store without another release download.
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
  require_safe_cleanup_root "runtime directory" "$runtime_directory"
  require_safe_cleanup_root "default runtime directory" "$default_runtime_directory"
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
  remove_kast_children "$HOME/Library/Application Support/Google" "*/plugins/kast"
  remove_owned_path "$bin_dir/kast"
  remove_owned_path "$HOME/.local/bin/kast"
  remove_owned_path "$HOME/.local/bin/_kastctl"
  remove_owned_path "$install_root"
  remove_owned_path "$default_install_root"
  remove_owned_path "$legacy_install_root"
  remove_owned_path "$runtime_store"
  remove_owned_path "$runtime_cache_root"
  remove_owned_path "$runtime_directory"
  remove_owned_path "$default_runtime_directory"
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
  local has_idea_identity=false
  local has_kast_plugin=false

  unzip -Z1 "$archive" > "$listing"
  while IFS= read -r entry || [[ -n "$entry" ]]; do
    [[ -n "$entry" ]] || fail "semantic runtime contains an empty path"
    case "$entry" in
      /*|../*|*/../*|*/..)
        fail "semantic runtime contains an unsafe path: $entry"
        ;;
      kast-indexer) has_executable=true ;;
      runtime-libs/*) has_runtime_libraries=true ;;
      idea-home/product-info.json) has_idea_identity=true ;;
      idea-home/plugins/kast-indexer/*) has_kast_plugin=true ;;
    esac
  done < "$listing"
  [[ "$has_executable" == true ]] ||
    fail "semantic runtime has no kast-indexer executable"
  [[ "$has_runtime_libraries" == true ]] ||
    fail "semantic runtime has no runtime libraries"
  [[ "$has_idea_identity" == true ]] ||
    fail "semantic runtime has no IntelliJ identity"
  [[ "$has_kast_plugin" == true ]] ||
    fail "semantic runtime has no private Kast plugin"
}

verify_runtime_manifest() {
  local manifest="$1"
  local expected_name="$2"
  local expected_url="$3"
  local expected_digest="$4"
  local expected_bytes="$5"

  grep -Fq "\"fileName\":\"$expected_name\"" "$manifest" ||
    fail "control manifest identifies a different semantic runtime archive"
  grep -Fq "\"url\":\"$expected_url\"" "$manifest" ||
    fail "control manifest identifies a different semantic runtime URL"
  grep -Fq "\"sha256\":\"sha256:$expected_digest\"" "$manifest" ||
    fail "control manifest identifies a different semantic runtime digest"
  grep -Fq "\"bytes\":$expected_bytes" "$manifest" ||
    fail "control manifest identifies a different semantic runtime size"
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
      fail "installed runtime archive path is invalid: $installed_archive"
    [[ "$(shasum -a 256 "$installed_archive" | awk '{ print $1 }')" == "$expected_digest" ]] ||
      fail "installed runtime archive does not match the immutable release"
  else
    staged_runtime="$(mktemp "$runtime_root/.runtime.XXXXXX")"
    mv -f "$source_archive" "$staged_runtime"
    [[ "$(shasum -a 256 "$staged_runtime" | awk '{ print $1 }')" == "$expected_digest" ]] ||
      fail "staged runtime archive digest changed"
    mv "$staged_runtime" "$installed_archive"
    staged_runtime=""
  fi
  if [[ -e "$installed_checksum" || -L "$installed_checksum" ]]; then
    [[ -f "$installed_checksum" && ! -L "$installed_checksum" ]] ||
      fail "installed runtime checksum path is invalid: $installed_checksum"
  fi
  staged_runtime_checksum="$(mktemp "$runtime_root/.runtime-checksum.XXXXXX")"
  cp "$source_checksum" "$staged_runtime_checksum"
  [[ "$(< "$staged_runtime_checksum")" == "$(< "$source_checksum")" ]] ||
    fail "staged runtime checksum changed"
  mv -f "$staged_runtime_checksum" "$installed_checksum"
  staged_runtime_checksum=""
}

install_complete_launcher() {
  local root="$1"
  local launcher="$root/bin/kast-complete"
  if [[ -e "$launcher" || -L "$launcher" ]]; then
    [[ -f "$launcher" && ! -L "$launcher" ]] ||
      fail "complete launcher path is invalid: $launcher"
  fi
  staged_launcher="$(mktemp "$root/bin/.kast-complete.XXXXXX")"
  {
    printf '%s\n' '#!/bin/sh' 'set -eu' ''
    printf '%s\n' 'source_path="$0"'
    printf '%s\n' 'while [ -L "$source_path" ]; do'
    printf '%s\n' '  source_dir="$(CDPATH= cd -- "$(dirname -- "$source_path")" && pwd -P)"'
    printf '%s\n' '  link="$(readlink "$source_path")"'
    printf '%s\n' '  case "$link" in /*) source_path="$link" ;; *) source_path="$source_dir/$link" ;; esac'
    printf '%s\n' 'done'
    printf '%s\n' 'script_dir="$(CDPATH= cd -- "$(dirname -- "$source_path")" && pwd -P)"'
    printf '%s\n' "runtime_root=\"\$(CDPATH= cd -- \"\$script_dir/../share/kast/runtime\" && pwd -P)\""
    printf '%s\n' "runtime_archive=\"\$runtime_root/$runtime_name\""
    printf '%s\n' 'control_executable="$script_dir/kast"'
    printf '%s\n' 'if [ ! -x "$control_executable" ]; then'
    printf '%s\n' '  echo "kast: installed control executable is missing: $control_executable" >&2'
    printf '%s\n' '  exit 1'
    printf '%s\n' 'fi'
    printf '%s\n' 'if [ ! -f "$runtime_archive" ]; then'
    printf '%s\n' '  echo "kast: installed semantic runtime is missing: $runtime_archive" >&2'
    printf '%s\n' '  exit 1'
    printf '%s\n' 'fi'
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
  local smoke_store="$3"
  local version_output link

  [[ -x "$root/bin/kast" ]] || fail "control archive has no executable bin/kast"
  [[ -f "$root/share/kast/semantic-runtime.json" ]] ||
    fail "control archive has no semantic runtime manifest"
  [[ -f "$root/share/kast/operation-registry.json" ]] ||
    fail "control archive has no operation registry"
  [[ -f "$root/share/kast/wire-schema.json" ]] ||
    fail "control archive has no wire schema"

  link="$(find "$root" -type l -print -quit)"
  [[ -z "$link" ]] || fail "control archive contains a symbolic link: $link"
  [[ ! -e "$root/kast-indexer" && ! -e "$root/idea-home" ]] ||
    fail "control archive contains semantic runtime content"

  rm -rf "$smoke_store"
  version_output="$(KAST_RUNTIME_STORE="$smoke_store" "$root/bin/kast" --version)"
  case "$version_output" in
    "kast ${expected_version} (semantic runtime sha256:"*) ;;
    *) fail "installed metadata reports an unexpected version: $version_output" ;;
  esac
  KAST_RUNTIME_STORE="$smoke_store" "$root/bin/kast" --schema >/dev/null
  [[ ! -e "$smoke_store" ]] ||
    fail "local metadata commands attempted to realize the semantic runtime"
}

[[ -n "${HOME:-}" ]] || fail "HOME is unavailable"
data_home="${XDG_DATA_HOME:-${HOME}/.local/share}"
config_home="${XDG_CONFIG_HOME:-${HOME}/.config}"
default_install_root="${data_home}/kast"
legacy_install_root="${HOME}/.local/share/kast"
runtime_cache_root="${HOME}/.cache/kast"
default_runtime_directory="${TMPDIR:-/tmp}/kast-runtime"
config_root="${config_home}/kast"
legacy_config_root="${HOME}/.config/kast"
application_support_root="${HOME}/Library/Application Support/Kast"

action="install"
purge_existing=false
version="${KAST_VERSION:-}"
repository="${KAST_REPOSITORY:-$DEFAULT_REPOSITORY}"
install_root="${KAST_INSTALL_ROOT:-$default_install_root}"
bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"
runtime_store="${KAST_RUNTIME_STORE:-$runtime_cache_root/semantic-runtimes}"
runtime_directory="${KAST_RUNTIME_DIRECTORY:-$default_runtime_directory}"
process_table_command="${KAST_INSTALL_PROCESS_TABLE_COMMAND:-ps}"
process_kill_command="${KAST_INSTALL_PROCESS_KILL_COMMAND:-kill}"
version_option_set=false
repository_option_set=false

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
    --repository)
      [[ $# -ge 2 ]] || fail "--repository requires a value"
      repository="$2"
      repository_option_set=true
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

if [[ "$action" == "uninstall" ]]; then
  [[ "$purge_existing" == false ]] ||
    fail "--purge-existing is valid only with install"
  [[ "$version_option_set" == false && "$repository_option_set" == false ]] ||
    fail "--version and --repository are valid only with install"
  require_command rm
  require_command find
  require_command "$process_table_command"
  require_command "$process_kill_command"
  require_command sleep
  purge_kast
  note "uninstalled Kast"
  exit 0
fi

require_command curl
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

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_executable="${JAVA_HOME}/bin/java"
  [[ -x "$java_executable" ]] || fail "JAVA_HOME has no executable bin/java: $JAVA_HOME"
else
  require_command java
  java_executable="$(command -v java)"
fi
java_major="$(java_major_version "$java_executable")" ||
  fail "unable to identify the Java version used by the Kast launcher"
(( java_major >= 21 )) || fail "Java 21 or newer is required; found Java $java_major"

validate_repository "$repository"
require_absolute_path "install root" "$install_root"
require_absolute_path "binary directory" "$bin_dir"
require_absolute_path "runtime store" "$runtime_store"
require_absolute_path "runtime directory" "$runtime_directory"
if [[ "$purge_existing" == true ]]; then
  require_command rm
  require_command "$process_table_command"
  require_command "$process_kill_command"
  require_command sleep
  validate_cleanup_plan
fi

if [[ -z "$version" || "$version" == "latest" ]]; then
  version="$(resolve_latest_version "$repository")"
else
  version="${version#v}"
  validate_version "$version"
fi

release="v${version}"
control_name="kast-control-v${version}-macos-aarch64.tar.gz"
runtime_name="kast-semantic-runtime-${version}-macos-aarch64.zip"
release_url="https://github.com/${repository}/releases/download/${release}"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
staged_root=""
staged_runtime=""
staged_runtime_checksum=""
staged_launcher=""
cleanup() {
  rm -rf "$temporary_root"
  if [[ -n "$staged_root" && -e "$staged_root" ]]; then
    rm -rf "$staged_root"
  fi
  if [[ -n "$staged_runtime" && -e "$staged_runtime" ]]; then
    rm -f "$staged_runtime"
  fi
  if [[ -n "$staged_runtime_checksum" && -e "$staged_runtime_checksum" ]]; then
    rm -f "$staged_runtime_checksum"
  fi
  if [[ -n "$staged_launcher" && -e "$staged_launcher" ]]; then
    rm -f "$staged_launcher"
  fi
}
trap cleanup EXIT

archive="$temporary_root/$control_name"
checksum="$temporary_root/$control_name.sha256"
listing="$temporary_root/control.list"
runtime_archive="$temporary_root/$runtime_name"
runtime_checksum="$temporary_root/$runtime_name.sha256"
runtime_listing="$temporary_root/runtime.list"

note "downloading complete Kast $version distribution"
for asset in \
  "$control_name" \
  "$control_name.sha256" \
  "$runtime_name" \
  "$runtime_name.sha256"; do
  curl \
    --fail \
    --location \
    --silent \
    --show-error \
    --retry 5 \
    --retry-delay 2 \
    --output "$temporary_root/$asset" \
    "$release_url/$asset"
done

control_digest="$(verify_checksum "$archive" "$checksum" "$control_name")"
runtime_digest="$(verify_checksum "$runtime_archive" "$runtime_checksum" "$runtime_name")"
verify_archive_paths "$archive" "$listing"
verify_runtime_archive_paths "$runtime_archive" "$runtime_listing"

verified_root="$temporary_root/verified-control"
mkdir -p "$verified_root"
tar -xzf "$archive" -C "$verified_root"
verify_control_root "$verified_root" "$version" "$temporary_root/runtime-store"
runtime_bytes="$(wc -c < "$runtime_archive" | tr -d '[:space:]')"
verify_runtime_manifest \
  "$verified_root/share/kast/semantic-runtime.json" \
  "$runtime_name" \
  "$release_url/$runtime_name" \
  "$runtime_digest" \
  "$runtime_bytes"

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
  verify_control_root "$target_root" "$version" "$temporary_root/runtime-store"
else
  staged_root="$(mktemp -d "$versions_root/.install-${version}.XXXXXX")"
  tar -xzf "$archive" -C "$staged_root"
  verify_control_root "$staged_root" "$version" "$temporary_root/runtime-store"
  printf '%s\n' "$control_digest" > "$staged_root/.kast-control-sha256"
  mv "$staged_root" "$target_root"
  staged_root=""
fi

install_runtime_archive \
  "$target_root" \
  "$runtime_archive" \
  "$runtime_checksum" \
  "$runtime_digest"
install_complete_launcher "$target_root"

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
    "$install_root/current/bin/kast"|"$install_root/versions/"*/bin/kast|\
    "$install_root/current/bin/kast-complete"|"$install_root/versions/"*/bin/kast-complete) ;;
    *) fail "command path is owned by another installation: $command_link" ;;
  esac
fi

# `ln -sfn` replaces only the installer-owned links checked above. It does not
# follow a `current` symlink to the installed version directory.
ln -sfn "versions/$version" "$current_link"
ln -sfn "$install_root/current/bin/kast-complete" "$command_link"

verify_control_root "$target_root" "$version" "$temporary_root/runtime-store"
[[ "$(shasum -a 256 "$target_root/share/kast/runtime/$runtime_name" | awk '{ print $1 }')" == "$runtime_digest" ]] ||
  fail "installed semantic runtime digest changed"
KAST_RUNTIME_STORE="$temporary_root/runtime-store" "$command_link" --version >/dev/null
[[ ! -e "$temporary_root/runtime-store" ]] ||
  fail "local metadata attempted to realize the installed semantic runtime"

note "installed Kast $version"
note "command: $command_link"
note "semantic runtime archive: $target_root/share/kast/runtime/$runtime_name"
note "runtime store: ${KAST_RUNTIME_STORE:-${HOME}/.cache/kast/semantic-runtimes}"
case ":${PATH:-}:" in
  *":$bin_dir:"*) ;;
  *) note "add $bin_dir to PATH" ;;
esac
