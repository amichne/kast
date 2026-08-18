#!/usr/bin/env bash
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
Install the control-only Kast distribution.

Usage:
  install.sh [--version <major.minor.patch>] [--install-root <absolute-path>]
             [--bin-dir <absolute-path>] [--repository <owner/name>]

Defaults:
  version       latest stable GitHub release
  install-root  ${XDG_DATA_HOME:-$HOME/.local/share}/kast
  bin-dir       $HOME/.local/bin
  repository    amichne/kast

Environment equivalents:
  KAST_VERSION
  KAST_INSTALL_ROOT
  KAST_BIN_DIR
  KAST_REPOSITORY

The semantic runtime is not downloaded by this installer. Kast acquires and
verifies it on the first semantic command and stores it by content identity.
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

version="${KAST_VERSION:-}"
repository="${KAST_REPOSITORY:-$DEFAULT_REPOSITORY}"

[[ -n "${HOME:-}" ]] || fail "HOME is unavailable"
data_home="${XDG_DATA_HOME:-${HOME}/.local/share}"
install_root="${KAST_INSTALL_ROOT:-${data_home}/kast}"
bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="$2"
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
    --repository)
      [[ $# -ge 2 ]] || fail "--repository requires a value"
      repository="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

require_command curl
require_command tar
require_command shasum
require_command awk
require_command sed
require_command mktemp
require_command find
require_command readlink
require_command mv
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

if [[ -z "$version" || "$version" == "latest" ]]; then
  version="$(resolve_latest_version "$repository")"
else
  version="${version#v}"
  validate_version "$version"
fi

release="v${version}"
control_name="kast-control-v${version}-macos-aarch64.tar.gz"
release_url="https://github.com/${repository}/releases/download/${release}"

temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
staged_root=""
cleanup() {
  rm -rf "$temporary_root"
  if [[ -n "$staged_root" && -e "$staged_root" ]]; then
    rm -rf "$staged_root"
  fi
}
trap cleanup EXIT

archive="$temporary_root/$control_name"
checksum="$temporary_root/$control_name.sha256"
listing="$temporary_root/control.list"

note "downloading Kast $version control distribution"
for asset in "$control_name" "$control_name.sha256"; do
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
verify_archive_paths "$archive" "$listing"

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
    "$install_root/current/bin/kast"|"$install_root/versions/"*/bin/kast) ;;
    *) fail "command path is owned by another installation: $command_link" ;;
  esac
fi

# `ln -sfn` replaces only the installer-owned links checked above. It does not
# follow a `current` symlink to the installed version directory.
ln -sfn "versions/$version" "$current_link"
ln -sfn "$install_root/current/bin/kast" "$command_link"

verify_control_root "$target_root" "$version" "$temporary_root/runtime-store"

note "installed Kast $version"
note "command: $command_link"
note "semantic runtime: acquired on first semantic command"
note "runtime store: ${KAST_RUNTIME_STORE:-${HOME}/.cache/kast/semantic-runtimes}"
case ":${PATH:-}:" in
  *":$bin_dir:"*) ;;
  *) note "add $bin_dir to PATH" ;;
esac
