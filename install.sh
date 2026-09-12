#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

readonly PROGRAM="kast-install"
readonly REPOSITORY="amichne/kast"
readonly INSTALL_DOWNLOAD_RETRIES=5
readonly INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS=2000

fail() {
  printf '%s: %s\n' "$PROGRAM" "$*" >&2
  exit 1
}

note() {
  printf 'kast: %s\n' "$*" >&2
}

usage() {
  cat <<'USAGE'
Install or remove Kast for the current user.

Usage:
  install.sh [--idea-home <absolute-path>] [--version <major.minor.patch>] [--dry-run]
  install.sh uninstall [--dry-run]
  install.sh --local session [--idea-home <absolute-path>]
  install.sh --local persistent [--idea-home <absolute-path>]
  install.sh --help

The default command installs the latest release into:
  ${XDG_DATA_HOME:-$HOME/.local/share}/kast

and creates `kast` and `kast-codex` in:
  $HOME/.local/bin

Pass arguments to a downloaded installer after Bash's `$0` separator:
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --help

`--dry-run` downloads and verifies the matched release, then prints the exact
installation plan without changing installation state.
USAGE
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

require_absolute_path() {
  case "$2" in
    /*) ;;
    *) fail "$1 must be an absolute path: $2" ;;
  esac
}

validate_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "version must be <major>.<minor>.<patch>: $1"
}

canonical_idea_home() {
  local requested="$1"
  local candidate physical release major architecture
  case "$requested" in
    *.app) candidate="$requested/Contents" ;;
    *) candidate="$requested" ;;
  esac
  [[ -d "$candidate" ]] || return 1
  physical="$(CDPATH='' cd -- "$candidate" && pwd -P)" || return 1
  [[ -f "$physical/Resources/build.txt" ]] || return 1
  [[ -d "$physical/plugins/Kotlin" ]] || return 1
  [[ -x "$physical/jbr/Contents/Home/bin/java" ]] || return 1
  release="$physical/jbr/Contents/Home/release"
  [[ -f "$release" ]] || return 1
  major="$(sed -nE 's/^JAVA_VERSION="([0-9]+).*/\1/p' "$release" | awk 'NR == 1 { print; exit }')"
  architecture="$(sed -nE 's/^OS_ARCH="([^"]+)".*/\1/p' "$release" | awk 'NR == 1 { print; exit }')"
  [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 25 )) || return 1
  case "$architecture" in arm64|aarch64) ;; *) return 1 ;; esac
  printf '%s\n' "$physical"
}

discover_idea_home() {
  local root application candidate existing
  local -a roots candidates
  roots=("/Applications" "$HOME/Applications" "$HOME/Library/Application Support/JetBrains/Toolbox/apps")
  candidates=()
  if [[ -n "${KAST_INSTALL_IDEA_SEARCH_ROOT:-}" ]]; then
    require_absolute_path "IDEA search root" "$KAST_INSTALL_IDEA_SEARCH_ROOT"
    roots=("$KAST_INSTALL_IDEA_SEARCH_ROOT")
  fi
  for root in "${roots[@]}"; do
    [[ -d "$root" ]] || continue
    while IFS= read -r -d '' application; do
      candidate="$(canonical_idea_home "$application" || true)"
      [[ -n "$candidate" ]] || continue
      for existing in "${candidates[@]+"${candidates[@]}"}"; do
        [[ "$existing" != "$candidate" ]] || continue 2
      done
      candidates+=("$candidate")
    done < <(find "$root" \( -type d -o -type l \) -name 'IntelliJ IDEA*.app' -prune -print0)
  done
  case "${#candidates[@]}" in
    1) printf '%s\n' "${candidates[0]}" ;;
    0) fail "no compatible IntelliJ IDEA was found; install IDEA or pass --idea-home" ;;
    *)
      printf '%s\n' "kast-install: multiple compatible IDEA installations:" >&2
      printf '  %s\n' "${candidates[@]}" >&2
      fail "IDEA discovery is ambiguous; pass --idea-home"
      ;;
  esac
}

resolve_latest_version() {
  local effective tag
  effective="$(curl --fail --location --silent --show-error \
    --retry "$INSTALL_DOWNLOAD_RETRIES" --retry-delay "$((INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS / 1000))" \
    --output /dev/null --write-out '%{url_effective}' \
    "https://github.com/$REPOSITORY/releases/latest")"
  tag="${effective%/}"
  tag="${tag##*/}"
  tag="${tag#v}"
  validate_version "$tag"
  printf '%s\n' "$tag"
}

