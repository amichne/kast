#!/usr/bin/env bash
# kast.sh - unified Kast shell tooling
#
# Subcommands:
#   build    Build portable distribution artifacts  ->  dist/
#   release  Prepare a release asset from the portable distribution zip
#   install  Install Kast CLI from GitHub releases
#
# Curl one-liner (auto-invokes install):
#   curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash
#   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
#
# Explicit subcommand:
#   ./kast.sh build [cli] [plugin] [backend] [--all]
#   ./kast.sh release --tag v1.0.0 --platform-id linux-x64
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
PORTABLE_DIST_DIR="${REPO_ROOT}/kast-cli/build/portable-dist/kast-cli"
PORTABLE_ZIP_DIR="${REPO_ROOT}/kast-cli/build/distributions"
PLUGIN_DIST_DIR="${REPO_ROOT}/backend-intellij/build/distributions"
BACKEND_PORTABLE_DIST_DIR="${REPO_ROOT}/backend-standalone/build/portable-dist/backend-standalone"
BACKEND_PORTABLE_ZIP_DIR="${REPO_ROOT}/backend-standalone/build/distributions"

# Install constants
readonly DEFAULT_RELEASE_REPO="amichne/kast"
readonly GITHUB_API_ACCEPT="Accept: application/vnd.github+json"
readonly GITHUB_API_VERSION="X-GitHub-Api-Version: 2022-11-28"
readonly PATH_MARKER="# Added by the Kast installer"
readonly COMPLETION_START_MARKER="# >>> Kast completion >>>"
readonly COMPLETION_END_MARKER="# <<< Kast completion <<<"

# Shared mutable temp dir -- used by both build and install; cleaned on EXIT
tmp_dir=""

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

can_prompt() { [[ -r /dev/tty && -w /dev/tty ]]; }

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

_BUILD_ALL_TARGETS=(cli plugin backend)
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

_build_run_gradle_tasks() {
  ( cd "$REPO_ROOT"; "$GRADLEW" "$@" )
}

_build_run_gradle_tasks_with_retry() {
  if _build_run_gradle_tasks "$@"; then return 0; fi
  log_note "Gradle build failed; stopping daemon and retrying"
  "$GRADLEW" --stop >/dev/null 2>&1 || true
  _build_run_gradle_tasks "$@"
}

_build_resolve_cli_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PORTABLE_ZIP_DIR}"/kast-cli-*-portable.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a portable zip under ${PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
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

