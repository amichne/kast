#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

readonly PROGRAM="kast-install"
readonly REPOSITORY="amichne/kast"
readonly INSTALL_DOWNLOAD_RETRIES=5
readonly INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS=2000

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
  if supports_color; then printf '\033[%sm%s\033[0m' "$code" "$*"; else printf '%s' "$*"; fi
}

ui_glyph() {
  local kind="$1"
  if supports_unicode; then
    case "$kind" in step) printf '◆' ;; success) printf '✓' ;; warning) printf '!' ;; error) printf '×' ;; *) printf '›' ;; esac
  else
    case "$kind" in step) printf '*' ;; success) printf '+' ;; warning) printf '!' ;; error) printf 'x' ;; *) printf '>' ;; esac
  fi
}

ui_line() {
  local kind="$1" color="$2"
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
  printf '  %s\n\n' "$(colorize '2' 'Compiler-grounded Kotlin evidence for coding agents')" >&2
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
Install or remove Kast for the current user.

Usage:
  install.sh [--idea-home <absolute-path>] [--version <major.minor.patch> | --developer-latest] [--force] [--dry-run]
  install.sh uninstall [--dry-run]
  install.sh --help

The default command installs the latest release into:
  ${XDG_DATA_HOME:-$HOME/.local/share}/kast

and configures the persistent daemon without adding a Kast command to PATH.
When `--idea-home` is omitted, installation checks `/Applications`,
`~/Applications`, and the JetBrains Toolbox app directory for a compatible IDEA.

Pass arguments to a downloaded installer after Bash's `$0` separator:
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --help

`--dry-run` downloads and verifies the matched release, then prints the exact
installation plan without changing installation state.

`--developer-latest` installs the newest verified developer build from the
public developer channel. Its exact source revision is recorded in the channel
pointer and release SBOM. Developer builds are prereleases and change independently
of the latest stable release.

Development builds use packaging/install-checkout.sh session|persistent.

Installation enables the app server and its macOS login LaunchAgent by default.
`--force` retires the selected installation, resets its managed state and sockets,
and restages verified components. It does not change
source workspaces or the selected IDEA application. `--dry-run` remains read-only.
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
  [[ "$(cat "$physical/Resources/build.txt")" =~ ^(IU-|IC-)?262\. ]] || return 1
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

resolve_developer_latest() {
  local pointer tag selected_version source_revision extra
  pointer="$(curl --fail --location --silent --show-error --max-filesize 256 \
    --retry "$INSTALL_DOWNLOAD_RETRIES" --retry-delay "$((INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS / 1000))" \
    "https://raw.githubusercontent.com/$REPOSITORY/developer-latest/latest.txt")" ||
    fail "developer-latest pointer is unavailable"
  [[ "$pointer" != *$'\n'* ]] || fail "developer-latest pointer has multiple records"
  IFS=' ' read -r tag selected_version source_revision extra <<< "$pointer"
  [[ -z "${extra:-}" && "$selected_version" =~ ^0\.0\.[0-9]+$ &&
    "$tag" == "developer-v$selected_version" && "$source_revision" =~ ^[0-9a-f]{40}$ ]] ||
    fail "developer-latest pointer is invalid"
  printf '%s\t%s\t%s\n' "$tag" "$selected_version" "$source_revision"
}

fetch_asset() {
  local name="$1"
  local destination="$2"
  local unavailable="${3:-}"
  if [[ -n "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]]; then
    require_absolute_path "assets directory" "$KAST_INSTALL_ASSETS_DIRECTORY"
    if [[ ! -f "$KAST_INSTALL_ASSETS_DIRECTORY/$name" || -L "$KAST_INSTALL_ASSETS_DIRECTORY/$name" ]]; then
      [[ -z "$unavailable" ]] || fail "$unavailable"
      fail "local release asset is unavailable: $name"
    fi
    cp "$KAST_INSTALL_ASSETS_DIRECTORY/$name" "$destination"
  else
    if ! curl --fail --location --silent --show-error \
      --retry "$INSTALL_DOWNLOAD_RETRIES" --retry-delay "$((INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS / 1000))" \
      --output "$destination" "$release_url/$name"; then
      [[ -z "$unavailable" ]] || fail "$unavailable"
      fail "release asset is unavailable: $name"
    fi
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

MAXIMUM_CONTROL_ARCHIVE_ENTRIES = 16_384

archive, destination = Path(sys.argv[1]), Path(sys.argv[2])
destination.mkdir(mode=0o700)
with tarfile.open(archive, "r:gz") as source:
    members = []
    total_bytes = 0
    for member in source:
        if len(members) >= MAXIMUM_CONTROL_ARCHIVE_ENTRIES:
            raise SystemExit(f"kast-install: control archive entry count rejected (observedAtLeast={len(members) + 1}, maximum={MAXIMUM_CONTROL_ARCHIVE_ENTRIES})")
        total_bytes += member.size
        if total_bytes > 1073741824:
            raise SystemExit("kast-install: control archive byte count rejected (maximum=1073741824)")
        members.append(member)
    if not members:
        raise SystemExit("kast-install: control archive layout rejected")
    if len(members) > MAXIMUM_CONTROL_ARCHIVE_ENTRIES:
        raise SystemExit(
            "kast-install: control archive entry count rejected "
            f"(observed={len(members)}, maximum={MAXIMUM_CONTROL_ARCHIVE_ENTRIES})"
        )
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
version = value.get("version")
if not isinstance(build, str) or re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", build) is None:
    raise SystemExit("kast-install: IDEA build identity is invalid")
if not isinstance(directory, str) or re.fullmatch(r"[A-Za-z0-9._-]+", directory) is None:
    raise SystemExit("kast-install: IDEA data-directory identity is invalid")
if not isinstance(version, str) or not version.strip() or "\t" in version or "\n" in version:
    raise SystemExit("kast-install: IDEA version identity is invalid")
print(f"{version}\t{build}\t{directory}")
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
  local selected
  local -a options=()
  [[ "$force" == 0 ]] || options+=(--force)
  selected="$(cd "$install_root/current" && pwd -P)"
  python3 "$control_root/share/kast/installation-recovery.py" activate-plugin \
    --installation "$selected" --staged-plugin "$staged" --plugin-root "$plugin_root" ${options[@]+"${options[@]}"}
}

action=install
version="${KAST_VERSION:-}"
idea_home="${KAST_INSTALL_IDEA_HOME:-}"
mode=apply
force=0
developer_latest=0

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
    --force)
      [[ "$action" == install ]] || fail "--force is valid only for installation"
      force=1
      shift
      ;;
    --dry-run)
      mode=plan
      shift
      ;;
    --developer-latest)
      [[ "$action" == install ]] || fail "--developer-latest is valid only for installation"
      developer_latest=1
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