fetch_asset() {
  local name="$1"
  local destination="$2"
  if [[ -n "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]]; then
    require_absolute_path "assets directory" "$KAST_INSTALL_ASSETS_DIRECTORY"
    [[ -f "$KAST_INSTALL_ASSETS_DIRECTORY/$name" && ! -L "$KAST_INSTALL_ASSETS_DIRECTORY/$name" ]] ||
      fail "local release asset is unavailable: $name"
    cp "$KAST_INSTALL_ASSETS_DIRECTORY/$name" "$destination"
  else
    curl --fail --location --silent --show-error \
      --retry "$INSTALL_DOWNLOAD_RETRIES" --retry-delay "$((INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS / 1000))" \
      --output "$destination" "$release_url/$name"
  fi
}

verify_checksum() {
  local payload="$1"
  local checksum="$2"
  local name="$3"
  local expected observed extra actual lines
  lines="$(awk 'END { print NR }' "$checksum")"
  [[ "$lines" == 1 ]] || fail "checksum must contain exactly one record: $name"
  IFS=' ' read -r expected observed extra < "$checksum" || fail "checksum is unreadable: $name"
  [[ -z "${extra:-}" && "$expected" =~ ^[0-9a-f]{64}$ && "$observed" == "$name" ]] ||
    fail "checksum record is invalid: $name"
  actual="$(shasum -a 256 "$payload" | awk '{ print $1 }')"
  [[ "$actual" == "$expected" ]] || fail "SHA-256 mismatch: $name"
  printf '%s\n' "$actual"
}

extract_control() {
  local archive="$1"
  local destination="$2"
  python3 - "$archive" "$destination" <<'PYTHON'
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile

archive, destination = Path(sys.argv[1]), Path(sys.argv[2])
destination.mkdir(mode=0o700)
with tarfile.open(archive, "r:gz") as source:
    members = source.getmembers()
    if not members or len(members) > 4096:
        raise SystemExit("kast-install: control archive layout rejected")
    for member in members:
        path = PurePosixPath(member.name)
        if path.is_absolute() or not path.parts or any(part in ("", ".", "..") for part in path.parts):
            raise SystemExit("kast-install: control archive path rejected")
        if not (member.isdir() or member.isreg()):
            raise SystemExit("kast-install: control archive contains a non-file entry")
    for member in members:
        target = destination.joinpath(*PurePosixPath(member.name).parts)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        opened = source.extractfile(member)
        if opened is None:
            raise SystemExit("kast-install: control archive file is unreadable")
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, member.mode & 0o777)
        with opened, os.fdopen(descriptor, "wb") as output:
            shutil.copyfileobj(opened, output)
        os.chmod(target, member.mode & 0o777, follow_symlinks=False)
PYTHON
}

read_idea_identity() {
  local metadata="$1/Resources/product-info.json"
  [[ -f "$metadata" && ! -L "$metadata" ]] || fail "IDEA product metadata is unavailable"
  python3 - "$metadata" <<'PYTHON'
import json
from pathlib import Path
import re
import sys

metadata = Path(sys.argv[1])
value = json.loads(metadata.read_bytes())
build = value.get("buildNumber")
directory = value.get("dataDirectoryName")
if not isinstance(build, str) or re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", build) is None:
    raise SystemExit("kast-install: IDEA build identity is invalid")
if not isinstance(directory, str) or re.fullmatch(r"[A-Za-z0-9._-]+", directory) is None:
    raise SystemExit("kast-install: IDEA data-directory identity is invalid")
print(f"{build}\t{directory}")
PYTHON
}

extract_hosted_plugin() {
  local archive="$1"
  local destination="$2"
  local version="$3"
  local build="$4"
  python3 - "$archive" "$destination" "$version" "$build" <<'PYTHON'
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import xml.etree.ElementTree as etree
import zipfile

archive, destination = Path(sys.argv[1]), Path(sys.argv[2])
version, build = sys.argv[3], sys.argv[4]
destination.mkdir(mode=0o700)
with zipfile.ZipFile(archive) as source:
    members = source.infolist()
    if not members or len(members) > 4096 or sum(member.file_size for member in members) > 128 * 1024 * 1024:
        raise SystemExit("kast-install: hosted plugin archive layout rejected")
    for member in members:
        path = PurePosixPath(member.filename)
        mode = member.external_attr >> 16
        if (path.is_absolute() or not path.parts or path.parts[0] != "kast-ide-hosted"
                or any(part in ("", ".", "..") for part in path.parts)
                or stat.S_ISLNK(mode)):
            raise SystemExit("kast-install: hosted plugin archive path rejected")
        if member.is_dir():
            continue
        target = destination.joinpath(*path.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with source.open(member) as opened, os.fdopen(descriptor, "wb") as output:
            shutil.copyfileobj(opened, output)

plugin = destination / "kast-ide-hosted"
library = plugin / "lib"
if not library.is_dir():
    raise SystemExit("kast-install: hosted plugin has no library directory")
descriptors = []
for jar in library.iterdir():
    if not jar.is_file() or jar.is_symlink() or jar.suffix != ".jar":
        continue
    try:
        with zipfile.ZipFile(jar) as candidate:
            descriptors.append(candidate.read("META-INF/plugin.xml"))
    except KeyError:
        pass
if len(descriptors) != 1:
    raise SystemExit("kast-install: hosted plugin descriptor identity is ambiguous")
root = etree.fromstring(descriptors[0])
identity = root.findtext("id")
plugin_version = root.findtext("version")
compatibility = root.find("idea-version")
since = None if compatibility is None else compatibility.get("since-build")
until = None if compatibility is None else compatibility.get("until-build")
if identity != "io.github.amichne.kast.ide-hosted":
    raise SystemExit("kast-install: hosted plugin identity is invalid")
if plugin_version != version:
    raise SystemExit("kast-install: hosted plugin version is mismatched")
release_line = build.split(".", 1)[0]
if since != release_line or until != release_line + ".*":
    raise SystemExit("kast-install: hosted plugin IDEA release line is mismatched")
PYTHON
}

activate_hosted_plugin() {
  local staged="$1"
  local plugin_root="$2"
  python3 - "$staged" "$plugin_root" <<'PYTHON'
from pathlib import Path
import shutil
import sys
import uuid

staged, root = Path(sys.argv[1]), Path(sys.argv[2])
source = staged / "kast-ide-hosted"
root.mkdir(parents=True, exist_ok=True)
if root.is_symlink() or not root.is_dir():
    raise SystemExit("kast-install: IDEA plugin directory is not a physical directory")
destination = root / "kast-ide-hosted"
if destination.is_symlink() or (destination.exists() and not destination.is_dir()):
    raise SystemExit("kast-install: existing hosted plugin is not a physical directory")
token = uuid.uuid4().hex
candidate = root / f".kast-ide-hosted.install-{token}"
backup = root / f".kast-ide-hosted.backup-{token}"
shutil.copytree(source, candidate)
replaced = destination.exists()
try:
    if replaced:
        destination.replace(backup)
    candidate.replace(destination)
except BaseException:
    if candidate.exists():
        shutil.rmtree(candidate)
    if replaced and backup.exists() and not destination.exists():
        backup.replace(destination)
    raise
if backup.exists():
    shutil.rmtree(backup)
PYTHON
}

script_source="${BASH_SOURCE[0]:-}"
checkout_mode=""
if [[ "${1:-}" == "--local" ]]; then
  [[ -n "$script_source" && -f "$script_source" ]] ||
    fail "--local requires an installer file from a Kast checkout"
  installer_directory="$(CDPATH='' cd -- "$(dirname -- "$script_source")" && pwd -P)"
  checkout_mode="${2:-}"
  case "$checkout_mode" in session|persistent) ;; *) fail '--local requires session or persistent' ;; esac
  shift 2
  # Checkout builds admit only the IDE selection option, before resolving or building anything.
  case "$#" in
    0) ;;
    2) [[ $1 == --idea-home && -n $2 ]] || fail 'unsupported checkout installation option' ;;
    *) fail 'unsupported checkout installation option' ;;
  esac
