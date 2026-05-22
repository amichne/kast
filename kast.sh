#!/usr/bin/env bash
# kast.sh - unified Kast shell tooling
#
# Subcommands:
#   build    Build portable distribution artifacts  ->  dist/
#   install  Install Kast CLI from GitHub releases
#
# Curl one-liner (auto-invokes install):
#   curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
#   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
#
# Explicit subcommand:
#   ./kast.sh build [plugin] [backend] [--all]
#   ./kast.sh install [--components=cli,intellij] [--non-interactive]
set -euo pipefail

# ---------------------------------------------------------------------------
# Script location -- graceful when curl-piped (BASH_SOURCE[0] may be /dev/stdin)
# ---------------------------------------------------------------------------

_SCRIPT_SRC="${BASH_SOURCE[0]:-}"
if [[ -n "$_SCRIPT_SRC" && -f "$_SCRIPT_SRC" ]]; then
  SCRIPT_DIR="$(cd -- "$(dirname -- "$_SCRIPT_SRC")" >/dev/null 2>&1 && pwd)"
else
  SCRIPT_DIR=""
fi

REPO_ROOT="$SCRIPT_DIR"
GRADLEW="${REPO_ROOT}/gradlew"
DIST_ROOT="${REPO_ROOT}/dist"

# Build paths (only meaningful when SCRIPT_DIR is set -- not applicable when curl-piped)
PLUGIN_DIST_DIR="${REPO_ROOT}/backend-intellij/build/distributions"
BACKEND_PORTABLE_DIST_DIR="${REPO_ROOT}/backend-standalone/build/portable-dist/backend-standalone"
BACKEND_PORTABLE_ZIP_DIR="${REPO_ROOT}/backend-standalone/build/distributions"

# Install constants
readonly DEFAULT_RELEASE_REPO="amichne/kast"
readonly DEFAULT_CLI_RELEASE_REPO="amichne/kast-rs"
readonly GITHUB_API_ACCEPT="Accept: application/vnd.github+json"
readonly GITHUB_API_VERSION="X-GitHub-Api-Version: 2022-11-28"
readonly PATH_MARKER="# Added by the Kast installer"
readonly COMPLETION_START_MARKER="# >>> Kast completion >>>"
readonly COMPLETION_END_MARKER="# <<< Kast completion <<<"
readonly KAST_CONFIG_ENV_START_MARKER="# >>> kast config >>>"
readonly KAST_CONFIG_ENV_END_MARKER="# <<< kast config <<<"
readonly KAST_ENV_SOURCE_START_MARKER="# >>> kast env >>>"
readonly KAST_ENV_SOURCE_END_MARKER="# <<< kast env <<<"

# Shared mutable temp dir -- used by both build and install; cleaned on EXIT
tmp_dir=""
declare -a _INSTALL_SHELL_PATCHES=()
declare -a _INSTALL_MANAGED_REPOS=()
declare -a _INSTALL_MIGRATION_SUMMARY=()

cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
}

trap cleanup EXIT

# ===========================================================================
# Logging and UI utilities
# ===========================================================================

supports_color() {
  if [[ "${CLICOLOR_FORCE:-}" == "1" ]]; then return 0; fi
  if [[ -n "${NO_COLOR:-}" ]]; then return 1; fi
  if [[ ! -t 2 ]]; then return 1; fi
  [[ "${TERM:-}" != "dumb" ]]
}

colorize() {
  local code="$1"; shift
  if supports_color; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
    return
  fi
  printf '%s' "$*"
}

log_line() { printf '%s %s\n' "$1" "$2" >&2; }
log()         { log_line "$(colorize '2' '|')" "$*"; }
log_section() { printf '\n%s\n' "$(colorize '1;36' "$*")" >&2; }
log_step()    { log_line "$(colorize '1;34' '>')" "$*"; }
log_success() { log_line "$(colorize '1;32' 'v')" "$*"; }
log_note()    { log_line "$(colorize '33' '*')" "$*"; }
log_prompt()  { printf '%s %s' "$(colorize '1;34' '?')" "$*" >/dev/tty; }

die() {
  log_line "$(colorize '1;31' 'x')" "$*"
  exit 1
}

can_prompt() { [[ -r /dev/tty && -w /dev/tty ]] && { : </dev/tty >/dev/tty; } 2>/dev/null; }

prompt_yes_no() {
  local message="$1"
  local default_answer="${2:-no}"
  local prompt_suffix="[y/N]"
  local reply=""

  [[ "$default_answer" == "yes" ]] && prompt_suffix="[Y/n]"

  while true; do
    log_prompt "${message} ${prompt_suffix} "
    if ! IFS= read -r reply </dev/tty; then
      printf '\n' >/dev/tty
      return 1
    fi
    printf '\n' >/dev/tty
    case "$reply" in
      "")                   [[ "$default_answer" == "yes" ]]; return ;;
      [Yy]|[Yy][Ee][Ss])   return 0 ;;
      [Nn]|[Nn][Oo])        return 1 ;;
    esac
  done
}

# ===========================================================================
# Shared utilities
# ===========================================================================

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
}

resolve_java_bin() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    local candidate="${JAVA_HOME}/bin/java"
    [[ -x "$candidate" ]] || die "JAVA_HOME is set but does not contain an executable java binary"
    printf '%s\n' "$candidate"
    return
  fi
  command -v java >/dev/null 2>&1 || die "Java 21 is required. Install Java 21 and rerun."
  command -v java
}

assert_java_21() {
  local java_bin="$1"
  local spec_version
  spec_version="$(
    "$java_bin" -XshowSettings:properties -version 2>&1 |
      awk -F'= ' '/java.specification.version =/ { print $2; exit }'
  )"
  [[ -n "$spec_version" ]] || die "Could not determine the installed Java version"
  local major_version="${spec_version%%.*}"
  if [[ "$major_version" -lt 21 ]]; then
    die "Kast requires Java 21 or newer. Found Java specification version $spec_version."
  fi
}

extract_zip_archive() {
  local archive_path="$1"
  local output_dir="$2"
  python3 - "$archive_path" "$output_dir" <<'PY'
import sys
import zipfile
from pathlib import Path

archive_path = Path(sys.argv[1])
output_dir = Path(sys.argv[2])
output_dir.mkdir(parents=True, exist_ok=True)

with zipfile.ZipFile(archive_path) as archive:
    resolved_output = output_dir.resolve()
    for member in archive.namelist():
        dest = (output_dir / member).resolve()
        if not str(dest).startswith(str(resolved_output) + "/"):
            raise Exception(f"Zip-slip attempt detected: {member}")
    archive.extractall(output_dir)
PY
}

compute_sha256() {
  local input_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$input_path" | awk '{ print $1 }'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$input_path" | awk '{ print $1 }'
    return
  fi
  die "Neither sha256sum nor shasum is available for checksum computation"
}

# ===========================================================================
# cmd_build -- local dev build / packaging
# ===========================================================================

_BUILD_ALL_TARGETS=(plugin backend)
_build_selected_targets=()

_build_verify_prerequisites() {
  [[ -n "$REPO_ROOT" && -d "$REPO_ROOT" ]] || die "Could not determine the repo root (run kast.sh from the repo)"
  [[ -x "$GRADLEW" ]] || die "Missing executable gradlew at ${GRADLEW}"
}

_build_ensure_healthy_daemon() {
  if ! "$GRADLEW" --status >/dev/null 2>&1; then
    log_step "Stopping stale Gradle daemons"
    "$GRADLEW" --stop >/dev/null 2>&1 || true
  fi
}

_build_select_targets_fzf() {
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && _build_selected_targets+=("$line")
  done < <(
    printf '%s\n' "${_BUILD_ALL_TARGETS[@]}" | fzf \
      --multi \
      --prompt="Select build targets: " \
      --header="<tab> toggle  <ctrl-a> select all  <enter> confirm" \
      --bind="ctrl-a:select-all" \
      --height="~50%" \
      --layout=reverse \
      --border=rounded
  )
  [[ "${#_build_selected_targets[@]}" -gt 0 ]] || die "No targets selected"
}

_build_select_targets_interactive() {
  if command -v fzf >/dev/null 2>&1 && can_prompt; then
    _build_select_targets_fzf
  else
    log_note "fzf not found or no TTY -- building all targets"
    _build_selected_targets=("${_BUILD_ALL_TARGETS[@]}")
  fi
}

_GRADLE_EXTRA_ARGS=()

_build_run_gradle_tasks() {
  ( cd "$REPO_ROOT"; "$GRADLEW" "${_GRADLE_EXTRA_ARGS[@]}" "$@" )
}

_build_run_gradle_tasks_with_retry() {
  if _build_run_gradle_tasks "$@"; then return 0; fi
  log_note "Gradle build failed; stopping daemon and retrying"
  "$GRADLEW" --stop >/dev/null 2>&1 || true
  _build_run_gradle_tasks "$@" --offline
}

_build_resolve_plugin_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PLUGIN_DIST_DIR}"/*.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a plugin zip under ${PLUGIN_DIST_DIR}"
  printf '%s\n' "$newest"
}

_build_resolve_backend_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${BACKEND_PORTABLE_ZIP_DIR}"/backend-standalone-*-portable.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a backend portable zip under ${BACKEND_PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

_build_plugin() {
  log_section "Building target: plugin"
  _build_run_gradle_tasks_with_retry buildIntellijPlugin

  local source_zip; source_zip="$(_build_resolve_plugin_zip)"
  local dist_zip="${DIST_ROOT}/plugin.zip"
  log_step "Publishing plugin zip into ${dist_zip}"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"
}

