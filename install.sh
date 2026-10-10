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

# Presentation only: child exit status and structured reports retain their authority.
# Public bootstrap also supports older verified payloads with the same command protocol.
run_installer_step() {
  local label="$1" report="$2"
  shift 2
  local output_root output status=0
  output_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-install-output.XXXXXX")" || return "$?"
  output="$output_root/stdout"
  [[ -z "$report" ]] || output="$report"
  if "$@" > "$output" 2> "$output_root/stderr"; then
    status=0
  else
    status=$?
  fi
  if [[ "$verbose" == 1 ]]; then
    cat "$output" || status=$?
    cat "$output_root/stderr" >&2 || status=$?
  else
    python3 - "$label" "$status" "$output" "$output_root/stderr" <<'PYTHON' >&2
import json
from pathlib import Path
import sys

label, code = sys.argv[1], int(sys.argv[2])

def readable(value):
    if not isinstance(value, (str, int)) or isinstance(value, bool):
        return ''
    return ''.join(character if character.isprintable() else ' ' for character in str(value))[:240]

def details(document):
    # Select display fields only; never interpret this projection as domain admission.
    result = []
    if document.get('type') in ('REJECTED', 'ROLLED_BACK', 'RECOVERY_REQUIRED'):
        result.append(document['type'])
    for key in ('stage', 'outcome', 'reason', 'failure', 'recoveryFailure', 'field', 'exitCode'):
        value = document.get(key)
        if isinstance(value, dict):
            value = ' '.join(filter(None, (readable(value.get('type')), readable(value.get('exitCode')))))
        text = readable(value)
        if text:
            result.append(text)
    for key in ('unresolved', 'blockers'):
        values = document.get(key)
        if isinstance(values, list):
            result.extend(filter(None, (readable(value) for value in values[:8])))
    return '; '.join(result)

if code:
    print(f'  x {label} failed (exit {code})')
shown = 0
for name in sys.argv[3:]:
    try:
        with Path(name).open('rb') as source:
            raw = source.read(65537)
    except OSError:
        print('  > Installer diagnostics could not be read; rerun with --verbose to inspect them.')
        continue
    decoded = raw[:65536].decode('utf-8', errors='replace')
    try:
        json.loads(decoded)
    except (ValueError, RecursionError):
        lines = decoded.splitlines()[:64]
    else:
        lines = [decoded]
    for line in lines:
        if shown >= 12:
            break
        stripped = line.lstrip()
        try:
            document = json.loads(line)
        except (ValueError, RecursionError):
            if stripped.startswith(('{', '[')):
                text = 'Structured diagnostics are unavailable; rerun with --verbose to inspect them.'
            else:
                text = readable(line)
        else:
            if not isinstance(document, dict):
                continue
            activation = document.get('activation')
            if isinstance(activation, dict) and activation.get('type') == 'pending':
                text = 'App server activation is pending: ' + details(activation)
                resume = readable(activation.get('resume'))
                if resume:
                    text += '; resume with ' + resume
            elif document.get('status') == 'retained' and isinstance(document.get('retained'), list):
                text = f"{len(document['retained'])} prior Kast entries retained for review"
            elif code:
                text = details(document)
            else:
                # Routine structured telemetry remains available through --verbose.
                continue
        if text:
            print('  > ' + text)
            shown += 1
    if len(raw) > 65536:
        print('  > Additional diagnostics are available with --verbose.')
PYTHON
  fi
  rm -rf -- "$output_root" || status=$?
  return "$status"
}

# Preserve the private path-only response while the existing presenter handles diagnostics.
run_management_completion() {
  local report="$1"
  shift
  "$@" > "$report"
}