_build_cli() {
  log_section "Building target: cli"
  rm -rf "${REPO_ROOT}/kast-cli/build/portable-dist" "${REPO_ROOT}/kast-cli/build/distributions"
  _build_run_gradle_tasks_with_retry stageCliDist buildCliPortableZip

  log_step "Verifying staged CLI tree in ${PORTABLE_DIST_DIR}"
  [[ -x "${PORTABLE_DIST_DIR}/kast-cli" ]]                    || die "Missing staged kast-cli launcher"
  [[ -d "${PORTABLE_DIST_DIR}/runtime-libs" ]]                 || die "Missing staged runtime-libs directory"
  [[ -f "${PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]]   || die "Missing staged runtime classpath file"
  local jars=()
  shopt -s nullglob; jars=("${PORTABLE_DIST_DIR}"/libs/kast-cli-*-all.jar); shopt -u nullglob
  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${PORTABLE_DIST_DIR}/libs"

  local dist_dir="${DIST_ROOT}/cli"
  local dist_zip="${DIST_ROOT}/cli.zip"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing CLI tree into ${dist_dir}"
  mkdir -p "$DIST_ROOT"
  cp -R "$PORTABLE_DIST_DIR" "${tmp_dir}/cli"
  rm -rf "$dist_dir"
  mv "${tmp_dir}/cli" "$dist_dir"
  log_success "Published ${dist_dir}"

  local source_zip; source_zip="$(_build_resolve_cli_zip)"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"

  rm -rf "$tmp_dir"; tmp_dir=""
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
  [[ -x "${BACKEND_PORTABLE_DIST_DIR}/backend-standalone" ]]          || die "Missing staged backend-standalone launcher"
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
  local cli_dir="${DIST_ROOT}/cli"
  if [[ -d "$cli_dir" && (! -f "${cli_dir}/kast-cli" || ! -d "${cli_dir}/runtime-libs") ]]; then
    log_step "Removing incomplete ${cli_dir} from a previous run"
    rm -rf "$cli_dir"
  fi

  local backend_dir="${DIST_ROOT}/backend"
  if [[ -d "$backend_dir" && (! -f "${backend_dir}/backend-standalone" || ! -d "${backend_dir}/runtime-libs") ]]; then
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

  while [[ $# -gt 0 ]]; do
    case "$1" in
      cli|plugin|backend)
        _build_selected_targets+=("$1"); shift ;;
      --all)
        _build_selected_targets=("${_BUILD_ALL_TARGETS[@]}"); shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh build [target...] [options]

Builds selected Kast components and publishes artifacts to dist/.

Targets (positional, repeatable):
  cli          CLI binary + JVM wrapper  -> dist/cli/   dist/cli.zip
  plugin       IntelliJ plugin zip       -> dist/plugin.zip
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
      cli)     _build_cli ;;
      plugin)  _build_plugin ;;
      backend) _build_backend ;;
    esac
  done

  _build_openapi

  log_section "Build complete"
  for target in "${_build_selected_targets[@]}"; do
    case "$target" in
      cli)     log_success "cli     ->  ${DIST_ROOT}/cli/  ${DIST_ROOT}/cli.zip" ;;
      plugin)  log_success "plugin  ->  ${DIST_ROOT}/plugin.zip" ;;
      backend) log_success "backend ->  ${DIST_ROOT}/backend/  ${DIST_ROOT}/backend.zip" ;;
    esac
  done
}

# ===========================================================================
# cmd_release -- prepare a release asset from the portable dist zip
# ===========================================================================

cmd_release() {
  [[ -n "$REPO_ROOT" && -d "$REPO_ROOT" ]] || die "Release requires a local checkout (not valid when curl-piped)"

  local tag="" platform_id="" skip_build="false"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --tag=*)         tag="${1#*=}"; shift ;;
      --tag)           [[ $# -ge 2 ]] || die "Missing value for --tag"; tag="$2"; shift 2 ;;
      --platform-id=*) platform_id="${1#*=}"; shift ;;
      --platform-id)   [[ $# -ge 2 ]] || die "Missing value for --platform-id"; platform_id="$2"; shift 2 ;;
      --skip-build)    skip_build="true"; shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh release --tag <version> --platform-id <id> [options]

Prepares a release asset from the portable distribution zip.

Options:
  --tag <version>       Release tag (e.g. v1.2.3). Required.
  --platform-id <id>    Platform identifier (e.g. linux-x64, macos-arm64). Required.
  --skip-build          Skip the Gradle build (use existing portable zip).
  --help, -h            Show this help.

Examples:
  ./kast.sh release --tag v1.0.0 --platform-id linux-x64
  ./kast.sh release --tag v1.0.0 --platform-id macos-arm64 --skip-build
USAGE
        return 0
        ;;
      *) die "Unknown argument: $1" ;;
    esac
  done

  [[ -n "$tag" ]]         || die "Missing required --tag"
  [[ -n "$platform_id" ]] || die "Missing required --platform-id"
  [[ -x "$GRADLEW" ]]     || die "Missing executable gradlew at ${GRADLEW}"

  if [[ "$skip_build" != "true" ]]; then
    log_section "Building portable distribution"
    if ! ( cd "$REPO_ROOT"; "$GRADLEW" :kast-cli:portableDistZip ); then
      log_note "Gradle build failed; stopping daemon and retrying with --no-daemon"
      "$GRADLEW" --stop >/dev/null 2>&1 || true
      ( cd "$REPO_ROOT"; "$GRADLEW" --no-daemon :kast-cli:portableDistZip )
    fi
  fi

  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PORTABLE_ZIP_DIR}"/kast-cli-*-portable.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "No portable zip found under ${PORTABLE_ZIP_DIR}. Run without --skip-build."

  local source_zip="$newest"
  local asset_name="kast-${tag}-${platform_id}.zip"
  local asset_path="${DIST_ROOT}/${asset_name}"

  log_section "Preparing release asset"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$asset_path"

  local digest; digest="$(compute_sha256 "$asset_path")"
  printf '%s  %s\n' "$digest" "$asset_name" >> "${DIST_ROOT}/checksums.txt"

  log_success "Release asset: ${asset_path}"
  log "SHA-256: ${digest}"
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
  curl \
    --fail \
    --location \
    --retry 3 \
    --retry-delay 2 \
    --silent \
    --show-error \
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
        platform_id="$4" archive_name="$5" archive_source="$6"
  python3 - "$output_path" "$release_repo" "$release_tag" \
            "$platform_id" "$archive_name" "$archive_source" <<'PY'
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

  mkdir -p "$(dirname -- "$rc_file")"
  touch "$rc_file"

  if ! grep -Fq "$PATH_MARKER" "$rc_file"; then
    {
      printf '\n%s\n' "$PATH_MARKER"
      printf 'export PATH="%s:$PATH"\n' "$bin_dir"
    } >> "$rc_file"
    log_success "Added ${bin_dir} to PATH in ${rc_file}"
    return
  fi

  log_step "PATH already includes the Kast installer block in ${rc_file}"
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
  log_success "Enabled ${shell_name} completions in ${rc_file}"
}