_build_backend() {
  log_section "Building target: backend"
  rm -rf "${REPO_ROOT}/backend-standalone/build/portable-dist" "${REPO_ROOT}/backend-standalone/build/distributions"
  _build_run_gradle_tasks_with_retry stageBackendDist buildBackendPortableZip

  log_step "Verifying staged backend tree in ${BACKEND_PORTABLE_DIST_DIR}"
  [[ -x "${BACKEND_PORTABLE_DIST_DIR}/kast-standalone" ]]             || die "Missing staged backend-standalone launcher"
  [[ -d "${BACKEND_PORTABLE_DIST_DIR}/runtime-libs" ]]                || die "Missing staged runtime-libs directory"
  [[ -f "${BACKEND_PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]]  || die "Missing staged runtime classpath file"
  local jars=()
  shopt -s nullglob; jars=("${BACKEND_PORTABLE_DIST_DIR}"/libs/backend-standalone-*-all.jar); shopt -u nullglob
  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${BACKEND_PORTABLE_DIST_DIR}/libs"

  local dist_dir="${DIST_ROOT}/backend"
  local dist_zip="${DIST_ROOT}/backend.zip"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing backend tree into ${dist_dir}"
  mkdir -p "$DIST_ROOT"
  cp -R "$BACKEND_PORTABLE_DIST_DIR" "${tmp_dir}/backend"
  rm -rf "$dist_dir"
  mv "${tmp_dir}/backend" "$dist_dir"
  log_success "Published ${dist_dir}"

  local source_zip; source_zip="$(_build_resolve_backend_zip)"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"

  rm -rf "$tmp_dir"; tmp_dir=""
}

_build_openapi() {
  log_section "Generating OpenAPI specification"
  _build_run_gradle_tasks_with_retry stageOpenApiSpec
  local dist_spec="${DIST_ROOT}/openapi.yaml"
  [[ -f "$dist_spec" ]] || die "Missing generated OpenAPI spec at ${dist_spec}"
  log_success "openapi -> ${dist_spec}"
}

_build_clean_stale_outputs() {
  local backend_dir="${DIST_ROOT}/backend"
  if [[ -d "$backend_dir" && (! -f "${backend_dir}/kast-standalone" || ! -d "${backend_dir}/runtime-libs") ]]; then
    log_step "Removing incomplete ${backend_dir} from a previous run"
    rm -rf "$backend_dir"
  fi

  shopt -s nullglob
  local stale
  for stale in "${TMPDIR:-/tmp}"/kast-build.??????; do
    [[ -d "$stale" ]] && rm -rf "$stale"
  done
  shopt -u nullglob
}

cmd_build() {
  _build_selected_targets=()
  _GRADLE_EXTRA_ARGS=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      plugin|backend)
        _build_selected_targets+=("$1"); shift ;;
      --all)
        _build_selected_targets=("${_BUILD_ALL_TARGETS[@]}"); shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh build [target...] [options]

Builds selected Kast components and publishes artifacts to dist/.

Targets (positional, repeatable):
  plugin       IDEA plugin zip           -> dist/plugin.zip
  backend      Standalone server         -> dist/backend/  dist/backend.zip

Options:
  --all            Build all targets.
  --help, -h       Show this help.

When no targets are supplied and a TTY is available, fzf is used for
interactive multi-selection. Falls back to building all targets when
fzf is not installed.
USAGE
        return 0
        ;;
      *)
        die "Unknown argument: $1" ;;
    esac
  done

  _build_verify_prerequisites
  _build_clean_stale_outputs

  if [[ "${#_build_selected_targets[@]}" -eq 0 ]]; then
    _build_select_targets_interactive
  fi

  log_section "Kast local build"
  _build_ensure_healthy_daemon

  for target in "${_build_selected_targets[@]}"; do
    case "$target" in
      plugin)  _build_plugin ;;
      backend) _build_backend ;;
    esac
  done

  _build_openapi

  log_section "Build complete"
  for target in "${_build_selected_targets[@]}"; do
    case "$target" in
      plugin)  log_success "plugin  ->  ${DIST_ROOT}/plugin.zip" ;;
      backend) log_success "backend ->  ${DIST_ROOT}/backend/  ${DIST_ROOT}/backend.zip" ;;
    esac
  done
}

# ===========================================================================
# cmd_install -- end-user installer
# ===========================================================================

_install_resolve_release_repo() {
  if [[ -n "${KAST_RELEASE_REPO:-}" ]]; then
    printf '%s\n' "$KAST_RELEASE_REPO"; return
  fi
  if [[ -z "$SCRIPT_DIR" ]] || ! command -v git >/dev/null 2>&1; then
    printf '%s\n' "$DEFAULT_RELEASE_REPO"; return
  fi
  local origin
  origin="$(git -C "$SCRIPT_DIR" config --get remote.origin.url 2>/dev/null || true)"
  if [[ "$origin" =~ ^git@github\.com:([^/]+)/([^.]+)(\.git)?$ ]]; then
    printf '%s/%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"; return
  fi
  if [[ "$origin" =~ ^https://github\.com/([^/]+)/([^.]+)(\.git)?$ ]]; then
    printf '%s/%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"; return
  fi
  printf '%s\n' "$DEFAULT_RELEASE_REPO"
}

_install_resolve_cli_release_repo() {
  if [[ -n "${KAST_CLI_RELEASE_REPO:-}" ]]; then
    printf '%s\n' "$KAST_CLI_RELEASE_REPO"; return
  fi
  printf '%s\n' "$DEFAULT_CLI_RELEASE_REPO"
}

_install_detect_platform_id() {
  local os_name arch_name
  os_name="$(uname -s)"; arch_name="$(uname -m)"
  case "$os_name:$arch_name" in
    Linux:x86_64)                printf '%s\n' "linux-x64" ;;
    Darwin:x86_64)               printf '%s\n' "macos-x64" ;;
    Darwin:arm64|Darwin:aarch64) printf '%s\n' "macos-arm64" ;;
    *) die "Unsupported platform: ${os_name} ${arch_name}" ;;
  esac
}

_install_download_file() {
  local url="$1" output_path="$2"
  local progress_mode="${KAST_DOWNLOAD_PROGRESS:-auto}"
  local -a progress_args=()

  case "$progress_mode" in
    auto|"")
      if [[ -t 2 ]]; then
        progress_args=(--progress-bar)
      else
        progress_args=(--silent --show-error)
      fi
      ;;
    always)
      progress_args=(--progress-bar)
      ;;
    never)
      progress_args=(--silent --show-error)
      ;;
    *)
      die "KAST_DOWNLOAD_PROGRESS must be auto, always, or never"
      ;;
  esac

  curl \
    --fail \
    --location \
    --retry 3 \
    --retry-delay 2 \
    "${progress_args[@]}" \
    --output "$output_path" \
    "$url"
}

_install_extract_release_metadata() {
  local metadata_path="$1" platform_id="$2"
  python3 - "$metadata_path" "$platform_id" <<'PY'
import json
import re
import sys
from pathlib import Path

metadata_path = Path(sys.argv[1])
platform_id = sys.argv[2]
if not metadata_path.is_file():
    raise SystemExit(f"Release metadata file was not found: {metadata_path}")
release = json.loads(metadata_path.read_text(encoding="utf-8"))
pattern = re.compile(rf"^kast-.*-{re.escape(platform_id)}\.zip$")

for asset in release.get("assets", []):
    name = asset.get("name", "")
    if pattern.match(name):
        print(release.get("tag_name", ""))
        print(name)
        print(asset.get("browser_download_url", ""))
        print(asset.get("digest", ""))
        break
else:
    asset_names = ", ".join(a.get("name", "<unnamed>") for a in release.get("assets", []))
    raise SystemExit(
        f"No release asset matched platform '{platform_id}'. "
        f"Available assets: {asset_names or '<none>'}"
    )
PY
}