usage() {
  cat <<'USAGE'
Bootstrap Kast for the current user.

Usage:
  install.sh [--control-only | --host-only] [--host-version <major.minor.patch>] [--idea-home <absolute-path>]
             [--version <major.minor.patch> | --developer-latest] [--force] [--dry-run]
             [--register-codex-mcp | --skip-codex-mcp] [--install-root <absolute-path>] [--stage-only] [--verbose]
  install.sh uninstall [--install-root <absolute-path>] [--dry-run] [--managed-registrations] [--verbose]
  install.sh --help

The default command selects a control release and a host release with an equal hosted contract.
Use --control-only to reuse a compatible running IntelliJ host, or --host-only to install only its plugin.
Installation uses the sole per-user directory:
  $HOME/.local/share/kast

It publishes the native management command at `$XDG_CONFIG_HOME/kast` when
`XDG_CONFIG_HOME` is nonempty, otherwise at `$HOME/.local/bin/kast`. If that
directory is not on PATH, installation succeeds and prints the absolute path
and directory to add. No shell profile is edited.
`--install-root` binds a private lifecycle call to that exact per-user directory; other roots are rejected.
`--stage-only` stages only control without starting its daemon or changing host files; the lifecycle owner activates it later.
When `--idea-home` is omitted, installation checks `/Applications`,
`~/Applications`, and the JetBrains Toolbox app directory for a compatible IDEA.

Pass arguments to a downloaded installer after Bash's `$0` separator:
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- --help

`--verbose` includes the full structured child reports and diagnostic output.
Default output shows progress, activation qualifications, and failure reasons.

`--dry-run` downloads and verifies the matched release, then prints the
installation plan without changing installation state.

`--developer-latest` installs the newest verified developer build from the
public developer channel. Its exact source revision is recorded in the channel
pointer and release SBOM. Developer builds are prereleases and change independently
of the latest stable release.

Development builds use packaging/install-checkout.sh persistent.

Installation enables the app server and its macOS login LaunchAgent by default.
Upgrades review historical Kast entries one at a time when current ownership
checks cannot admit them. Only an exact `yes` removes a reviewed entry; without
a terminal, uncertain entries are retained and reported.
Interactive paired installs ask whether to register a user-level Codex MCP
server. Pass --register-codex-mcp or --skip-codex-mcp to make the choice without
a prompt. Non-interactive paired installs default to registration for compatibility.
--register-codex-mcp requires a paired installation. --control-only, --host-only,
and --stage-only skip registration without prompting; explicit --skip-codex-mcp is accepted.
After installation, use kast status, kast connect, kast upgrade, or kast uninstall.
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
  local component="${1:-control}"
  curl --fail --location --silent --show-error --max-filesize 4194304 \
    --retry "$INSTALL_DOWNLOAD_RETRIES" --retry-delay "$((INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS / 1000))" \
    "https://api.github.com/repos/$REPOSITORY/releases?per_page=100" | python3 -c '
import json, re, sys
component = sys.argv[1]
for release in json.load(sys.stdin):
    match = re.fullmatch(component + r"-v([0-9]+\.[0-9]+\.[0-9]+)", release.get("tag_name", ""))
    if match and not release.get("draft") and not release.get("prerelease"):
        print(match.group(1)); break
else:
    sys.exit("kast-install: selected component has no observed stable release")
' "$component"
}