_install_intellij_plugin() {
  local release_repo="$1" release_tag="$2" install_root="$3" local_archive="${4:-}"

  log_section "Install IntelliJ plugin"
  local plugin_dir="${install_root}/plugins"
  local plugin_name="kast-intellij-${release_tag}.zip"
  local plugin_path="${plugin_dir}/${plugin_name}"
  mkdir -p "$plugin_dir"

  if [[ -n "$local_archive" ]]; then
    log_step "Copying local plugin archive ${local_archive}"
    cp "$local_archive" "$plugin_path"
  else
    local plugin_url="https://github.com/${release_repo}/releases/download/${release_tag}/${plugin_name}"
    log_step "Downloading IntelliJ plugin ${plugin_name}"
    local download_attempt
    for download_attempt in 1 2 3; do
      if _install_download_file "$plugin_url" "$plugin_path"; then break; fi
      if [[ "$download_attempt" -eq 3 ]]; then
        log_note "Failed to download IntelliJ plugin after 3 attempts; skipping"
        return 1
      fi
      log_note "Download attempt ${download_attempt} failed; retrying in 5 seconds"
      sleep 5
    done
  fi

  log_success "IntelliJ plugin saved to ${plugin_path}"
  log_note "Install from IntelliJ: Settings -> Plugins -> gear icon -> Install Plugin from Disk"
  log_note "Select: ${plugin_path}"
  return 0
}

_install_standalone_backend() {
  local release_repo="$1" release_tag="$2" install_root="$3"

  log_section "Install standalone backend"
  # TODO: standalone backend zip is not yet published as a release asset.
  #       Once the release pipeline produces kast-standalone-<tag>.zip, download
  #       it here (mirroring _install_intellij_plugin) and extract to
  #       "${install_root}/backends/standalone-${release_tag}/".
  log_note "Standalone backend installation from releases is not yet available."
  log_note "Build from source: ./kast.sh build backend"
  log_note "Then launch with: <build-output>/kast-standalone --workspace-root=<path>"
  return 0
}

_install_prompt_components() {
  if ! can_prompt; then
    printf '%s\n' "cli"; return
  fi
  log_prompt "Which components? [cli/intellij/all] (cli) "
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
    all)       printf '%s\n' "cli,intellij" ;;
    *)         printf '%s\n' "$reply" ;;
  esac
}

cmd_install() {
  local components="" local_build="false" non_interactive="false"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --components=*) components="${1#--components=}"; shift ;;
      --components)   [[ $# -ge 2 ]] || die "Missing value for --components"; components="$2"; shift 2 ;;
      --local)        local_build="true"; shift ;;
      --non-interactive) non_interactive="true"; shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh install [options]
       curl -fsSL .../kast.sh | bash