[[ "$developer_latest" == 0 || -z "$version" ]] || fail "--developer-latest cannot be combined with --version or KAST_VERSION"
[[ "$developer_latest" == 0 || -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]] ||
  fail "--developer-latest cannot be combined with local assets"

[[ -n "${HOME:-}" ]] || fail "HOME is unavailable"
# Local artifacts are an explicit developer entry point; public release installs use standard paths.
profile="${KAST_INSTALL_PROFILE:-persistent}"
case "$profile" in persistent|session) ;; *) fail 'KAST_INSTALL_PROFILE must be persistent or session' ;; esac
if [[ -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]]; then
  [[ "$profile" == persistent ]] || fail 'session installation requires local build artifacts; use packaging/install-checkout.sh'
  [[ -z "${KAST_INSTALL_ROOT+x}${KAST_BIN_DIR+x}" ]] || fail 'custom installation paths require the development installer'
fi
install_root="${KAST_INSTALL_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/kast}"
bin_directory="${KAST_BIN_DIR:-$HOME/.local/bin}"
for retired in KAST_INDEXER_MAX_HEAP KAST_WORKER_RESIDENT_LIMIT KAST_WORKER_STARTUP_LIMIT KAST_WORKER_AGGREGATE_MIB KAST_WORKER_NATIVE_MIB KAST_WORKER_GRADLE_MIB KAST_RUNTIME_ARCHIVE KAST_RUNTIME_STORE KAST_CACHE_ROOT KAST_ENABLE_APP_SERVER KAST_APP_SERVER_TOOLS KAST_ENABLE_LAUNCHD KAST_INSTALL_REFRESH_APP_SERVER KAST_INSTALL_REPLACE_COMMAND_COLLISIONS; do
  [[ -z "${!retired+x}" ]] || fail "$retired is retired; remove it from the environment before installing"