_install_write_metadata() {
  local output_path="$1" release_repo="$2" release_tag="$3" \
        platform_id="$4" archive_name="$5" archive_source="$6" source="$7"
  python3 - "$output_path" "$release_repo" "$release_tag" \
            "$platform_id" "$archive_name" "$archive_source" "$source" <<'PY'
import json
import sys
from pathlib import Path

output_path = Path(sys.argv[1])
payload = {
    "releaseRepo":   sys.argv[2],
    "releaseTag":    sys.argv[3],
    "platformId":    sys.argv[4],
    "archiveName":   sys.argv[5],
    "archiveSource": sys.argv[6],
    "source":        sys.argv[7],
}
output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

_install_path_contains() {
  local target_dir="$1" path_entry
  IFS=':' read -r -a entries <<<"${PATH:-}"
  for path_entry in "${entries[@]}"; do
    [[ "$path_entry" == "$target_dir" ]] && return 0
  done
  return 1
}

_install_array_contains() {
  local needle="$1"
  shift || true
  local item
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done
  return 1
}

_install_record_shell_patch() {
  local rc_file="$1" marker="$2"
  local entry="${rc_file}|${marker}"
  _install_array_contains "$entry" "${_INSTALL_SHELL_PATCHES[@]:-}" || _INSTALL_SHELL_PATCHES+=("$entry")
}

_install_record_repo() {
  local repo_path="$1" version="$2"
  local entry="${repo_path}|${version}"
  _install_array_contains "$entry" "${_INSTALL_MANAGED_REPOS[@]:-}" || _INSTALL_MANAGED_REPOS+=("$entry")
}

_install_update_config_references() {
  local install_root="$1" bin_dir="$2"
  local config_file="$(_install_config_dir)/config.toml"
  [[ -f "$config_file" ]] || return 0
  python3 - "$config_file" "$install_root" "$bin_dir" <<'PYCONF'
import sys
from pathlib import Path

config_file = Path(sys.argv[1])
install_root = sys.argv[2]
bin_dir = sys.argv[3]
text = config_file.read_text(encoding="utf-8")
updated = (
    text
    .replace(".local/bin/kast", f"{bin_dir}/kast")
    .replace(".local/share/kast/instances", f"{install_root}/releases")
    .replace(".agents/skills/kast", f"{install_root}/lib/skills/kast")
)
if updated != text:
    config_file.write_text(updated, encoding="utf-8")
PYCONF
}

_install_migrate_legacy_layout() {
  local install_root="$1" bin_dir="$2"
  local legacy_bin="${HOME}/.local/bin/kast"
  local legacy_instances="${HOME}/.local/share/kast/instances"
  local legacy_skill="${HOME}/.agents/skills/kast"
  local migrated="false"

  mkdir -p "$install_root"

  if [[ -e "$legacy_instances" || -L "$legacy_instances" ]]; then
    local target_releases="${install_root}/releases"
    mkdir -p "$(dirname -- "$target_releases")"
    if [[ ! -e "$target_releases" && ! -L "$target_releases" ]]; then
      mv "$legacy_instances" "$target_releases"
      _INSTALL_MIGRATION_SUMMARY+=("Moved ${legacy_instances} -> ${target_releases}")
    else
      rm -rf "$legacy_instances"
      _INSTALL_MIGRATION_SUMMARY+=("Linked legacy instances path to ${target_releases}")
    fi
    mkdir -p "$(dirname -- "$legacy_instances")"
    ln -sfn "$target_releases" "$legacy_instances"
    migrated="true"
  fi

  if [[ -e "$legacy_skill" || -L "$legacy_skill" ]]; then
    local target_skill="${install_root}/lib/skills/kast"
    mkdir -p "$(dirname -- "$target_skill")"
    if [[ ! -e "$target_skill" && ! -L "$target_skill" ]]; then
      mv "$legacy_skill" "$target_skill"
      _INSTALL_MIGRATION_SUMMARY+=("Moved ${legacy_skill} -> ${target_skill}")
    else
      rm -rf "$legacy_skill"
      _INSTALL_MIGRATION_SUMMARY+=("Linked legacy skill path to ${target_skill}")
    fi
    mkdir -p "$(dirname -- "$legacy_skill")"
    ln -sfn "$target_skill" "$legacy_skill"
    migrated="true"
  fi

  if [[ -e "$legacy_bin" || -L "$legacy_bin" ]]; then
    local target_bin="${bin_dir}/kast"
    mkdir -p "$(dirname -- "$target_bin")"
    if [[ ! -e "$target_bin" && ! -L "$target_bin" ]]; then
      mv "$legacy_bin" "$target_bin"
      _INSTALL_MIGRATION_SUMMARY+=("Moved ${legacy_bin} -> ${target_bin}")
    else
      rm -f "$legacy_bin"
      _INSTALL_MIGRATION_SUMMARY+=("Linked legacy launcher path to ${target_bin}")
    fi
    mkdir -p "$(dirname -- "$legacy_bin")"
    ln -sfn "$target_bin" "$legacy_bin"
    migrated="true"
  fi

  _install_update_config_references "$install_root" "$bin_dir"

  if [[ "$migrated" == "true" ]]; then
    log_section "Legacy migration"
    local line
    for line in "${_INSTALL_MIGRATION_SUMMARY[@]:-}"; do
      log_success "$line"
    done
  fi
}

_install_write_manifest() {
  local install_root="$1" version="$2" platform_id="$3" backend_version="${4:-}"
  local manifest_file="${install_root}/.manifest.json"
  local shell_patches repo_entries
  shell_patches="$(printf '%s\n' "${_INSTALL_SHELL_PATCHES[@]:-}")"
  repo_entries="$(printf '%s\n' "${_INSTALL_MANAGED_REPOS[@]:-}")"
  INSTALL_SHELL_PATCHES="$shell_patches" INSTALL_REPOS="$repo_entries" python3 - "$manifest_file" "$install_root" "$version" "$platform_id" "$backend_version" <<'PYMANIFEST'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

manifest_file = Path(sys.argv[1])
install_root = Path(sys.argv[2])
version = sys.argv[3]
platform_id = sys.argv[4]
backend_version = sys.argv[5]
existing = {}
if manifest_file.exists():
    try:
        existing = json.loads(manifest_file.read_text(encoding="utf-8"))
    except Exception:
        existing = {}

def env_lines(name):
    return [line for line in os.environ.get(name, "").splitlines() if line]

components = set(existing.get("components", []))
managed_paths = set(existing.get("managedPaths", []))
if (install_root / "bin").exists():
    components.add("cli")
    managed_paths.add("bin")
if (install_root / "current").exists():
    components.add("cli")
    managed_paths.add("current")
if (install_root / "releases").exists():
    managed_paths.add("releases")
if (install_root / "backends").exists():
    components.add("backend")
    managed_paths.add("backends")
if (install_root / "plugins").exists():
    managed_paths.add("plugins")
    if any((install_root / "plugins").iterdir()):
        components.add("intellij")
if (install_root / "lib" / "skills" / "kast").exists():
    components.add("skill")
    managed_paths.add("lib/skills/kast")

shell_patches = {
    (entry.get("file"), entry.get("marker")): entry
    for entry in existing.get("shellRcPatches", [])
    if isinstance(entry, dict) and entry.get("file") and entry.get("marker")
}
for line in env_lines("INSTALL_SHELL_PATCHES"):
    file_path, marker = line.split("|", 1)
    shell_patches[(file_path, marker)] = {"file": file_path, "marker": marker}

repos = {
    entry.get("path"): entry
    for entry in existing.get("repos", [])
    if isinstance(entry, dict) and entry.get("path")
}
for line in env_lines("INSTALL_REPOS"):
    repo_path, repo_version = line.split("|", 1)
    repos[repo_path] = {"path": repo_path, "copilotExtensionVersion": repo_version}

manifest = {
    "version": version or existing.get("version", ""),
    "backendVersion": backend_version or existing.get("backendVersion", ""),
    "installedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "platform": platform_id or existing.get("platform", ""),
    "components": sorted(components),
    "managedPaths": sorted(managed_paths),
    "shellRcPatches": sorted(shell_patches.values(), key=lambda item: (item["file"], item["marker"])),
    "repos": sorted(repos.values(), key=lambda item: item["path"]),
    "schemaVersion": existing.get("schemaVersion", 3),
}
manifest_file.parent.mkdir(parents=True, exist_ok=True)
manifest_file.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PYMANIFEST
  log_success "Wrote install manifest to ${manifest_file}"
}

_install_resolve_shell_rc_file() {
  if [[ -n "${KAST_PATH_RC_FILE:-}" ]]; then
    printf '%s\n' "$KAST_PATH_RC_FILE"; return
  fi
  local shell_name="${SHELL##*/}"
  case "$shell_name" in
    zsh)  printf '%s\n' "${HOME}/.zshrc" ;;
    bash) [[ -f "${HOME}/.bashrc" ]] && printf '%s\n' "${HOME}/.bashrc" || printf '%s\n' "${HOME}/.bash_profile" ;;
    *)    printf '%s\n' "" ;;
  esac
}

_install_resolve_shell_name() {
  local shell_name="${SHELL##*/}"
  case "$shell_name" in
    bash|zsh) printf '%s\n' "$shell_name" ;;
    *)        printf '%s\n' "" ;;
  esac
}

_install_write_path_block() {
  local rc_file="$1" bin_dir="$2"
  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"

  if ! grep -Fq "$PATH_MARKER" "$rc_file"; then
    {
      printf '\n%s\n' "$PATH_MARKER"
      printf 'export PATH="%s:$PATH"\n' "$bin_dir"
    } >> "$rc_file"
    log_success "Added ${bin_dir} to PATH in ${rc_file}"
  else
    log_step "PATH already includes the Kast installer block in ${rc_file}"
  fi
  _install_record_shell_patch "$rc_file" "$PATH_MARKER"
}

_install_ensure_bin_dir_on_path() {
  local bin_dir="$1"
  _install_path_contains "$bin_dir" && return

  if [[ "${KAST_SKIP_PATH_UPDATE:-false}" == "true" ]]; then
    log_note "Add ${bin_dir} to PATH before running kast."
    return
  fi

  local rc_file; rc_file="$(_install_resolve_shell_rc_file)"
  if [[ -z "$rc_file" ]]; then
    log_note "Add ${bin_dir} to PATH before running kast."
    return
  fi

  _install_write_path_block "$rc_file" "$bin_dir"

  local shell_name; shell_name="$(_install_resolve_shell_name)"
  if [[ "$shell_name" == "bash" && "${rc_file##*/}" == ".bashrc" ]]; then
    local login_rc="${HOME}/.bash_profile"
    [[ -f "$login_rc" ]] || login_rc="${HOME}/.profile"
    _install_write_path_block "$login_rc" "$bin_dir"
  fi
}

_install_resolve_completion_mode() {
  case "${KAST_INSTALL_COMPLETIONS:-prompt}" in
    ""|prompt|auto) printf '%s\n' "prompt" ;;
    true|yes|1)     printf '%s\n' "enable" ;;
    false|no|0)     printf '%s\n' "disable" ;;
    *) die "KAST_INSTALL_COMPLETIONS must be one of: prompt, true, false" ;;
  esac
}