Install the Kast CLI and optional components.

Options:
  --components=<list>   Comma-separated: cli,intellij,all (default: cli)
                        cli              - Install the kast CLI binary
                        intellij         - Download IntelliJ plugin zip
                        all              - cli + intellij
  --local               Install from local dist/ artifacts built by ./kast.sh build
  --non-interactive     Skip all interactive prompts
  --help, -h            Show this help
USAGE
        return 0
        ;;
      *) die "Unknown argument: $1" ;;
    esac
  done

  log_section "Kast installer"
  log "Install the published CLI, wire your shell, and leave the workspace commands ready to run."

  need_tool curl
  need_tool python3

  local release_repo platform_id install_root bin_dir shell_name
  local archive_path="" archive_name="" archive_source="" release_tag="" archive_digest=""

  release_repo="$(_install_resolve_release_repo)"
  platform_id="$(_install_detect_platform_id)"
  install_root="${KAST_INSTALL_ROOT:-${HOME}/.local/share/kast}"
  bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"
  shell_name="$(_install_resolve_shell_name)"

  if [[ -z "$components" ]]; then
    if [[ "$non_interactive" == "true" ]]; then
      components="cli"
    else
      components="$(_install_prompt_components)"
    fi
  fi
  [[ "$components" == "all" ]] && components="cli,intellij"

  local install_cli="false" install_intellij="false"
  IFS=',' read -r -a component_list <<<"$components"
  for comp in "${component_list[@]}"; do
    case "$comp" in
      cli|standalone|server) install_cli="true" ;;  # standalone/server kept as aliases
      intellij)              install_intellij="true" ;;
      *) die "Unknown component: $comp" ;;
    esac
  done

  local local_plugin_archive=""
  if [[ "$local_build" == "true" ]]; then
    if [[ "$install_cli" == "true" && -z "${KAST_ARCHIVE_PATH:-}" ]]; then
      local dist_archive="${SCRIPT_DIR}/dist/cli.zip"
      [[ -f "$dist_archive" ]] || die "Local build archive not found at ${dist_archive}. Run ./kast.sh build first."
      KAST_ARCHIVE_PATH="$dist_archive"
    fi
    if [[ "$install_intellij" == "true" ]]; then
      local_plugin_archive="${SCRIPT_DIR}/dist/plugin.zip"
      [[ -f "$local_plugin_archive" ]] || die "Local plugin archive not found at ${local_plugin_archive}. Run ./kast.sh build plugin first."
    fi
  fi

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"

  if [[ "$install_cli" == "true" ]]; then
    if [[ -n "${KAST_ARCHIVE_PATH:-}" ]]; then
      log_section "Resolve release"
      archive_path="$KAST_ARCHIVE_PATH"
      [[ -f "$archive_path" ]] || die "KAST_ARCHIVE_PATH does not exist: $archive_path"
      archive_name="$(basename -- "$archive_path")"
      archive_source="$archive_path"
      release_tag="${KAST_VERSION:-local}"
      archive_digest="${KAST_EXPECTED_SHA256:-}"
      log_step "Using local archive ${archive_name}"
    else
      local metadata_url="${KAST_RELEASE_METADATA_URL:-}"
      if [[ -z "$metadata_url" ]]; then
        if [[ -n "${KAST_VERSION:-}" ]]; then
          metadata_url="https://api.github.com/repos/${release_repo}/releases/tags/${KAST_VERSION}"
        else
          metadata_url="https://api.github.com/repos/${release_repo}/releases/latest"
        fi
      fi

      local metadata_path="${tmp_dir}/release.json"
      log_section "Resolve release"
      log_step "Resolving release metadata for ${release_repo} (${platform_id})"
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

      release_tag="${release_info[0]}"
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
    local release_dir="${install_root}/releases/${release_tag}/${platform_id}"
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

    if [[ -d "${staging_dir}/kast-cli" ]]; then
      mv "${staging_dir}/kast-cli" "${staging_dir}/kast"
    fi
    [[ -d "${staging_dir}/kast" ]] || die "Archive ${archive_name} did not contain the expected kast-cli/ or kast/ directory"

    rm -rf "$release_dir"
    mkdir -p "$(dirname -- "$release_dir")"
    mv "${staging_dir}/kast" "$release_dir"

    [[ -f "${release_dir}/kast-cli" ]] || die "Installed archive did not contain the kast-cli launcher"
    chmod +x "${release_dir}/kast-cli"

    _install_write_metadata \
      "${release_dir}/.install-metadata.json" \
      "$release_repo" "$release_tag" "$platform_id" "$archive_name" "$archive_source"

    mkdir -p "$install_root" "$bin_dir"
    ln -sfn "$release_dir" "$current_link"
    {
      printf '#!/usr/bin/env bash\n'
      printf 'set -euo pipefail\n'
      printf 'exec "%s/current/kast-cli" "$@"\n' "$install_root"
    } > "$bin_link"
    chmod +x "$bin_link"
    log_success "Installed ${archive_name} into ${release_dir}"

    log_section "Shell setup"
    _install_ensure_bin_dir_on_path "$bin_dir"
    _install_shell_completion "$release_dir" "$install_root" "$shell_name"
  fi

  if [[ "$install_intellij" == "true" ]]; then
    local resolved_tag="${release_tag:-}"
    if [[ -z "$resolved_tag" ]]; then
      resolved_tag="${KAST_VERSION:-}"
      if [[ -z "$resolved_tag" ]]; then
        local latest_meta="${tmp_dir}/latest-release.json"
        curl \
          --fail --location --retry 3 --retry-delay 2 --silent --show-error \
          --header "$GITHUB_API_ACCEPT" \
          --header "$GITHUB_API_VERSION" \
          --output "$latest_meta" \
          "https://api.github.com/repos/${release_repo}/releases/latest"
        resolved_tag="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['tag_name'])" "$latest_meta")"
      fi
    fi
    _install_intellij_plugin "$release_repo" "$resolved_tag" "$install_root" "$local_plugin_archive" || true
  fi

  local rc_file; rc_file="$(_install_resolve_shell_rc_file)"

  log_section "Install summary"
  log "Install root:   ${install_root}"
  log "Binary path:    ${bin_dir}/kast"
  log "Config dir:     ${install_root}/current"
  log "Components:     ${components}"
  [[ -n "$rc_file" ]] && log "Shell RC:       ${rc_file}"

  log_section "Ready"
  if [[ "$install_cli" == "true" ]]; then
    log_success "Launcher path: ${bin_dir}/kast"
    if _install_path_contains "$bin_dir"; then
      log_step "Try: kast --help"
      log_step "Start a backend before running analysis commands:"
      log_step "  kast-standalone --workspace-root=/absolute/path/to/workspace  (standalone JVM backend)"
      log_step "  OR open IntelliJ IDEA with the kast plugin installed"
      log_step "Then run commands e.g.: kast references ..."
      if [[ "$install_intellij" != "true" ]]; then
        log_note "To also install the IntelliJ plugin: ./kast.sh install --components=intellij"
      fi
    else
      log_note "Export PATH=\"${bin_dir}:\$PATH\""
      log_note "Then run: kast --help"
      log_note "Start a backend: kast-standalone --workspace-root=<path>"
    fi
  fi
  if [[ "$install_intellij" == "true" ]]; then
    log_step "IntelliJ plugin: ${install_root}/plugins/"
  fi
}

# ===========================================================================
# Top-level dispatch
# ===========================================================================

usage_main() {
  cat >&2 << 'USAGE'
Usage: ./kast.sh <subcommand> [options]

Subcommands:
  build    Build portable distribution artifacts  ->  dist/
  release  Prepare a release asset from the portable distribution zip
  install  Install Kast CLI from GitHub releases

Run ./kast.sh <subcommand> --help for subcommand-specific options.

Curl pipe (auto-invokes install):
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
    release)        shift; cmd_release "$@" ;;
    install)        shift; cmd_install "$@" ;;
    --help|-h|help) usage_main ;;
    "")             usage_main; exit 1 ;;
    *)              die "Unknown subcommand: ${cmd}. Run ./kast.sh --help for usage." ;;
  esac
}

main "$@"