done
require_absolute_path "install root" "$install_root"
require_absolute_path "binary directory" "$bin_directory"

if [[ "$action" == uninstall ]]; then
  require_command python3
  selected="$install_root/current"
  [[ -d "$selected" ]] || fail "no selected Kast installation exists at $install_root"
  selected="$(CDPATH='' cd -- "$selected" && pwd -P)"
  lifecycle="$selected/share/kast/installation-lifecycle.py"
  [[ -f "$lifecycle" && ! -L "$lifecycle" ]] || fail "selected installation has no lifecycle control"
  if [[ "$mode" == plan ]]; then
    exec python3 "$lifecycle" --installation "$selected" remove --dry-run --json
  else
    registration="$selected/share/kast/codex-mcp-registration.py"
    if [[ -f "$registration" && ! -L "$registration" ]]; then
      unregister_copy="$(mktemp "${TMPDIR:-/tmp}/kast-mcp-unregister.XXXXXX")"
      cp "$registration" "$unregister_copy"
      trap 'rm -f -- "$unregister_copy"' EXIT
    fi
    python3 "$lifecycle" --installation "$selected" remove --json
    if [[ -n "${unregister_copy:-}" ]] && command -v codex >/dev/null 2>&1; then
      python3 "$unregister_copy" uninstall "$install_root"
    fi
    exit 0
  fi
fi

for command in curl shasum awk sed find python3 cp mktemp uname; do require_command "$command"; done
[[ "$(uname -s)" == Darwin ]] || fail "only macOS is supported"
case "$(uname -m)" in arm64|aarch64) ;; *) fail "only macOS on Apple silicon is supported" ;; esac

print_banner
note "checking this Mac and the selected IntelliJ installation"

if [[ -n "$idea_home" ]]; then
  require_absolute_path "IDEA home" "$idea_home"
  idea_home="$(canonical_idea_home "$idea_home" || true)"
  [[ -n "$idea_home" ]] || fail "IDEA home is incompatible"
else
  # Reuse the prior literal selector; never execute saved configuration as shell.
  selected_configuration="$install_root/current/config/environment"
  if [[ -f "$selected_configuration" ]]; then
    idea_home="$(python3 - "$selected_configuration" <<'PYTHON'
from pathlib import Path
import sys
lines = Path(sys.argv[1]).read_text().splitlines()
values = [line.partition("=")[2] for line in lines if line.startswith("KAST_INSTALL_IDEA_HOME=")]
if len(values) == 1:
    print(values[0])
PYTHON
)"
    idea_home="$(canonical_idea_home "$idea_home" || true)"
  fi
  [[ -n "$idea_home" ]] || idea_home="$(discover_idea_home)"
fi
java_home="$idea_home/jbr/Contents/Home"
IFS=$'\t' read -r idea_version idea_build idea_data_directory < <(read_idea_identity "$idea_home")
[[ -n "$idea_version" && -n "$idea_build" && -n "$idea_data_directory" ]] || fail "IDEA product identity is unavailable"
success "found IntelliJ IDEA $idea_version (build $idea_build)"
info "The IntelliJ plugin gives Kast compiler-grounded access to projects opened in this exact IDEA release line."
idea_plugin_root="$HOME/Library/Application Support/JetBrains/$idea_data_directory/plugins"