resolve_developer_latest() {
  local pointer tag selected_version source_revision extra
  pointer="$(curl --fail --location --silent --show-error --max-filesize 256 \
    --retry "$INSTALL_DOWNLOAD_RETRIES" --retry-delay "$((INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS / 1000))" \
    --header 'Accept: application/vnd.github.raw+json' --header 'Cache-Control: no-cache' \
    "https://api.github.com/repos/$REPOSITORY/contents/latest.txt?ref=developer-latest")" ||
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
  local transfer_display=(--silent)
  if [[ -n "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]]; then
    require_absolute_path "assets directory" "$KAST_INSTALL_ASSETS_DIRECTORY"
    if [[ ! -f "$KAST_INSTALL_ASSETS_DIRECTORY/$name" || -L "$KAST_INSTALL_ASSETS_DIRECTORY/$name" ]]; then
      [[ -z "$unavailable" ]] || fail "$unavailable"
      fail "local release asset is unavailable: $name"
    fi
    cp "$KAST_INSTALL_ASSETS_DIRECTORY/$name" "$destination"
  else
    if [[ -t 2 ]]; then transfer_display=(--progress-bar); fi
    if ! curl --fail --location "${transfer_display[@]}" --show-error \
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


action=install
managed_registrations=0
uninstall_journal=""
version="${KAST_VERSION:-}"
host_version="${KAST_HOST_VERSION:-}"
component=pair
control_only=0
idea_home="${KAST_INSTALL_IDEA_HOME:-}"
requested_install_root=""
mode=apply
force=0
stage_only=0
developer_latest=0
codex_mcp_choice=unspecified
verbose=0

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
    --stage-only)
      [[ "$action" == install ]] || fail "--stage-only is valid only for installation"
      stage_only=1
      shift
      ;;
    --control-only|--host-only)
      [[ "$action" == install && "$component" == pair ]] || fail "select one component installation"
      if [[ "$1" == --control-only ]]; then component=control; control_only=1; else component=host; fi
      shift
      ;;
    --host-version)
      [[ $# -ge 2 ]] || fail "--host-version requires a value"
      host_version="${2#v}"
      shift 2
      ;;
    --force)
      [[ "$action" == install ]] || fail "--force is valid only for installation"
      force=1
      shift
      ;;
    --uninstall-journal)
      [[ "$action" == uninstall && $# -ge 2 ]] || fail "--uninstall-journal requires removal and an exact path"
      uninstall_journal="$2"
      shift 2
      ;;
    --managed-registrations)
      [[ "$action" == uninstall ]] || fail "--managed-registrations is valid only for removal"
      managed_registrations=1
      shift
      ;;
    --dry-run)
      mode=plan
      shift
      ;;
    --verbose)
      verbose=1
      shift
      ;;
    --developer-latest)
      [[ "$action" == install ]] || fail "--developer-latest is valid only for installation"
      developer_latest=1
      shift
      ;;
    --register-codex-mcp|--skip-codex-mcp)
      [[ "$action" == install ]] || fail "$1 is valid only for installation"
      [[ "$codex_mcp_choice" == unspecified ]] || fail "choose only one Codex MCP registration option"
      if [[ "$1" == --register-codex-mcp ]]; then
        codex_mcp_choice=register
      else
        codex_mcp_choice=skip
      fi
      shift
      ;;
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      version="${2#v}"
      shift 2
      ;;
    --install-root)
      [[ $# -ge 2 ]] || fail "--install-root requires a value"
      requested_install_root="$2"
      [[ -n "$requested_install_root" ]] || fail "--install-root requires a nonempty path"
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

if [[ "$component" == host && -n "$host_version" ]]; then
  [[ -z "$version" || "$version" == "$host_version" ]] || fail "host version selections disagree"
  version="$host_version"
fi
[[ "$component" != control || -z "$host_version" ]] || fail "--control-only does not select a host release"
[[ "$control_only" == 0 || "$force" == 0 ]] || fail "--control-only cannot reset the installation"
[[ "$stage_only" == 0 || "$component" == pair ]] || fail "--stage-only cannot be combined with a component upgrade"
[[ "$developer_latest" == 0 || "$component" == pair ]] || fail "developer builds require an explicit tested pair"
[[ "$developer_latest" == 0 || -z "$version" ]] || fail "--developer-latest cannot be combined with --version or KAST_VERSION"
[[ "$developer_latest" == 0 || -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]] ||
  fail "--developer-latest cannot be combined with local assets"
if [[ "$stage_only" == 1 ]]; then
  # Cold reset staging owns only control; its lifecycle owner activates the candidate later.
  component=control
  host_version=""
fi
if [[ "$component" != pair ]]; then
  [[ "$codex_mcp_choice" != register ]] || fail "--register-codex-mcp requires a paired installation"
  [[ "$codex_mcp_choice" != unspecified ]] || codex_mcp_choice=skip
fi

[[ -z "$uninstall_journal" || ( "$managed_registrations" == 1 && "$mode" == apply ) ]] || fail "uninstall journal requires native managed removal"
[[ -n "${HOME:-}" ]] || fail "HOME is unavailable"
# Local artifacts are an explicit developer entry point; public release installs use standard paths.
profile="${KAST_INSTALL_PROFILE:-persistent}"
[[ "$profile" == persistent ]] || fail 'session installations are retired; use the sole persistent user installation'
install_root="$HOME/.local/share/kast"
[[ -z "$requested_install_root" || "$requested_install_root" == "$install_root" ]] || fail 'install root must be the sole per-user installation at $HOME/.local/share/kast'
[[ -z "${KAST_INSTALL_ROOT+x}" || "$KAST_INSTALL_ROOT" == "$install_root" ]] || fail 'KAST_INSTALL_ROOT must bind the sole per-user installation at $HOME/.local/share/kast'
bin_directory="$HOME/.local/bin"
[[ -z "${KAST_BIN_DIR+x}" || "$KAST_BIN_DIR" == "$bin_directory" ]] || fail 'KAST_BIN_DIR must bind the sole per-user binary directory at $HOME/.local/bin'
for retired in KAST_INDEXER_MAX_HEAP KAST_WORKER_RESIDENT_LIMIT KAST_WORKER_STARTUP_LIMIT KAST_WORKER_AGGREGATE_MIB KAST_WORKER_NATIVE_MIB KAST_WORKER_GRADLE_MIB KAST_RUNTIME_ARCHIVE KAST_RUNTIME_STORE KAST_CACHE_ROOT KAST_ENABLE_APP_SERVER KAST_APP_SERVER_TOOLS KAST_ENABLE_LAUNCHD KAST_INSTALL_REFRESH_APP_SERVER KAST_INSTALL_REPLACE_COMMAND_COLLISIONS; do
  [[ -z "${!retired+x}" ]] || fail "$retired is retired; remove it from the environment before installing"
done
require_absolute_path "install root" "$install_root"
require_absolute_path "binary directory" "$bin_directory"

if [[ "$action" == uninstall ]]; then
  require_command python3
  if [[ -e "$install_root" || -L "$install_root" ]]; then
    [[ -d "$install_root" && ! -L "$install_root" ]] || fail "install root ownership is unproven: $install_root"
    physical_install_root="$(CDPATH='' cd -- "$install_root" && pwd -P)"
    [[ "$physical_install_root" == "$install_root" ]] || fail "install root must be canonical: $install_root"
  else
    [[ "$mode" == apply && "$managed_registrations" == 0 ]] || fail "no Kast installation exists at $install_root"
  fi
  if [[ "$mode" == apply && "$managed_registrations" == 0 ]]; then
    # The native management owner retains cleanup authority after deleting its private payload.
    management_admission=0
    management_command="$(python3 - "$install_root" <<'PYTHON'
import hashlib, json, os, re, stat, sys
from pathlib import Path
root = Path(sys.argv[1])
try:
    receipt = root / 'management.json'
    if not os.path.lexists(receipt):
        token = hashlib.sha256(str(root).encode('utf-8')).hexdigest()
        receipt = root.parent / ('.kast-uninstall-' + token + '.json')
    attributes = receipt.lstat()
    if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid() or attributes.st_size > 65536:
        raise ValueError('receipt ownership')
    with receipt.open('rb') as source:
        raw = source.read(65537)
    if len(raw) > 65536:
        raise ValueError('receipt size')
    document = json.loads(raw.decode('utf-8'))
    expected = {'schemaVersion', 'installationRoot', 'executable', 'executableSha256', 'channel', 'registrations'}
    if receipt.name.startswith('.kast-uninstall-'):
        if stat.S_IMODE(attributes.st_mode) != 0o600:
            raise ValueError('retirement permissions')
        expected = {'type', 'installationRoot', 'executable', 'executableSha256', 'channel', 'managedRootIdentity', 'controlIdentity', 'recoveryProof', 'homeConfiguration'}
        if document.get('type') != 'EXTERNALS_CLEANED':
            raise ValueError('incomplete cleanup')
        if not isinstance(document.get('recoveryProof'), dict):
            raise ValueError('recovery proof')
        identity = document.get('managedRootIdentity')
        if (not isinstance(identity, dict) or set(identity) != {'device', 'inode', 'owner'}
                or any(type(value) is not int or value < 0 for value in identity.values())
                or identity['owner'] != os.getuid()):
            raise ValueError('root identity')
        if os.path.lexists(root):
            current = root.lstat()
            if (not stat.S_ISDIR(current.st_mode)
                    or (current.st_dev, current.st_ino, current.st_uid) != (identity['device'], identity['inode'], identity['owner'])):
                raise ValueError('changed root')
    elif document.get('schemaVersion') != 2 or not isinstance(document.get('registrations'), list):
        raise ValueError('receipt contract')
    if (set(document) != expected or document['installationRoot'] != str(root)
            or document['channel'] not in {'STABLE', 'DEVELOPER'}
            or not isinstance(document['executableSha256'], str)
            or re.fullmatch('[0-9a-f]{64}', document['executableSha256']) is None):
        raise ValueError('receipt contract')
    command = Path(document['executable'])
    if not command.is_absolute() or command.resolve() != command:
        raise ValueError('executable scope')
    if not os.path.lexists(command):
        raise SystemExit(20)
    attributes = command.lstat()
    if (not command.is_absolute() or command.resolve(strict=True) != command
            or not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid()
            or not os.access(command, os.X_OK)):
        raise ValueError('executable ownership')
    digest = hashlib.sha256()
    with command.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            digest.update(chunk)
    if digest.hexdigest() != document['executableSha256']:
        raise ValueError('executable identity')
    print(command)
except (OSError, ValueError, TypeError, KeyError):
    print('kast-install: receipted management executable ownership is unproven; use verified Kast recovery', file=sys.stderr)
    raise SystemExit(1)
PYTHON
    )" || management_admission=$?
    if [[ "$management_admission" == 20 ]]; then
      # Reuse the installer's admitted release archive when the old executable is already gone.
      for command in curl shasum awk cp mktemp; do require_command "$command"; done
      if [[ -z "$version" || "$version" == latest ]]; then
        [[ -z "${KAST_RELEASE_BASE_URL:-}" && -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]] ||
          fail "recovery assets require an exact KAST_VERSION"
        version="$(resolve_latest_version control)"
      fi
      validate_version "$version"
      release_url="${KAST_RELEASE_BASE_URL:-https://github.com/$REPOSITORY/releases/download}"
      release_url="${release_url%/}/control-v$version"
      recovery_directory="$(mktemp -d "${TMPDIR:-/tmp}/kast-uninstall-recovery.XXXXXX")"
      trap 'rm -rf -- "$recovery_directory"' EXIT
      recovery_name="kast-control-v$version-macos-aarch64.tar.gz"
      for name in "$recovery_name" "$recovery_name.sha256"; do fetch_asset "$name" "$recovery_directory/$name"; done
      verify_checksum "$recovery_directory/$recovery_name" "$recovery_directory/$recovery_name.sha256" "$recovery_name" >/dev/null
      extract_control "$recovery_directory/$recovery_name" "$recovery_directory/control"
      management_command="$recovery_directory/control/share/kast/libexec/kast-management"
      [[ -x "$management_command" && -f "$management_command" && ! -L "$management_command" ]] ||
        fail "admitted recovery archive has no native management executable"
    elif [[ "$management_admission" != 0 ]]; then
      fail "native removal admission failed; retained installation and cleanup state"
    fi
    run_installer_step "Owned installation removal" "" "$management_command" uninstall
    exit 0
  fi
  selected="$install_root/installation"
  [[ -d "$selected" && ! -L "$selected" ]] || fail "no Kast installation exists at $install_root"
  selected="$(CDPATH='' cd -- "$selected" && pwd -P)"
  lifecycle="$selected/share/kast/installation-lifecycle.py"
  [[ -f "$lifecycle" && ! -L "$lifecycle" ]] || fail "selected installation has no lifecycle control"
  if [[ "$mode" == plan ]]; then
    run_installer_step "Installation removal plan" "" python3 "$lifecycle" --installation "$selected" remove --control-only --dry-run --json
    success "planned removal of Kast at $selected; use --verbose for the structured plan"
    exit 0
  else
    registration="$selected/share/kast/codex-mcp-registration.py"
    if [[ "$managed_registrations" == 0 && -f "$registration" && ! -L "$registration" ]]; then
      unregister_copy="$(mktemp "${TMPDIR:-/tmp}/kast-mcp-unregister.XXXXXX")"
      cp "$registration" "$unregister_copy"
      trap 'rm -f -- "$unregister_copy"' EXIT
    fi
    removal_arguments=()
    [[ -z "$uninstall_journal" ]] || removal_arguments+=(--uninstall-journal "$uninstall_journal")
    run_installer_step "Installation removal" "" python3 "$lifecycle" --installation "$selected" remove --control-only --json ${removal_arguments[@]+"${removal_arguments[@]}"}
    if [[ -n "${unregister_copy:-}" ]] && command -v codex >/dev/null 2>&1; then
      python3 "$unregister_copy" uninstall "$install_root"
    fi
    success "removed the Kast installation"
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
  # Reuse the saved IDEA path; never execute saved configuration as shell.
  selected_configuration="$install_root/installation/config/environment"
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

if [[ "$codex_mcp_choice" == unspecified ]]; then
  codex_mcp_choice=register
  if [[ "$mode" == apply && -t 0 ]]; then
    while true; do
      printf '  Register a user-level Kast MCP server in Codex? [Y/n] ' >&2
      IFS= read -r answer || fail "Codex MCP registration choice was not provided"
      case "$answer" in
        ''|y|Y|yes|YES) break ;;
        n|N|no|NO) codex_mcp_choice=skip; break ;;
        *) warning "Enter yes or no." ;;
      esac
    done
  fi