_install_shell_completion() {
  local release_dir="$1" install_root="$2" shell_name="$3"

  if [[ -z "$shell_name" ]]; then
    log_note "Shell completion setup is available for Bash and Zsh. Run 'kast help completion' for manual instructions."
    return
  fi

  local completion_dir="${release_dir}/completions"
  local completion_file="${completion_dir}/kast.${shell_name}"
  local completion_stderr="${tmp_dir}/completion-${shell_name}.stderr"
  local rc_file; rc_file="$(_install_resolve_shell_rc_file)"

  mkdir -p "$completion_dir"
  if ! "${release_dir}/kast" completion "$shell_name" >"$completion_file" 2>"$completion_stderr"; then
    rm -f "$completion_file" "$completion_stderr"
    log_note "This Kast build does not expose 'completion ${shell_name}' yet; skipping completion setup."
    return
  fi
  rm -f "$completion_stderr"
  log_success "Generated ${shell_name} completion script at ${completion_file}"

  if [[ -z "$rc_file" ]]; then
    log_note "Open a shell init file and source ${install_root}/current/completions/kast.${shell_name} to enable completions."
    return
  fi

  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"

  if grep -Fq "$COMPLETION_START_MARKER" "$rc_file"; then
    _install_record_shell_patch "$rc_file" "$COMPLETION_START_MARKER"
    log_step "Shell completion is already configured in ${rc_file}"
    return
  fi

  local completion_mode; completion_mode="$(_install_resolve_completion_mode)"

  if [[ "$completion_mode" == "disable" ]]; then
    log_note "Skipped shell completion setup. Enable later: source ${install_root}/current/completions/kast.${shell_name}"
    return
  fi

  if [[ "$completion_mode" == "prompt" ]]; then
    if ! can_prompt; then
      log_note "Skipped interactive completion setup because no terminal prompt is available."
      log_note "To enable it later, source ${install_root}/current/completions/kast.${shell_name} from ${rc_file}."
      return
    fi
    if ! prompt_yes_no "Enable ${shell_name} completions in ${rc_file}?" "yes"; then
      log_note "Skipped shell completion setup. Enable later: source ${install_root}/current/completions/kast.${shell_name}"
      return
    fi
  fi

  {
    printf '\n%s\n' "$COMPLETION_START_MARKER"
    printf 'if [[ -r "%s/current/completions/kast.%s" ]]; then\n' "$install_root" "$shell_name"
    printf '  source "%s/current/completions/kast.%s"\n' "$install_root" "$shell_name"
    printf 'fi\n'
    printf '%s\n' "$COMPLETION_END_MARKER"
  } >> "$rc_file"
  _install_record_shell_patch "$rc_file" "$COMPLETION_START_MARKER"
  log_success "Enabled ${shell_name} completions in ${rc_file}"
}

_install_intellij_plugin() {
  local release_repo="$1" release_tag="$2" install_root="$3" local_archive="${4:-}"

  log_section "Install IDEA / Android Studio plugin"
  local plugin_dir="${install_root}/plugins"
  local plugin_name="kast-intellij-${release_tag}.zip"
  local plugin_path="${plugin_dir}/${plugin_name}"
  mkdir -p "$plugin_dir"

  if [[ -n "$local_archive" ]]; then
    log_step "Copying local plugin archive ${local_archive}"
    cp "$local_archive" "$plugin_path"
  elif [[ "$release_tag" == "local" ]]; then
    log_note "Local install: no plugin archive available; skipping IDEA plugin"
    return 1
  else
    local plugin_url="https://github.com/${release_repo}/releases/download/${release_tag}/${plugin_name}"
    log_step "Downloading IDEA plugin ${plugin_name}"
    local download_attempt
    for download_attempt in 1 2 3; do
      if _install_download_file "$plugin_url" "$plugin_path"; then break; fi
      if [[ "$download_attempt" -eq 3 ]]; then
        log_note "Failed to download IDEA plugin after 3 attempts; skipping"
        return 1
      fi
      log_note "Download attempt ${download_attempt} failed; retrying in 5 seconds"
      sleep 5
    done
  fi

  log_success "IDEA plugin zip saved to ${plugin_path}"
  log_note "Install from IDEA / Android Studio: Settings -> Plugins -> gear icon -> Install Plugin from Disk"
  log_note "Select: ${plugin_path}"
  return 0
}

_install_standalone_backend() {
  local release_repo="$1" release_tag="$2" install_root="$3" local_archive="${4:-}" bin_dir="${5:-${HOME}/.kast/bin}" archive_digest="${6:-}"

  log_section "Install standalone backend"
  local backend_dir="${install_root}/backends"
  local backend_name="kast-standalone-${release_tag}.zip"
  local backend_release_dir="${backend_dir}/standalone-${release_tag}"
  local backend_path="${tmp_dir}/${backend_name}"
  mkdir -p "$backend_dir"

  if [[ -n "$local_archive" ]]; then
    log_step "Copying local backend archive ${local_archive}"
    cp "$local_archive" "$backend_path"
  else
    local backend_url="https://github.com/${release_repo}/releases/download/${release_tag}/${backend_name}"
    log_step "Downloading standalone backend ${backend_name}"
    local download_attempt
    for download_attempt in 1 2 3; do
      if _install_download_file "$backend_url" "$backend_path"; then break; fi
      if [[ "$download_attempt" -eq 3 ]]; then
        log_note "Failed to download standalone backend after 3 attempts; skipping"
        return 1
      fi
      log_note "Download attempt ${download_attempt} failed; retrying in 5 seconds"
      sleep 5
    done
  fi

  if [[ -n "$archive_digest" ]]; then
    local expected_sha256="${archive_digest#sha256:}"
    local actual_sha256; actual_sha256="$(compute_sha256 "$backend_path")"
    [[ "$actual_sha256" == "$expected_sha256" ]] || die "Checksum verification failed for ${backend_name}"
    log_success "Verified SHA-256 for ${backend_name}"
  fi

  local staging_dir="${tmp_dir}/backend-extract"
  extract_zip_archive "$backend_path" "$staging_dir"

  if [[ -d "${staging_dir}/backend-standalone" ]]; then
    mv "${staging_dir}/backend-standalone" "${staging_dir}/kast-standalone-extracted"
  fi
  [[ -d "${staging_dir}/kast-standalone-extracted" ]] || die "Backend archive did not contain expected backend-standalone/ directory"

  rm -rf "$backend_release_dir"
  mkdir -p "$(dirname -- "$backend_release_dir")"
  mv "${staging_dir}/kast-standalone-extracted" "$backend_release_dir"

  local current_link="${backend_dir}/current"
  ln -sfn "$backend_release_dir" "$current_link"

  log_success "Standalone backend installed to ${backend_release_dir}"
  log_note "Start with: kast up --workspace-root=/absolute/path/to/workspace"
  return 0
}

_install_resolve_release_tag() {
  local release_repo="$1" known_tag="${2:-}"
  if [[ -n "$known_tag" ]]; then
    printf '%s\n' "$known_tag"
    return
  fi
  local version="${KAST_VERSION:-}"
  if [[ -n "$version" ]]; then
    printf '%s\n' "$version"
    return
  fi
  local meta_path="${tmp_dir}/latest-release-tag.json"
  curl \
    --fail --location --retry 3 --retry-delay 2 --silent --show-error \
    --header "$GITHUB_API_ACCEPT" \
    --header "$GITHUB_API_VERSION" \
    --output "$meta_path" \
    "https://api.github.com/repos/${release_repo}/releases/latest"
  python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['tag_name'])" "$meta_path"
}

_install_prompt_components() {
  if ! can_prompt; then
    printf '%s\n' "cli"; return
  fi
  log_prompt "Which components? [cli/intellij/backend/all] (cli) "
  local reply=""
  if ! IFS= read -r reply </dev/tty; then
    printf '\n' >/dev/tty
    printf '%s\n' "cli"
    return
  fi
  printf '\n' >/dev/tty
  case "${reply,,}" in
    ""|cli)    printf '%s\n' "cli" ;;
    intellij)  printf '%s\n' "intellij" ;;
    backend)   printf '%s\n' "backend" ;;
    all)       printf '%s\n' "cli,intellij,backend" ;;
    *)         printf '%s\n' "$reply" ;;
  esac
}

# ---------------------------------------------------------------------------
# Install wizard helpers
# ---------------------------------------------------------------------------

# Global wizard state (set by _install_detect_env and _install_mode_select)
_INSTALL_ENV_HAS_JAVA="false"
_INSTALL_ENV_HAS_FZF="false"
_INSTALL_ENV_EXISTING_VERSION=""
_INSTALL_MODE="minimal"
_INSTALL_INTELLIJ_ACTION="skip"
declare -a _INSTALL_ENV_INTELLIJ_PIDS=()
declare -a _INSTALL_ENV_INTELLIJ_APPS=()
declare -a _INSTALL_ENV_INTELLIJ_LABELS=()

_install_banner() {
  printf '\n' >&2
  printf '  %s\n' "$(colorize '1;36' '  ██╗  ██╗ █████╗ ███████╗████████╗')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ██║ ██╔╝██╔══██╗██╔════╝╚══██╔══╝')" >&2
  printf '  %s\n' "$(colorize '1;36' '  █████╔╝ ███████║███████╗   ██║   ')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ██╔═██╗ ██╔══██║╚════██║   ██║   ')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ██║  ██╗██║  ██║███████║   ██║   ')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝   ╚═╝  ')" >&2
  printf '\n' >&2
  printf '  %s\n' "Kotlin semantic analysis — from your terminal" >&2
  printf '  %s\n' "$(colorize '2' 'https://github.com/amichne/kast')" >&2
  printf '\n' >&2
}

_install_choice_line() {
  local label="$1" description="$2" accent="${3:-1;36}" badge="${4:-}"

  printf '  %s %s' "$(colorize "$accent" '→')" "$(colorize '1' "$label")" >&2
  if [[ -n "$badge" ]]; then
    printf ' %s' "$(colorize '1;32' "[$badge]")" >&2
  fi
  printf '\n' >&2
  printf '    %s\n' "$description" >&2
}