developer_source=""
release=""
if [[ "$developer_latest" == 1 ]]; then
  [[ -z "${KAST_RELEASE_BASE_URL:-}" ]] || fail "--developer-latest cannot override the public release URL"
  IFS=$'\t' read -r release version developer_source < <(resolve_developer_latest)
  validate_version "$version"
  info "selected developer build $version from $developer_source"
elif [[ -z "$version" || "$version" == latest ]]; then
  [[ -z "${KAST_RELEASE_BASE_URL:-}" ]] || fail "KAST_RELEASE_BASE_URL requires KAST_VERSION"
  [[ -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]] || fail "local assets require KAST_VERSION"
  version="$(resolve_latest_version)"
else
  validate_version "$version"
fi

release="${release:-v$version}"
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

note "downloading Kast $version and its IDEA ${idea_build%%.*} plugin"
for name in "$control_name" "$control_name.sha256"; do fetch_asset "$name" "$temporary_root/$name"; done
idea_mismatch="not installing Kast: release $version has no matching IDEA ${idea_build%%.*} plugin for IntelliJ IDEA $idea_version (build $idea_build)"
fetch_asset "$plugin_name" "$temporary_root/$plugin_name" "$idea_mismatch"
fetch_asset "$plugin_name.sha256" "$temporary_root/$plugin_name.sha256" "$idea_mismatch"
note "verifying downloaded checksums and plugin compatibility"
control_digest="$(verify_checksum "$temporary_root/$control_name" "$temporary_root/$control_name.sha256" "$control_name")"
plugin_digest="$(verify_checksum "$temporary_root/$plugin_name" "$temporary_root/$plugin_name.sha256" "$plugin_name")"
plugin_stage="$temporary_root/hosted-plugin"
extract_hosted_plugin "$temporary_root/$plugin_name" "$plugin_stage" "$version" "$idea_build"
control_root="$temporary_root/control"
extract_control "$temporary_root/$control_name" "$control_root"
[[ -x "$control_root/share/kast/libexec/kast-service" ]] || fail "control archive has no executable installer"
if [[ "$mode" == apply && "$profile" == persistent ]]; then
  require_command codex
  registration_source="$control_root/share/kast/codex-mcp-registration.py"
  [[ -f "$registration_source" ]] || fail "control archive has no Codex MCP registration helper"
  python3 "$registration_source" check "$install_root" || fail "Codex MCP name 'kast' is unavailable"
fi

info "The app server provides the complete Kast suite. Persistent installations start it at login."
note "$([[ "$mode" == plan ]] && printf 'planning' || printf 'installing') the app server and private service control"

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
export KAST_INSTALL_PROFILE="$profile"
export KAST_INSTALL_FORCE="$force"
export KAST_INSTALL_MODE="$mode"
export CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
export JAVA="$java_home/bin/java"
export JAVA_HOME="$java_home"

# Pass reset authority as a command option so older payloads reject it before effects.
installation_options=()
[[ "$force" == 0 ]] || installation_options+=(--force)
"$control_root/share/kast/libexec/kast-service" install ${installation_options[@]+"${installation_options[@]}"}
if [[ "$mode" == plan ]]; then
  success "verified hosted plugin $plugin_digest for IntelliJ IDEA $idea_version (build $idea_build)"
  info "Installation is planned at $install_root; the IDEA plugin is planned at $idea_plugin_root/kast-ide-hosted."
else
  activate_hosted_plugin "$plugin_stage" "$idea_plugin_root"
  if [[ "$profile" == persistent ]]; then
    [[ -x "$install_root/current/bin/kast-mcp-complete" ]] || fail "installed Kast MCP launcher is unavailable"
    python3 "$install_root/current/share/kast/codex-mcp-registration.py" install "$install_root"
  fi
  success "installed Kast $version and the plugin for IntelliJ IDEA $idea_version (build $idea_build)"
  info "Restart IntelliJ IDEA, then start a fresh Codex session in a Gradle repository."
fi