fi

developer_source=""
release=""
if [[ "$developer_latest" == 1 ]]; then
  [[ -z "${KAST_RELEASE_BASE_URL:-}" ]] || fail "--developer-latest cannot override the public release URL"
  developer_selection="$(resolve_developer_latest)"
  IFS=$'\t' read -r release version developer_source <<< "$developer_selection"
  validate_version "$version"
  info "selected developer build $version from $developer_source"
elif [[ -z "$version" || "$version" == latest ]]; then
  [[ -z "${KAST_RELEASE_BASE_URL:-}" ]] || fail "KAST_RELEASE_BASE_URL requires KAST_VERSION"
  [[ -z "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]] || fail "local assets require KAST_VERSION"
  version="$(resolve_latest_version "$([[ "$component" == host ]] && printf host || printf control)")"
else
  validate_version "$version"
fi

release="${release:-$([[ "$component" == host ]] && printf host || printf control)-v$version}"
if [[ "$component" == host ]]; then host_version="$version"; fi
if [[ "$component" != control && -z "$host_version" ]]; then
  if [[ "$developer_latest" == 1 || -n "${KAST_INSTALL_ASSETS_DIRECTORY:-}" ]]; then host_version="$version"
  else host_version="$(resolve_latest_version host)"; fi
fi
if [[ "$component" != control ]]; then validate_version "$host_version"; fi
base_release_url="${KAST_RELEASE_BASE_URL:-https://github.com/$REPOSITORY/releases/download}"
release_url="${KAST_RELEASE_BASE_URL:-https://github.com/$REPOSITORY/releases/download}"
release_url="${release_url%/}/$release"
control_name="kast-control-v$version-macos-aarch64.tar.gz"
plugin_name="kast-ide-hosted-v${host_version:-unused}-idea-${idea_build%%.*}.zip"
temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
temporary_root="$(CDPATH='' cd -- "$temporary_root" && pwd -P)"
installation_complete=0
cleanup() {
  local status=$?
  local selected
  trap - EXIT
  if [[ "$status" == 0 && "$installation_complete" == 1 ]]; then
    if ! selected="$(CDPATH='' cd -- "$install_root/installation" && pwd -P)"; then
      ui_line error 31 'kast-install: upgraded installation is unavailable; replacement recovery was retained'
      status=1
    elif ! run_installer_step "Upgrade recovery finalization" "" python3 "$control_root/share/kast/installation-recovery.py" seal-upgrade \
      --installation "$selected"; then
      ui_line error 31 'kast-install: upgrade recovery could not be finalized; replacement recovery was retained'
      status=1
      if [[ "$control_only" == 1 ]]; then
        run_installer_step "Control recovery after finalization failure" "" "$control_root/share/kast/libexec/kast-service" recover-control finalization || true
      fi
    elif [[ "$component" != control ]] && ! run_installer_step "Prior installation review" "" python3 "$control_root/share/kast/prune-prior-installations.py" \
      --installation "$selected"; then
      ui_line error 31 'kast-install: prior installation review could not be completed'
      status=1
    fi
  fi
  if ! rm -rf -- "$temporary_root"; then status=1; fi
  if [[ "$status" == 0 && "$installation_complete" == 1 ]]; then
    if [[ "$stage_only" == 1 ]]; then
      success "staged Kast Control $version"
      info "The verified lifecycle owner will activate control."
    elif [[ "$component" == control ]]; then
      success "activated Kast Control $version with admitted running IntelliJ hosts"
      info "Start fresh control sessions. IntelliJ was reused."
    else
      success "installed Kast Control $version and Kast Host $host_version"
      info "Restart IntelliJ IDEA, then start a fresh agent session."
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "$component" != host ]]; then
  note "downloading Kast Control $version"
  for name in "$control_name" "$control_name.sha256"; do fetch_asset "$name" "$temporary_root/$name"; done
  control_digest="$(verify_checksum "$temporary_root/$control_name" "$temporary_root/$control_name.sha256" "$control_name")"
fi
if [[ "$component" != control ]]; then
  control_release_url="$release_url"
  if [[ "$developer_latest" == 0 ]]; then release_url="${base_release_url%/}/host-v$host_version"; fi
  note "downloading Kast Host $host_version for IDEA ${idea_build%%.*}"
  idea_mismatch="not installing Kast Host: release $host_version has no matching IDEA ${idea_build%%.*} plugin for IntelliJ IDEA $idea_version (build $idea_build)"
  fetch_asset "$plugin_name" "$temporary_root/$plugin_name" "$idea_mismatch"
  fetch_asset "$plugin_name.sha256" "$temporary_root/$plugin_name.sha256" "$idea_mismatch"
  plugin_digest="$(verify_checksum "$temporary_root/$plugin_name" "$temporary_root/$plugin_name.sha256" "$plugin_name")"
  host_record_name="kast-host-release-v$host_version.json"
  fetch_asset "$host_record_name" "$temporary_root/$host_record_name"
  fetch_asset "$host_record_name.sha256" "$temporary_root/$host_record_name.sha256"
  verify_checksum "$temporary_root/$host_record_name" "$temporary_root/$host_record_name.sha256" "$host_record_name" >/dev/null
  if [[ "$component" == host ]]; then
    fetch_asset host-installation.py "$temporary_root/host-installation.py"
    fetch_asset host-installation.py.sha256 "$temporary_root/host-installation.py.sha256"
    verify_checksum "$temporary_root/host-installation.py" "$temporary_root/host-installation.py.sha256" host-installation.py >/dev/null
    host_options=()
    [[ "$mode" == apply ]] || host_options+=(--dry-run)
    python3 "$temporary_root/host-installation.py" --archive "$temporary_root/$plugin_name" --sha256 "$plugin_digest" \
      --version "$host_version" --idea-build "$idea_build" --plugin-root "$idea_plugin_root" \
      --release-record "$temporary_root/$host_record_name" ${host_options[@]+"${host_options[@]}"}
    if [[ "$mode" == plan ]]; then success "planned Kast Host $host_version installation at $idea_plugin_root/kast-ide-hosted"
    else success "installed Kast Host $host_version; restart IntelliJ IDEA to load it"; fi
    exit 0
  fi
  release_url="$control_release_url"
fi
control_root="$temporary_root/control"
extract_control "$temporary_root/$control_name" "$control_root"
[[ -x "$control_root/share/kast/libexec/kast-service" ]] || fail "control archive has no executable installer"
installation_commit=commit
completion_capability="$control_root/share/kast/install-completion-v1"
if [[ "$stage_only" == 0 && ( -e "$completion_capability" || -L "$completion_capability" ) ]]; then
  [[ -f "$completion_capability" && ! -L "$completion_capability" ]] || fail "installation completion capability is invalid"
  [[ "$(wc -c < "$completion_capability")" =~ ^[[:space:]]*2[[:space:]]*$ ]] || fail "installation completion capability is incompatible"
  [[ "$(cat "$completion_capability")" == 1 ]] || fail "installation completion capability is incompatible"
  installation_commit=commit-active
fi
if [[ "$component" == pair ]]; then
  [[ -f "$control_root/share/kast/host-installation.py" ]] || fail "control convenience installer has no host installation helper"
  run_installer_step "IDEA host payload preflight" "" python3 "$control_root/share/kast/host-installation.py" \
    --archive "$temporary_root/$plugin_name" --sha256 "$plugin_digest" --version "$host_version" \
    --idea-build "$idea_build" --plugin-root "$idea_plugin_root" \
    --release-record "$temporary_root/$host_record_name" --required-control "$control_root/share/kast/ide-host.json" --dry-run
fi
management_executable="$control_root/share/kast/libexec/kast-management"
[[ -x "$management_executable" ]] || fail "control archive has no native management executable"
export KAST_INSTALL_ROOT="$install_root"
management_destination="$("$management_executable" --internal-install preflight)" ||
  fail "native executable destination was rejected before service retirement"
if [[ "$mode" == apply && "$codex_mcp_choice" == register ]]; then
  require_command codex
  registration_source="$control_root/share/kast/codex-mcp-registration.py"
  [[ -f "$registration_source" ]] || fail "control archive has no Codex MCP registration helper"
  python3 "$registration_source" check "$install_root" || fail "Codex MCP name 'kast' is unavailable"
fi

info "The app server provides the complete Kast suite. Persistent installations start it at login."
if [[ "$codex_mcp_choice" == skip ]]; then
  info "Codex MCP registration is skipped; use the app server or kast connect for your harness."
fi
note "$([[ "$mode" == plan ]] && printf 'planning' || printf 'installing') the app server and private service control"

export KAST_INSTALL_CONTROL_ROOT="$control_root"
export KAST_INSTALL_CONTROL_ARCHIVE="$temporary_root/$control_name"
export KAST_INSTALL_CONTROL_SHA256="$control_digest"
export KAST_INSTALL_CONTROL_ONLY="$control_only"
export KAST_INSTALL_VERSION="$version"
export KAST_INSTALL_IDEA_HOME="$idea_home"
export KAST_INSTALL_IDEA_PLUGIN_ROOT="$idea_plugin_root"
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
if [[ "$stage_only" == 1 ]]; then
  [[ -f "$control_root/share/kast/reset-fence-v1" && ! -L "$control_root/share/kast/reset-fence-v1" ]] ||
    fail 'this release cannot stage a fenced reset; upgrade the recovery executable and select a compatible release'
  [[ "$(cat "$control_root/share/kast/reset-fence-v1")" == 1 ]] || fail 'reset capability is incompatible'
  installation_options+=(--stage-only)
fi
if [[ -n "${KAST_MANAGEMENT_REPORT_PATH:-}" && "$mode" == apply ]]; then
  run_installer_step "App server and service installation" "$KAST_MANAGEMENT_REPORT_PATH" \
    "$control_root/share/kast/libexec/kast-service" install ${installation_options[@]+"${installation_options[@]}"}
else
  run_installer_step "App server and service installation" "" \
    "$control_root/share/kast/libexec/kast-service" install ${installation_options[@]+"${installation_options[@]}"}
fi
if [[ "$mode" == plan ]]; then
  [[ "$component" == control ]] || success "verified hosted plugin $plugin_digest for IntelliJ IDEA $idea_version (build $idea_build)"
  if [[ "$component" == control ]]; then info "Kast Control installation is planned at $install_root."
  else info "Installation is planned at $install_root; the IDEA plugin is planned at $idea_plugin_root/kast-ide-hosted."; fi
else
  if [[ "$component" != control ]]; then
    run_installer_step "IDEA host installation" "" python3 "$control_root/share/kast/host-installation.py" \
      --archive "$temporary_root/$plugin_name" --sha256 "$plugin_digest" --version "$host_version" \
      --idea-build "$idea_build" --plugin-root "$idea_plugin_root" \
      --release-record "$temporary_root/$host_record_name" --required-control "$control_root/share/kast/ide-host.json"
  fi
  export KAST_MANAGEMENT_CHANNEL="$([[ "$developer_latest" == 1 ]] && printf developer || printf stable)"
  completion_report="$temporary_root/management-completion"
  if ! run_installer_step "Native installation completion" "" run_management_completion "$completion_report" \
    "$management_executable" --internal-install "$installation_commit"; then
    if [[ "$control_only" == 1 ]]; then
      run_installer_step "Control recovery after publication failure" "" "$control_root/share/kast/libexec/kast-service" recover-control publication || true
    fi
    fail "native executable activation failed; inspect the reported control recovery outcome"
  fi
  installed_management="$(cat "$completion_report")"
  [[ "$installed_management" == "$management_destination" ]] ||
    fail "native executable destination changed during installation"
  if [[ "$codex_mcp_choice" == register ]]; then
    [[ -x "$install_root/installation/bin/kast-mcp-complete" ]] || fail "installed Kast MCP launcher is unavailable"
    "$installed_management" connect codex mcp
  fi
  installation_complete=1
fi