_install_detect_env() {
  command -v java >/dev/null 2>&1 && _INSTALL_ENV_HAS_JAVA="true"
  command -v fzf  >/dev/null 2>&1 && _INSTALL_ENV_HAS_FZF="true"

  local existing_kast; existing_kast="$(command -v kast 2>/dev/null || true)"
  if [[ -n "$existing_kast" ]]; then
    _INSTALL_ENV_EXISTING_VERSION="$("$existing_kast" --version 2>/dev/null | head -1 || echo "unknown")"
  fi

  # Running IDEA-compatible IDE instances (macOS only)
  [[ "$(uname -s)" != "Darwin" ]] && return
  local pid app_path
  while IFS=' ' read -r pid app_path; do
    [[ -z "$pid" || -z "$app_path" ]] && continue
    local label="PID ${pid}  $(_install_intellij_product_label "$app_path")"
    _INSTALL_ENV_INTELLIJ_PIDS+=("$pid")
    _INSTALL_ENV_INTELLIJ_APPS+=("$app_path")
    _INSTALL_ENV_INTELLIJ_LABELS+=("$label")
  done < <(
    ps aux \
      | grep -E '/Contents/MacOS/(idea|studio)$' \
      | grep -v grep \
      | awk '{
          pid = $2
          if (match($0, /\/.*\.app\/Contents\/MacOS\/(idea|studio)$/)) {
            app = substr($0, RSTART, RLENGTH)
            sub(/\/Contents\/MacOS\/(idea|studio)$/, "", app)
            print pid " " app
          }
        }'
  )
}

_install_intellij_product_label() {
  local app_path="$1"
  local pinfo="${app_path}/Contents/Resources/product-info.json"
  [[ -f "$pinfo" ]] || { printf '%s' "$(basename "$app_path")"; return; }
  python3 - "$pinfo" <<'END'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"{d['name']} {d['version']}  [{d['dataDirectoryName']}]")
END
}

_install_intellij_plugins_dir() {
  local pid="$1" app_path="$2"
  local jvm_args config_path=""
  jvm_args="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  config_path="$(printf '%s' "$jvm_args" \
    | grep -oE '\-Didea\.config\.path=[^ ]+' \
    | cut -d= -f2- | head -1)"
  if [[ -n "$config_path" ]]; then
    printf '%s/plugins' "$config_path"
    return
  fi
  local pinfo="${app_path}/Contents/Resources/product-info.json"
  [[ -f "$pinfo" ]] || { log_note "Cannot find product-info.json in ${app_path}"; return 1; }
  local data_dir_name
  data_dir_name="$(python3 - "$pinfo" <<'EOF'
import json, sys; print(json.load(open(sys.argv[1]))["dataDirectoryName"])
EOF
)"
  printf '%s/Library/Application Support/JetBrains/%s/plugins' "$HOME" "$data_dir_name"
}

# _fzf_select <prompt> item1 item2 ...
# Prints the selected item. Falls back to a numbered menu when fzf is unavailable.
_fzf_select() {
  local prompt="$1"; shift
  local -a items=("$@")
  local count="${#items[@]}"
  [[ "$count" -eq 0 ]] && return 1
  if [[ "$count" -eq 1 ]]; then printf '%s' "${items[0]}"; return 0; fi

  if [[ "$_INSTALL_ENV_HAS_FZF" == "true" ]] && can_prompt; then
    local selection
    selection="$(printf '%s\n' "${items[@]}" \
      | fzf --prompt="→ ${prompt}: " \
            --height="~40%" \
            --layout=reverse \
            --border=rounded \
            --pointer="→" \
            --header="enter = select · ctrl-c = cancel" \
            --color="prompt:blue,pointer:green,info:blue,header:yellow,hl:cyan,hl+:cyan,border:blue" \
            --no-multi)"
    printf '%s' "$selection"
    return 0
  fi

  local i
  for i in "${!items[@]}"; do
    printf '  [%d] %s\n' "$((i + 1))" "${items[$i]}" >/dev/tty
  done
  printf '\n' >/dev/tty
  local choice
  while true; do
    log_prompt "${prompt} (1-${count}): "
    IFS= read -r choice </dev/tty
    printf '\n' >/dev/tty
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= count )); then
      printf '%s' "${items[$((choice - 1))]}"; return 0
    fi
    log_note "Enter a number between 1 and ${count}"
  done
}

# Sets _INSTALL_MODE ("minimal"|"full") and _INSTALL_INTELLIJ_ACTION ("push"|"zip"|"skip")
_install_mode_select() {
  local non_interactive="${1:-false}"
  local intellij_count="${#_INSTALL_ENV_INTELLIJ_PIDS[@]}"

  if [[ "$non_interactive" == "true" ]] || ! can_prompt; then
    _INSTALL_MODE="minimal"
    _INSTALL_INTELLIJ_ACTION="skip"
    return
  fi

  log_section "Choose install mode"
  if [[ "$intellij_count" -gt 0 ]]; then
    log_step "Detected ${intellij_count} running IDEA-compatible IDE instance(s)"
    printf '\n' >&2
    _install_choice_line "minimal" "CLI + IDEA plugin (recommended — IDE detected)" "1;32" "recommended"
    _install_choice_line "full" "CLI + standalone JVM backend (no IDE dependency)" "1;36"
  else
    _install_choice_line "minimal" "CLI only (lightweight; add plugin or backend separately)" "1;32" "quick start"
    _install_choice_line "full" "CLI + standalone JVM backend (includes analysis engine)" "1;36"
  fi
  printf '\n' >&2

  local mode_choice; mode_choice="$(_fzf_select "Install mode" "minimal" "full")"
  _INSTALL_MODE="${mode_choice:-minimal}"

  if [[ "$_INSTALL_MODE" == "minimal" ]] && [[ "$intellij_count" -gt 0 ]]; then
    printf '\n' >&2
    log_step "How would you like to install the IDEA plugin?"
    printf '\n' >&2
    _install_choice_line "push" "Push directly to a running IDE instance (restart the IDE after)" "1;32" "recommended"
    _install_choice_line "zip" "Download zip for manual install from disk" "1;36"
    _install_choice_line "skip" "Skip plugin install" "33"
    printf '\n' >&2
    local action_choice; action_choice="$(_fzf_select "Plugin action" "push" "zip" "skip")"
    _INSTALL_INTELLIJ_ACTION="${action_choice:-push}"
  elif [[ "$_INSTALL_MODE" == "minimal" ]]; then
    printf '\n' >&2
    if prompt_yes_no "Download IDEA plugin zip?" "yes"; then
      _INSTALL_INTELLIJ_ACTION="zip"
    else
      _INSTALL_INTELLIJ_ACTION="skip"
    fi
  else
    _INSTALL_INTELLIJ_ACTION="skip"
  fi
}

_install_config_write() {
  local install_root="$1" bin_dir="$2" runtime_libs="${3:-}"
  local config_dir; config_dir="$(_install_config_dir)"
  local config_file="${config_dir}/env"
  mkdir -p "$config_dir"

  local in_block="false"
  if [[ -f "$config_file" ]] && grep -Fq "$KAST_CONFIG_ENV_START_MARKER" "$config_file"; then
    local tmp_conf; tmp_conf="$(mktemp)"
    while IFS= read -r cfg_line || [[ -n "$cfg_line" ]]; do
      if [[ "$cfg_line" == "$KAST_CONFIG_ENV_START_MARKER" ]]; then
        in_block="true"
        printf '%s\n' "$KAST_CONFIG_ENV_START_MARKER"
        _install_print_config_env "$config_dir" "$install_root" "$bin_dir" "$runtime_libs"
        printf '%s\n' "$KAST_CONFIG_ENV_END_MARKER"
        continue
      fi
      if [[ "$in_block" == "true" ]]; then
        [[ "$cfg_line" == "$KAST_CONFIG_ENV_END_MARKER" ]] && in_block="false"
        continue
      fi
      printf '%s\n' "$cfg_line"
    done < "$config_file" > "$tmp_conf"
    cat "$tmp_conf" > "$config_file"; rm -f "$tmp_conf"
    log_step "Updated ${config_file}"
  else
    {
      printf '# Kast configuration — managed by kast installer (do not edit markers)\n'
      printf '%s\n' "$KAST_CONFIG_ENV_START_MARKER"
      _install_print_config_env "$config_dir" "$install_root" "$bin_dir" "$runtime_libs"
      printf '%s\n' "$KAST_CONFIG_ENV_END_MARKER"
    } > "$config_file"
    log_success "Created ${config_file}"
  fi

  local toml_file="${config_dir}/config.toml"
  _install_toml_set_value "$toml_file" "paths" "installRoot" "$install_root"
  _install_toml_set_value "$toml_file" "paths" "binDir" "$bin_dir"
  _install_toml_set_value "$toml_file" "cli" "binaryPath" "${bin_dir}/kast"
  if [[ -n "$runtime_libs" ]]; then
    _install_toml_set_value "$toml_file" "backends.standalone" "runtimeLibsDir" "$runtime_libs"
  fi
  return 0
}

_install_config_dir() {
  if [[ -n "${KAST_CONFIG_HOME:-}" ]]; then
    printf '%s\n' "${KAST_CONFIG_HOME%/}"
  else
    printf '%s/.config/kast\n' "$HOME"
  fi
}

_install_print_config_env() {
  local config_dir="$1" install_root="$2" bin_dir="$3" runtime_libs="${4:-}"
  : "$install_root" "$bin_dir" "$runtime_libs"
  printf 'export KAST_CONFIG_HOME="%s"\n' "$config_dir"
  return 0
}

_install_toml_set_value() {
  local toml_file="$1" section="$2" key="$3" value="$4"
  mkdir -p "$(dirname -- "$toml_file")"
  python3 - "$toml_file" "$section" "$key" "$value" <<'PYTOML'
import sys
from pathlib import Path

toml_file = Path(sys.argv[1])
section = sys.argv[2]
key = sys.argv[3]
value = sys.argv[4]
entry = f'{key} = "{value}"'
header = f'[{section}]'
lines = toml_file.read_text(encoding="utf-8").splitlines() if toml_file.exists() else []

section_start = next((idx for idx, line in enumerate(lines) if line.strip() == header), None)
if section_start is None:
    if lines and lines[-1].strip():
        lines.append("")
    lines.extend([header, entry])
else:
    section_end = next(
        (idx for idx in range(section_start + 1, len(lines)) if lines[idx].strip().startswith("[") and lines[idx].strip().endswith("]")),
        len(lines),
    )
    for idx in range(section_start + 1, section_end):
        stripped = lines[idx].strip()
        if stripped.startswith(f"{key} ") or stripped.startswith(f"{key}="):
            lines[idx] = entry
            break
    else:
        lines.insert(section_end, entry)

toml_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
PYTOML
}