fi

action=install
version="${KAST_VERSION:-}"
idea_home="${KAST_INSTALL_IDEA_HOME:-}"
mode=apply

if [[ "${1:-}" == uninstall ]]; then
  action=uninstall
  shift
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --dry-run)
      mode=plan
      shift
      ;;
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="${2#v}"
      shift 2
      ;;
    --idea-home)
      [[ "$action" == install && $# -ge 2 ]] || fail "--idea-home is valid only for installation"
      idea_home="$2"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ -n "${HOME:-}" ]] || fail "HOME is unavailable"
install_root="${KAST_INSTALL_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/kast}"
bin_directory="${KAST_BIN_DIR:-$HOME/.local/bin}"
require_absolute_path "install root" "$install_root"
require_absolute_path "binary directory" "$bin_directory"

if [[ "$action" == uninstall ]]; then
  command="$install_root/current/bin/kast-complete"
  [[ -x "$command" ]] || fail "no selected Kast installation exists at $install_root"
  if [[ "$mode" == plan ]]; then
    exec "$command" installation remove --dry-run --json
  else
    exec "$command" installation remove --json
  fi
fi

for command in curl shasum awk sed find python3 cp mktemp uname; do require_command "$command"; done
[[ "$(uname -s)" == Darwin ]] || fail "only macOS is supported"
case "$(uname -m)" in arm64|aarch64) ;; *) fail "only macOS on Apple silicon is supported" ;; esac