_install_config_source_in_rc() {
  local rc_file="${1:-}"
  [[ -n "$rc_file" ]] || return 0
  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"
  local config_file; config_file="$(_install_config_dir)/env"
  if grep -Fq "$KAST_ENV_SOURCE_START_MARKER" "$rc_file"; then
    local tmp_rc in_block="false"; tmp_rc="$(mktemp)"
    while IFS= read -r rc_line || [[ -n "$rc_line" ]]; do
      if [[ "$rc_line" == "$KAST_ENV_SOURCE_START_MARKER" ]]; then
        in_block="true"
        printf '%s\n' "$KAST_ENV_SOURCE_START_MARKER"
        printf '[[ -f "%s" ]] && source "%s"\n' "$config_file" "$config_file"
        printf '%s\n' "$KAST_ENV_SOURCE_END_MARKER"
        continue
      fi
      if [[ "$in_block" == "true" ]]; then
        [[ "$rc_line" == "$KAST_ENV_SOURCE_END_MARKER" ]] && in_block="false"
        continue
      fi
      printf '%s\n' "$rc_line"
    done < "$rc_file" > "$tmp_rc"
    cat "$tmp_rc" > "$rc_file"; rm -f "$tmp_rc"
    _install_record_shell_patch "$rc_file" "$KAST_ENV_SOURCE_START_MARKER"
    log_step "Updated kast env source in ${rc_file}"
    return
  fi
  {
    printf '\n%s\n' "$KAST_ENV_SOURCE_START_MARKER"
    printf '[[ -f "%s" ]] && source "%s"\n' "$config_file" "$config_file"
    printf '%s\n' "$KAST_ENV_SOURCE_END_MARKER"
  } >> "$rc_file"
  _install_record_shell_patch "$rc_file" "$KAST_ENV_SOURCE_START_MARKER"
  log_success "Added kast env source to ${rc_file}"
}

_install_pick_intellij_instance() {
  local count="${#_INSTALL_ENV_INTELLIJ_LABELS[@]}"
  [[ "$count" -gt 0 ]] || return 1
  if [[ "$count" -eq 1 ]]; then printf '0'; return 0; fi

  local selection; selection="$(_fzf_select "Select IDE instance" "${_INSTALL_ENV_INTELLIJ_LABELS[@]}")"
  [[ -n "$selection" ]] || return 1
  local i
  for i in "${!_INSTALL_ENV_INTELLIJ_LABELS[@]}"; do
    [[ "${_INSTALL_ENV_INTELLIJ_LABELS[$i]}" == "$selection" ]] && { printf '%d' "$i"; return 0; }
  done
  return 1
}

_install_push_plugin_to_intellij() {
  local release_repo="$1" release_tag="$2" local_archive="${3:-}"
  local count="${#_INSTALL_ENV_INTELLIJ_PIDS[@]}"
  [[ "$count" -gt 0 ]] || { log_note "No running IDEA-compatible IDE instances to push to"; return 1; }

  log_section "Push plugin to IDEA / Android Studio"
  local idx; idx="$(_install_pick_intellij_instance)" || return 1
  local selected_pid="${_INSTALL_ENV_INTELLIJ_PIDS[$idx]}"
  local selected_app="${_INSTALL_ENV_INTELLIJ_APPS[$idx]}"
  local selected_label="${_INSTALL_ENV_INTELLIJ_LABELS[$idx]}"
  local plugins_dir; plugins_dir="$(_install_intellij_plugins_dir "$selected_pid" "$selected_app")" || return 1

  log_success "Target: ${selected_label}"
  log_step    "Plugins dir: ${plugins_dir}"

  local plugin_name="kast-intellij-${release_tag}.zip"
  local plugin_tmp="${tmp_dir}/${plugin_name}"
  if [[ ! -f "$plugin_tmp" ]]; then
    if [[ -n "$local_archive" ]]; then
      log_step "Copying local plugin archive ${local_archive}"
      cp "$local_archive" "$plugin_tmp"
    elif [[ "$release_tag" == "local" ]]; then
      log_note "Local install: no plugin archive available; falling back to zip install"
      return 1
    else
      local plugin_url="https://github.com/${release_repo}/releases/download/${release_tag}/${plugin_name}"
      log_step "Downloading ${plugin_name}"
      local attempt
      for attempt in 1 2 3; do
        if _install_download_file "$plugin_url" "$plugin_tmp"; then break; fi
        if [[ "$attempt" -eq 3 ]]; then
          log_note "Failed to download plugin; falling back to zip install"
          return 1
        fi
        sleep 5
      done
    fi
  fi

  mkdir -p "$plugins_dir"
  local jar
  for jar in "${plugins_dir}"/backend-intellij*.jar; do
    [[ -f "$jar" ]] && { log_note "Removing prior JAR: ${jar}"; rm -f "$jar"; }
  done
  unzip -o -q "$plugin_tmp" -d "$plugins_dir"
  log_success "Plugin pushed to ${plugins_dir}"
  log_note "Restart the IDE to activate the plugin"
}

_install_skill_phase() {
  local bin_dir="$1" non_interactive="${2:-false}" install_root="${3:-${HOME}/.kast}"
  local kast_bin="${bin_dir}/kast"
  if [[ ! -x "$kast_bin" ]]; then
    kast_bin="$(command -v kast 2>/dev/null || true)"
    [[ -x "$kast_bin" ]] || { log_note "kast CLI not found; skipping skill install"; return 0; }
  fi

  log_section "Install Copilot skill"

  local requested_scope="${KAST_SKILL_SCOPE:-}"
  if [[ -n "$requested_scope" ]]; then
    case "$requested_scope" in
      global|local|both|skip) ;;
      *) die "KAST_SKILL_SCOPE must be one of: global, local, both, skip" ;;
    esac
  elif [[ "$non_interactive" == "true" ]] || ! can_prompt; then
    log_note "Skipped skill install. Run: kast install skill"
    return 0
  fi

  local global_dir="${install_root}/lib/skills"
  local local_dir; local_dir="$(pwd)/.agents/skills"

  local scope_choice="$requested_scope"
  if [[ -z "$scope_choice" ]]; then
    printf '\n' >&2
    printf '  %-9s %s\n' "$(colorize '1;32' 'global')" "Install to ${global_dir}/kast  (all projects)" >&2
    printf '  %-9s %s\n' "local  " "Install to ${local_dir}/kast  (current directory)" >&2
    printf '  %-9s %s\n' "both   " "Install to both locations" >&2
    printf '  %-9s %s\n' "skip   " "Skip skill install" >&2
    printf '\n' >&2

    scope_choice="$(_fzf_select "Skill scope" "global" "local" "both" "skip")"
  fi
  scope_choice="${scope_choice:-global}"

  case "$scope_choice" in
    global)
      mkdir -p "$global_dir"
      "$kast_bin" install skill --target-dir="$global_dir" --name=kast --yes=true
      log_success "Skill installed at ${global_dir}/kast" ;;
    local)
      mkdir -p "$local_dir"
      "$kast_bin" install skill --target-dir="$local_dir" --name=kast --yes=true
      log_success "Skill installed at ${local_dir}/kast" ;;
    both)
      mkdir -p "$global_dir"
      "$kast_bin" install skill --target-dir="$global_dir" --name=kast --yes=true
      mkdir -p "$local_dir"
      "$kast_bin" install skill --target-dir="$local_dir" --name=kast --yes=true
      log_success "Skill installed globally and locally" ;;
    skip|*)
      log_note "Skipped skill install. Run: kast install skill" ;;
  esac
}

_install_copilot_extension_phase() {
  local bin_dir="$1" non_interactive="${2:-false}" assume_yes="${3:-false}" install_version="${4:-unknown}"
  local kast_bin="${bin_dir}/kast"
  if [[ ! -x "$kast_bin" ]]; then
    kast_bin="$(command -v kast 2>/dev/null || true)"
    [[ -x "$kast_bin" ]] || { log_note "kast CLI not found; skipping copilot-extension install"; return 0; }
  fi

  log_section "Install Copilot extension"

  local repo_root
  repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  if [[ -z "$repo_root" ]]; then
    log_note "Not inside a git repository; skipping copilot-extension install"
    return 0
  fi

  if [[ "$assume_yes" == "true" ]]; then
    "$kast_bin" install copilot-extension --target-dir="${repo_root}/.github" --yes=true
    _install_record_repo "$repo_root" "$install_version"
    log_success "Copilot extension installed at ${repo_root}/.github"
    return 0
  fi

  if [[ "$non_interactive" == "true" ]] || ! can_prompt; then
    log_note "Skipped copilot-extension install. Run: kast install copilot-extension"
    return 0
  fi

  printf '\n' >&2
  printf '  Install the kast Copilot extension into %s/.github?\n' "$repo_root" >&2
  printf '  %-9s %s\n' "$(colorize '1;32' 'yes')" "Install into ${repo_root}/.github" >&2
  printf '  %-9s %s\n' "no     " "Skip (run: kast install copilot-extension later)" >&2
  printf '\n' >&2

  local ext_choice; ext_choice="$(_fzf_select "Install copilot-extension" "yes" "no")"
  ext_choice="${ext_choice:-no}"

  case "$ext_choice" in
    yes)
      "$kast_bin" install copilot-extension --target-dir="${repo_root}/.github" --yes=true
      _install_record_repo "$repo_root" "$install_version"
      log_success "Copilot extension installed at ${repo_root}/.github" ;;
    no|*)
      log_note "Skipped copilot-extension install. Run: kast install copilot-extension" ;;
  esac
}

_install_summary_phase() {
  local install_root="$1" bin_dir="$2" install_mode="${3:-minimal}"
  local intellij_action="${4:-skip}" install_standalone="${5:-false}"
  local config_file; config_file="$(_install_config_dir)/env"

  printf '\n' >&2
  log_section "Installation complete"
  printf '\n  %s\n'  "$(colorize '1;36' 'Kast install root:')" >&2
  printf '  %s\n\n' "  ${install_root}" >&2
  printf '  %s  %s\n' "$(colorize '1;32' 'v')" "CLI binary:  ${bin_dir}/kast" >&2
  printf '  %s  %s\n' "$(colorize '1;32' 'v')" "Config:      ${config_file}" >&2
  if [[ "$intellij_action" == "push" || "$intellij_action" == "zip" ]]; then
    printf '  %s  %s\n' "$(colorize '1;32' 'v')" "IDEA plugin installed" >&2
  fi
  if [[ "$install_standalone" == "true" ]]; then
    printf '  %s  %s\n' "$(colorize '1;32' 'v')" "Standalone backend: ${install_root}/backends/current" >&2
  fi
  printf '\n' >&2
  printf '  %s\n' "$(colorize '1;33' 'Next steps:')" >&2
  printf '  %s\n' "  Open a new shell (or: source ${config_file})" >&2
  printf '  %s\n' "  kast --help" >&2
  printf '  %s\n' "  cd /your/kotlin/project && kast up" >&2
  if [[ "$intellij_action" == "push" ]]; then
    printf '  %s\n' "  Restart the IDE to activate the plugin" >&2
  elif [[ "$intellij_action" == "zip" ]]; then
    printf '  %s\n' "  IDEA / Android Studio: Settings → Plugins → ⚙ → Install from Disk" >&2
    printf '  %s\n' "  Select: ${install_root}/plugins/" >&2
  elif [[ "$install_standalone" == "true" ]]; then
    printf '  %s\n' "  kast up --workspace-root=/absolute/path/to/workspace" >&2
  fi
  printf '\n' >&2
}

cmd_install() {
  local components="" local_build="false" non_interactive="false" assume_yes="false"
  local install_mode_flag="" skip_skill="false" skip_copilot_extension="false"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --components=*) components="${1#--components=}"; shift ;;
      --components)   [[ $# -ge 2 ]] || die "Missing value for --components"; components="$2"; shift 2 ;;
      --mode=*)       install_mode_flag="${1#--mode=}"; shift ;;
      --mode)         [[ $# -ge 2 ]] || die "Missing value for --mode"; install_mode_flag="$2"; shift 2 ;;
      --skip-skill)   skip_skill="true"; shift ;;
      --skip-copilot-extension) skip_copilot_extension="true"; shift ;;
      --yes)          assume_yes="true"; shift ;;
      --local)        local_build="true"; shift ;;
      --non-interactive) non_interactive="true"; skip_skill="true"; skip_copilot_extension="true"; shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh install [options]
       curl -fsSL .../kast.sh | bash

Install the Kast CLI and optional components.

Options:
  --mode=minimal|full|auto  Drive the install wizard path (default: interactive)
                              minimal - CLI + optional IDEA plugin
                              full    - CLI + standalone JVM backend
                              auto    - detect environment and recommend
  --components=<list>       Expert override: comma-separated cli,intellij,backend,all
                              Skips the wizard entirely; same as previous behavior
  --skip-skill              Skip Copilot skill install step
  --skip-copilot-extension  Skip Copilot extension install step
  --yes                     Auto-install the Copilot extension when inside a git repo
  --local                   Install from local dist/ artifacts (built by ./kast.sh build)
  --non-interactive         Skip all interactive prompts (implies --skip-skill, --skip-copilot-extension)
  --help, -h                Show this help

Environment:
  KAST_MANAGED_ROOT              Managed install root override (default: $HOME/.kast)
  KAST_ARCHIVE_PATH              Local CLI archive path
  KAST_EXPECTED_SHA256           Expected SHA-256 for KAST_ARCHIVE_PATH
  KAST_BACKEND_ARCHIVE_PATH      Local standalone backend archive path
  KAST_BACKEND_EXPECTED_SHA256   Expected SHA-256 for KAST_BACKEND_ARCHIVE_PATH
  KAST_DOWNLOAD_PROGRESS         auto, always, or never (default: auto)
  KAST_INSTALL_SOURCE            Install source metadata label (default: kast.sh)
  KAST_SKILL_SCOPE               Skill scope when prompts are unavailable: global, local, both, skip