if [[ -n "$idea_home" ]]; then
  require_absolute_path "IDEA home" "$idea_home"
  idea_home="$(canonical_idea_home "$idea_home" || true)"
  [[ -n "$idea_home" ]] || fail "IDEA home is incompatible"
else
  idea_home="$(discover_idea_home)"
fi
java_home="$idea_home/jbr/Contents/Home"
IFS=$'\t' read -r idea_build idea_data_directory < <(read_idea_identity "$idea_home")
[[ -n "$idea_build" && -n "$idea_data_directory" ]] || fail "IDEA product identity is unavailable"
if [[ -n "$checkout_mode" ]]; then
  export KAST_INSTALL_IDEA_HOME="$idea_home"
  exec bash "$installer_directory/packaging/install-checkout.sh" "$installer_directory/install.sh" "$checkout_mode" --idea-home "$idea_home"
fi
idea_plugin_root="$HOME/Library/Application Support/JetBrains/$idea_data_directory/plugins"

if [[ -z "$version" || "$version" == latest ]]; then
  [[ -z "${KAST_RELEASE_BASE_URL:-}" ]] || fail "KAST_RELEASE_BASE_URL requires KAST_VERSION"
  [[ -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]] || fail "local assets require KAST_VERSION"
  version="$(resolve_latest_version)"
else
  validate_version "$version"
fi

release="v$version"
release_url="${KAST_RELEASE_BASE_URL:-https://github.com/$REPOSITORY/releases/download}"
release_url="${release_url%/}/$release"
control_name="kast-control-v$version-macos-aarch64.tar.gz"
plugin_name="kast-ide-hosted-v$version-idea-${idea_build%%.*}.zip"
temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
temporary_root="$(CDPATH='' cd -- "$temporary_root" && pwd -P)"
cleanup() { rm -rf -- "$temporary_root"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

note "preparing Kast $version"
for name in "$control_name" "$control_name.sha256" \
  "$plugin_name" "$plugin_name.sha256"; do
  fetch_asset "$name" "$temporary_root/$name"
done
control_digest="$(verify_checksum "$temporary_root/$control_name" "$temporary_root/$control_name.sha256" "$control_name")"
plugin_digest="$(verify_checksum "$temporary_root/$plugin_name" "$temporary_root/$plugin_name.sha256" "$plugin_name")"
plugin_stage="$temporary_root/hosted-plugin"
extract_hosted_plugin "$temporary_root/$plugin_name" "$plugin_stage" "$version" "$idea_build"
control_root="$temporary_root/control"
extract_control "$temporary_root/$control_name" "$control_root"
[[ -x "$control_root/bin/kast" ]] || fail "control archive has no executable installer"

export KAST_INSTALL_CONTROL_ROOT="$control_root"
export KAST_INSTALL_CONTROL_ARCHIVE="$temporary_root/$control_name"
export KAST_INSTALL_CONTROL_SHA256="$control_digest"
export KAST_INSTALL_HOSTED_PLUGIN_ARCHIVE="$temporary_root/$plugin_name"
export KAST_INSTALL_HOSTED_PLUGIN_SHA256="$plugin_digest"
export KAST_INSTALL_VERSION="$version"
export KAST_INSTALL_IDEA_HOME="$idea_home"
export KAST_INSTALL_JAVA_HOME="$java_home"
export KAST_INSTALL_ROOT="$install_root"
export KAST_BIN_DIR="$bin_directory"
export KAST_ENABLE_LAUNCHD="${KAST_ENABLE_LAUNCHD:-0}"
export KAST_ENABLE_APP_SERVER="${KAST_ENABLE_APP_SERVER:-1}"
export KAST_INSTALL_REFRESH_APP_SERVER="${KAST_INSTALL_REFRESH_APP_SERVER:-0}"
export KAST_INSTALL_MODE="$mode"
export CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
export JAVA="$java_home/bin/java"
export JAVA_HOME="$java_home"

"$control_root/bin/kast" installation install
if [[ "$mode" == plan ]]; then
  note "verified hosted plugin $plugin_digest for IDEA $idea_build; installation planned at $idea_plugin_root/kast-ide-hosted"
else
  activate_hosted_plugin "$plugin_stage" "$idea_plugin_root"
  note "installed hosted plugin for IDEA $idea_build; restart IntelliJ IDEA to activate it"
fi