USAGE
        return 0 ;;
      *) die "Unknown argument: $1" ;;
    esac
  done

  _INSTALL_SHELL_PATCHES=()
  _INSTALL_MANAGED_REPOS=()
  _INSTALL_MIGRATION_SUMMARY=()

  _install_banner

  need_tool curl
  need_tool python3

  local release_repo cli_release_repo platform_id install_root bin_dir shell_name
  local archive_path="" archive_name="" archive_source="" cli_release_tag="" archive_digest="" installed_backend_version=""
  local requested_cli_version="${KAST_CLI_VERSION:-${KAST_VERSION:-}}"
  local requested_backend_version="${KAST_BACKEND_VERSION:-${KAST_VERSION:-}}"

  release_repo="$(_install_resolve_release_repo)"
  cli_release_repo="$(_install_resolve_cli_release_repo)"
  platform_id="$(_install_detect_platform_id)"
  install_root="${KAST_MANAGED_ROOT:-${HOME}/.kast}"
  install_root="${install_root%/}"
  [[ -n "$install_root" ]] || die "KAST_MANAGED_ROOT resolved to an empty path"
  bin_dir="${install_root}/bin"
  shell_name="$(_install_resolve_shell_name)"

  _install_migrate_legacy_layout "$install_root" "$bin_dir"

  # Phase 1: Detect environment
  log_section "Detecting environment"
  _install_detect_env
  [[ -n "$_INSTALL_ENV_EXISTING_VERSION" ]] && log_step "Existing kast: ${_INSTALL_ENV_EXISTING_VERSION}"
  [[ "$_INSTALL_ENV_HAS_JAVA" == "true" ]]  && log_step "Java detected"
  [[ "${#_INSTALL_ENV_INTELLIJ_PIDS[@]}" -gt 0 ]] && \
    log_step "IDEA-compatible IDEs: ${#_INSTALL_ENV_INTELLIJ_PIDS[@]} instance(s) running"

  # Resolve component set: expert (--components) vs wizard
  local install_cli="false" install_intellij="false" install_standalone="false"
  local wizard_mode="true"

  if [[ -n "$components" ]]; then
    # Expert path: --components overrides wizard entirely
    wizard_mode="false"
    [[ "$components" == "all" ]] && components="cli,intellij,backend"
    local comp; IFS=',' read -r -a component_list <<<"$components"
    for comp in "${component_list[@]}"; do
      case "$comp" in
        cli)                        install_cli="true" ;;
        intellij)                   install_intellij="true" ;;
        backend|standalone-backend) install_standalone="true" ;;
        *) die "Unknown component: $comp" ;;
      esac
    done
  else
    # Phase 2: Mode selection
    if [[ -n "$install_mode_flag" ]]; then
      _INSTALL_MODE="$install_mode_flag"
      [[ "$_INSTALL_MODE" == "auto" ]] && _INSTALL_MODE="minimal"
      if [[ "$_INSTALL_MODE" == "minimal" && "${#_INSTALL_ENV_INTELLIJ_PIDS[@]}" -gt 0 ]]; then
        _INSTALL_INTELLIJ_ACTION="push"
      elif [[ "$_INSTALL_MODE" == "minimal" ]]; then
        _INSTALL_INTELLIJ_ACTION="zip"
      fi
    else
      _install_mode_select "$non_interactive"
    fi
    install_cli="true"
    [[ "$_INSTALL_MODE" == "full" ]] && install_standalone="true"
    [[ "$_INSTALL_INTELLIJ_ACTION" == "zip" || "$_INSTALL_INTELLIJ_ACTION" == "push" ]] && install_intellij="true"
  fi

  # Validate local build sources
  local local_plugin_archive="" local_backend_archive="${KAST_BACKEND_ARCHIVE_PATH:-}"
  local local_backend_digest="${KAST_BACKEND_EXPECTED_SHA256:-}"
  if [[ "$install_standalone" == "true" && -n "$local_backend_archive" ]]; then
    [[ -f "$local_backend_archive" ]] || die "KAST_BACKEND_ARCHIVE_PATH does not exist: $local_backend_archive"
  fi
  if [[ "$local_build" == "true" ]]; then
    if [[ "$install_cli" == "true" && -z "${KAST_ARCHIVE_PATH:-}" ]]; then
      die "Local Rust CLI archives are built in kast-rs. Set KAST_ARCHIVE_PATH to a kast-rs release zip."
    fi
    if [[ "$install_intellij" == "true" ]]; then
      local_plugin_archive="${SCRIPT_DIR}/dist/plugin.zip"
      [[ -f "$local_plugin_archive" ]] || die "Local plugin archive not found at ${local_plugin_archive}. Run ./kast.sh build plugin first."
    fi
    if [[ "$install_standalone" == "true" ]]; then
      local_backend_archive="${local_backend_archive:-${SCRIPT_DIR}/dist/backend.zip}"
      [[ -f "$local_backend_archive" ]] || die "Local backend archive not found at ${local_backend_archive}. Run ./kast.sh build backend first."
    fi
  fi

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"

  # Phase 3: Config file
  log_section "Configuration"
  local rc_file; rc_file="$(_install_resolve_shell_rc_file)"
  _install_config_write "$install_root" "$bin_dir"
  _install_config_source_in_rc "${rc_file:-}"

  # Phase 4: CLI install
  if [[ "$install_cli" == "true" ]]; then
    if [[ -n "${KAST_ARCHIVE_PATH:-}" ]]; then
      log_section "Resolve release"
      archive_path="$KAST_ARCHIVE_PATH"
      [[ -f "$archive_path" ]] || die "KAST_ARCHIVE_PATH does not exist: $archive_path"
      archive_name="$(basename -- "$archive_path")"
      archive_source="$archive_path"
      cli_release_tag="${requested_cli_version:-local}"
      archive_digest="${KAST_EXPECTED_SHA256:-}"
      log_step "Using local archive ${archive_name}"
    else
      local metadata_url="${KAST_CLI_RELEASE_METADATA_URL:-}"
      if [[ -z "$metadata_url" ]]; then
        if [[ -n "$requested_cli_version" ]]; then
          metadata_url="https://api.github.com/repos/${cli_release_repo}/releases/tags/${requested_cli_version}"
        else
          metadata_url="https://api.github.com/repos/${cli_release_repo}/releases/latest"
        fi
      fi

      local metadata_path="${tmp_dir}/release.json"
      log_section "Resolve release"
      log_step "Resolving CLI release metadata for ${cli_release_repo} (${platform_id})"
      curl \
        --fail \
        --location \
        --retry 3 \
        --retry-delay 2 \
        --silent \
        --show-error \
        --header "$GITHUB_API_ACCEPT" \
        --header "$GITHUB_API_VERSION" \
        --output "$metadata_path" \
        "$metadata_url"

      local release_info_path="${tmp_dir}/release-info.txt"
      _install_extract_release_metadata "$metadata_path" "$platform_id" >"$release_info_path"

      local release_info=()
      local release_line=""
      while IFS= read -r release_line || [[ -n "$release_line" ]]; do
        release_info+=("$release_line")
      done <"$release_info_path"
      [[ "${#release_info[@]}" -eq 4 ]] || die "Release metadata parsing returned incomplete asset information"

      cli_release_tag="${release_info[0]}"
      archive_name="${release_info[1]}"
      archive_source="${release_info[2]}"
      archive_digest="${release_info[3]}"
      archive_path="${tmp_dir}/${archive_name}"

      log_step "Downloading ${archive_name}"
      local download_attempt
      for download_attempt in 1 2 3; do
        if _install_download_file "$archive_source" "$archive_path"; then break; fi
        if [[ "$download_attempt" -eq 3 ]]; then
          die "Failed to download ${archive_name} after 3 attempts"
        fi
        log_note "Download attempt ${download_attempt} failed; retrying in 5 seconds"
        sleep 5
      done
    fi

    log_section "Verify package"
    if [[ -n "$archive_digest" ]]; then
      local expected_sha256="${archive_digest#sha256:}"
      local actual_sha256; actual_sha256="$(compute_sha256 "$archive_path")"
      [[ "$actual_sha256" == "$expected_sha256" ]] || die "Checksum verification failed for ${archive_name}"
      log_success "Verified SHA-256 for ${archive_name}"
    else
      log_note "No published SHA-256 digest was available for ${archive_name}; skipping checksum verification."
    fi

    local staging_dir="${tmp_dir}/extract"
    local release_dir="${install_root}/releases/${cli_release_tag}/${platform_id}"
    local current_link="${install_root}/current"
    local bin_link="${bin_dir}/kast"

    log_section "Install files"

    if [[ -d "$release_dir" && ! -f "${release_dir}/.install-metadata.json" ]]; then
      log_note "Removing partial install at ${release_dir}"
      rm -rf "$release_dir"
    fi

    if [[ -L "$current_link" && ! -e "$current_link" ]]; then
      log_note "Removing broken symlink at ${current_link}"
      rm -f "$current_link"
    fi

    extract_zip_archive "$archive_path" "$staging_dir"

    if [[ -f "${staging_dir}/kast" ]]; then
      mkdir -p "${staging_dir}/kast-release"
      mv "${staging_dir}/kast" "${staging_dir}/kast-release/kast"
      mv "${staging_dir}/kast-release" "${staging_dir}/kast"
    fi
    [[ -d "${staging_dir}/kast" ]] || die "Archive ${archive_name} did not contain the expected top-level kast binary"

    rm -rf "$release_dir"
    mkdir -p "$(dirname -- "$release_dir")"
    mv "${staging_dir}/kast" "$release_dir"

    [[ -f "${release_dir}/kast" ]] || die "Installed archive did not contain the kast launcher"
    chmod +x "${release_dir}/kast"

    _install_write_metadata \
      "${release_dir}/.install-metadata.json" \
      "$cli_release_repo" "$cli_release_tag" "$platform_id" "$archive_name" "$archive_source" "${KAST_INSTALL_SOURCE:-kast.sh}"

    mkdir -p "$install_root" "$bin_dir"
    ln -sfn "$release_dir" "$current_link"
    {
      printf '#!/usr/bin/env bash\n'
      printf 'set -euo pipefail\n'
      printf 'exec "%s/current/kast" "$@"\n' "$install_root"
    } > "$bin_link"
    chmod +x "$bin_link"
    log_success "Installed ${archive_name} into ${release_dir}"

    # Phase 5: Shell setup
    log_section "Shell setup"
    _install_ensure_bin_dir_on_path "$bin_dir"
    _install_shell_completion "$release_dir" "$install_root" "$shell_name"
  fi

  # Phase 6: IDEA plugin
  if [[ "$install_intellij" == "true" ]]; then
    local resolved_tag; resolved_tag="$(_install_resolve_release_tag "$release_repo" "$requested_backend_version")"
    if [[ "$wizard_mode" == "true" && "$_INSTALL_INTELLIJ_ACTION" == "push" ]]; then
      _install_push_plugin_to_intellij "$release_repo" "$resolved_tag" "$local_plugin_archive" \
        || _install_intellij_plugin "$release_repo" "$resolved_tag" "$install_root" "$local_plugin_archive" || true
    else
      _install_intellij_plugin "$release_repo" "$resolved_tag" "$install_root" "$local_plugin_archive" || true
    fi
  fi

  # Phase 7: Standalone backend
  if [[ "$install_standalone" == "true" ]]; then
    local resolved_tag
    if [[ -n "$local_backend_archive" && -z "$requested_backend_version" ]]; then
      resolved_tag="local"
    else
      resolved_tag="$(_install_resolve_release_tag "$release_repo" "$requested_backend_version")"
    fi
    if _install_standalone_backend "$release_repo" "$resolved_tag" "$install_root" "$local_backend_archive" "$bin_dir" "$local_backend_digest"; then
      installed_backend_version="$resolved_tag"
      local runtime_libs_dir="${install_root}/backends/standalone-${resolved_tag}/runtime-libs"
      _install_config_write "$install_root" "$bin_dir" "$runtime_libs_dir"
    fi
  fi

  # Phase 8: Copilot skill
  if [[ "$skip_skill" != "true" && "$install_cli" == "true" ]]; then
    _install_skill_phase "$bin_dir" "$non_interactive" "$install_root" || true
  fi

  # Phase 8.5: Copilot extension
  if [[ "$skip_copilot_extension" != "true" && "$install_cli" == "true" ]]; then
    _install_copilot_extension_phase "$bin_dir" "$non_interactive" "$assume_yes" "${cli_release_tag:-${requested_cli_version:-unknown}}" || true
  fi

  # Phase 9: Summary
  if [[ "$wizard_mode" == "true" ]]; then
    _install_summary_phase \
      "$install_root" "$bin_dir" \
      "${_INSTALL_MODE:-minimal}" \
      "${_INSTALL_INTELLIJ_ACTION:-skip}" \
      "$install_standalone"
  else
    log_section "Install summary"
    log "Install root:  ${install_root}"
    log "Binary:        ${bin_dir}/kast"
    log "Config:        $(_install_config_dir)/env"
    log "Components:    ${components}"
    [[ -n "${rc_file:-}" ]] && log "Shell RC:      ${rc_file}"
    log "Next:          cd /your/kotlin/project && kast up"
    log_section "Ready"
    if [[ "$install_cli" == "true" ]]; then
      log_success "Launcher: ${bin_dir}/kast"
      if _install_path_contains "$bin_dir"; then
        log_step "Try: kast --help"
      else
        log_note "Export PATH=\"${bin_dir}:\$PATH\" then run: kast --help"
      fi
    fi
    [[ "$install_intellij" == "true" ]]  && log_step "IDEA plugin: ${install_root}/plugins/"
    [[ "$install_standalone" == "true" ]] && log_success "Standalone backend: ${install_root}/backends/current/"
  fi

  local manifest_version="${cli_release_tag:-${requested_cli_version:-unknown}}"
  _install_write_manifest "$install_root" "$manifest_version" "$platform_id" "$installed_backend_version"
}

# ===========================================================================
# Top-level dispatch
# ===========================================================================

usage_main() {
  cat >&2 << 'USAGE'
Usage: ./kast.sh <subcommand> [options]

Subcommands:
  build    Build portable distribution artifacts  ->  dist/
  install  Install Kast CLI and optional components

Run ./kast.sh <subcommand> --help for subcommand-specific options.

Recommended local CLI install:
  brew tap amichne/kast
  brew install kast

Shell installer (auto-invokes install):
  curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
  /bin/bash -c "$(curl -fsSL .../kast.sh)" -- install --components=all
USAGE
}

main() {
  local cmd="${1:-}"

  # Auto-detect curl/pipe or `bash -c "$(curl ...)"` invocation:
  # SCRIPT_DIR is empty whenever the script is loaded from stdin, a pipe,
  # or bash -c — all the documented one-liner installation forms.
  # A real file-backed invocation (./kast.sh build) always sets SCRIPT_DIR.
  if [[ -z "$SCRIPT_DIR" ]] && [[ -z "$cmd" || "$cmd" == --* ]]; then
    cmd_install "$@"
    return
  fi

  case "$cmd" in
    build)          shift; cmd_build "$@" ;;
    install)        shift; cmd_install "$@" ;;
    --help|-h|help) usage_main ;;
    "")             usage_main; exit 1 ;;
    *)              die "Unknown subcommand: ${cmd}. Run ./kast.sh --help for usage." ;;
  esac
}

main "$@"
