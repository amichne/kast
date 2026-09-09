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
  install.sh --local session [--idea-home <absolute-path>]
  install.sh --local persistent [<installation-options>]
  install.sh [install] [--purge-existing] [--version <major.minor.patch>]
             [--install-root <absolute-path>] [--bin-dir <absolute-path>]
             [--runtime-store <absolute-path>]
             [--runtime-directory <absolute-path>]
             [--cache-root <absolute-path>]
             [--enable-launchd <0-or-1>]
             [--enable-app-server <0-or-1>]
             [--refresh-app-server]
             [--app-server-tools <comma-separated-tool-names>]
             [--idea-home <absolute-app-or-contents-path>]
             [--repository <owner/name>]
             [--release-base-url <https-or-file-url>]
             [--assets-directory <absolute-path>]
             [--migrate-configuration <absolute-literal-configuration-file>]
  install.sh uninstall [--installation-only] [--install-root <absolute-path>]
             [--bin-dir <absolute-path>]
             [--runtime-store <absolute-path>]
             [--runtime-directory <absolute-path>]
             [--cache-root <absolute-path>]

Defaults:
  version       latest stable GitHub release
  install-root  ${XDG_DATA_HOME:-$HOME/.local/share}/kast
  bin-dir       $HOME/.local/bin
  runtime-store <physical-release>/runtime-payloads
  runtime-dir   <physical-release>/state/run
  cache-root    <physical-release>/state/cache
  launchd       0 (direct process ownership)
  app server    1 (persistent Codex App Server integration enabled)
  server tools  query, source/semantic/impact, diagnostic, and change tools
  IDEA home     the sole IntelliJ IDEA found in standard macOS locations
  repository    amichne/kast
  release URL   https://github.com/<repository>/releases/download
  config file   <physical-release>/config/environment

--local builds the current working directory (a Kast checkout). Session mode
prints a Bash/Zsh activation file path on stdout; use:
  source "$(./install.sh --local session)"
It isolates configuration, caches and sockets and disables persistent services.
Persistent mode installs into the configured KAST_* paths and enables the
App Server login service for this workspace. This is a per-user installation,
available across sessions, not an all-users /Library/LaunchDaemons service.
Activation always disables the previous physical App Server and its login entry.
--refresh-app-server also enables the new login service for the current workspace.
Saved configuration is imported only through --migrate-configuration; an old
user-wide environment file is never implicitly selected.

Environment equivalents:
  KAST_VERSION
  KAST_INSTALL_ROOT
  KAST_BIN_DIR
  KAST_RUNTIME_STORE
  KAST_RUNTIME_DIRECTORY
  KAST_CACHE_ROOT
  KAST_ENABLE_LAUNCHD
  KAST_ENABLE_APP_SERVER
  KAST_APP_SERVER_TOOLS
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

The installer writes the six runtime settings to the config file as literal
KEY=value records. Edit that file after installation or override any setting
in the process environment; process values take precedence.

uninstall and --installation-only remove only the current manifest-owned release
through its bounded installation lifecycle command. Exact registered workers and
service ownership must be retired before state, configuration, and payload are
removed. Unproven legacy installations require explicit migration.

--purge-existing performs that same bounded removal after release verification.
The shared activation.lock remains in place and historical releases are retained.

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

validate_enable_app_server() {
  case "$1" in
    0|1) ;;
    *) fail "enable app server must be 0 or 1: $1" ;;
  esac
}

validate_app_server_tools() {
  local raw="$1"
  local tool
  local admitted=","
  [[ -n "$raw" ]] || fail "app server tools must not be empty"
  IFS=',' read -r -a configured_tools <<< "$raw"
  for tool in "${configured_tools[@]}"; do
    [[ -n "$tool" ]] || fail "app server tools contain an empty name"
    case "$tool" in
      query|symbol_lookup|symbol_inspect|source_read|semantic_query|impact_analyze|diagnostic_check|change_plan|change_apply|change_recover) ;;
      *) fail "app server tools contain an unknown name: $tool" ;;
    esac
    case "$admitted" in
      *",$tool,"*) fail "app server tools repeat a name: $tool" ;;
    esac
    admitted+="$tool,"
  done
}

canonical_app_server_tools() {
  local raw=",$1,"
  local tool
  local selected=()
  for tool in query symbol_lookup symbol_inspect source_read semantic_query impact_analyze \
    diagnostic_check change_plan change_apply change_recover; do
    case "$raw" in
      *",$tool,"*) selected+=("$tool") ;;
    esac
  done
  local IFS=','
  printf '%s\n' "${selected[*]}"
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

remove_selected_installation() {
  require_command python3
  require_absolute_path "install root" "$install_root"
  [[ -e "$install_root" || -L "$install_root" ]] || return 0
  python3 - "$install_root" <<'PYTHON_REMOVE'
import hashlib, json, os, pathlib, stat, sys
outer = pathlib.Path(sys.argv[1])
def reject():
    raise SystemExit("kast-install: selected installation has no admitted immutable ownership; explicit migration is required")
try:
    if outer.resolve(strict=True) != outer or not outer.is_dir(): reject()
    current = outer / "current"
    if not current.is_symlink(): reject()
    root = current.resolve(strict=True)
    if root.parent != outer / "versions" or os.readlink(current) != "versions/" + root.name: reject()
    manifest = root / "installation.json"
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 1024 * 1024: reject()
    document = json.loads(manifest.read_text())
    if document.get("schemaVersion") != 1 or document.get("installationRoot") != str(root): reject()
    expected = document.get("payloadFiles")
    if not isinstance(expected, list) or not expected or len(expected) > 4096: reject()
    observed, size = [], 0
    for directory in ("bin", "lib", "share"):
        if (root / directory).is_symlink(): reject()
        for candidate in sorted((root / directory).rglob("*")):
            if candidate.is_symlink(): reject()
            if candidate.is_dir(): continue
            if not candidate.is_file() or len(observed) >= 4096: reject()
            size += candidate.stat().st_size
            if size > 1024 * 1024 * 1024: reject()
            digest = hashlib.sha256()
            with candidate.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
            observed.append({"path": candidate.relative_to(root).as_posix(),
                             "sha256": "sha256:" + digest.hexdigest(), "mode": stat.S_IMODE(candidate.stat().st_mode)})
    if observed != expected: reject()
    script = root / "share/kast/installation-lifecycle.py"
    if not any(item["path"] == "share/kast/installation-lifecycle.py" for item in observed): reject()
    # The admitted lifecycle command takes the shared stable activation.lock before any mutation.
    os.execv(sys.executable, [sys.executable, str(script), "--installation", str(root), "remove"])
except (OSError, ValueError, TypeError, KeyError):
    reject()
PYTHON_REMOVE
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
      KAST_ENABLE_APP_SERVER=*)
        [[ "$persisted_enable_app_server_set" == false ]] ||
          fail "runtime configuration repeats KAST_ENABLE_APP_SERVER"
        persisted_enable_app_server="${line#*=}"
        persisted_enable_app_server_set=true
        ;;
      KAST_APP_SERVER_TOOLS=*)
        [[ "$persisted_app_server_tools_set" == false ]] ||
          fail "runtime configuration repeats KAST_APP_SERVER_TOOLS"
        persisted_app_server_tools="${line#*=}"
        persisted_app_server_tools_set=true
        ;;
      KAST_INDEXER_MAX_HEAP=*)
        [[ "$persisted_indexer_max_heap_set" == false ]] ||
          fail "runtime configuration repeats KAST_INDEXER_MAX_HEAP"
        persisted_indexer_max_heap="${line#*=}"
        persisted_indexer_max_heap_set=true
        ;;
      # Additional declared keys are retained in the literal source and admitted against the
      # selected payload's generated catalogue once that payload is available.
      *) [[ "$line" == *=* ]] || fail "runtime configuration has a malformed record" ;;
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
    printf '%s\n' '# 0 disables the Codex App Server integration; 1 enables it.'
    printf 'KAST_ENABLE_APP_SERVER=%s\n' "$enable_app_server"
    printf '%s\n' '# Exact comma-separated App Server tool subset in canonical order.'
    printf 'KAST_APP_SERVER_TOOLS=%s\n' "$app_server_tools"
    if [[ "$indexer_max_heap_set" == true ]]; then
      printf 'KAST_INDEXER_MAX_HEAP=%s\n' "$indexer_max_heap"
    fi
  } > "$staged_configuration"
  # Validate the original source too: an explicit invalid saved value cannot disappear beneath
  # a process override while the final candidate is assembled.
  if [[ -n "$migration_configuration" ]]; then
    env -i HOME="$HOME" PATH="$PATH" JAVA_HOME="$java_home" \
      "$target_root/bin/kast-complete" config validate --file "$migration_configuration" --json 9>&- >/dev/null
  fi
  # The original seven CLI options retain their existing precedence in the base candidate.
  # Every other saved setting is selected from the staged typed catalogue, never a shell key list.
  python3 - "$target_root/share/kast/configuration-schema.json" "$staged_configuration" "$migration_configuration" <<'PYTHON_CONFIGURATION'
import json, os, pathlib, re, sys

def reject():
    raise SystemExit("kast-install: saved configuration catalogue projection rejected")

def literal(path):
    source = pathlib.Path(path)
    if source.is_symlink() or not source.is_file() or source.stat().st_size > 65536:
        reject()
    raw = source.read_bytes()
    if len(raw) > 65536:
        reject()
    result = {}
    for line in raw.decode("utf-8", errors="strict").split("\n"):
        if not line or line.startswith("#"):
            continue
        if "=" not in line or "\x00" in line or "\r" in line:
            reject()
        key, value = line.split("=", 1)
        if key in result or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            reject()
        result[key] = value
        if len(result) > 256:
            reject()
    return result

try:
    catalogue = pathlib.Path(sys.argv[1])
    if catalogue.is_symlink() or not catalogue.is_file() or catalogue.stat().st_size > 262144:
        reject()
    parameters = json.loads(catalogue.read_text(encoding="utf-8"))["parameters"]
    if not isinstance(parameters, list) or not 1 <= len(parameters) <= 256:
        reject()
    declarations = {}
    for parameter in parameters:
        key = parameter["key"]
        if not isinstance(key, str) or key in declarations or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            reject()
        declarations[key] = parameter
    allowed = {key for key, declaration in declarations.items()
               if declaration.get("mutability") == "USER_SETTING" and "SAVED_INSTALLATION" in declaration.get("sources", [])}
    candidate = literal(sys.argv[2])
    migrated = literal(sys.argv[3]) if sys.argv[3] else {}
    if not set(candidate).issubset(allowed) or not set(migrated).issubset(allowed):
        reject()
    for key in sorted(allowed - candidate.keys()):
        if key in os.environ:
            candidate[key] = os.environ[key]
        elif key in migrated:
            candidate[key] = migrated[key]
    content = "# Kast literal configuration; process assignments take precedence.\n" + "".join(
        key + "=" + value + "\n" for key, value in candidate.items())
    if len(content.encode("utf-8")) > 65536 or any("\n" in value or "\r" in value or "\x00" in value for value in candidate.values()):
        reject()
    pathlib.Path(sys.argv[2]).write_text(content, encoding="utf-8")
except (OSError, UnicodeError, ValueError, KeyError, TypeError):
    reject()
PYTHON_CONFIGURATION
  chmod 600 "$staged_configuration"
  mv -f "$staged_configuration" "$path"
  staged_configuration=""
}

install_complete_launcher() {
  local root="$1"
  local java_executable="$2"
  local java_home="$3"
  local config_file="$4"
  local executable_name="${5:-kast}"
  local launcher="$root/bin/$executable_name-complete"
  staged_launcher="$(mktemp "$root/bin/.$executable_name-complete.XXXXXX")"
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
    if [[ "$executable_name" == kast ]]; then
      cat <<'INSTALLATION_DISPATCH'
if [ "${1-}" = installation ]; then
  shift
  installation_root="$(dirname -- "$script_dir")"
  lifecycle="$installation_root/share/kast/installation-lifecycle.py"
  if [ ! -f "$lifecycle" ] || [ -L "$lifecycle" ]; then
    echo "kast: installation lifecycle boundary is unavailable" >&2
    exit 1
  fi
  exec python3 "$lifecycle" --installation "$installation_root" "$@"
fi
INSTALLATION_DISPATCH
    fi
    printf 'config_file=%s\n' "$(shell_single_quote "$config_file")"
    cat <<'LAUNCHER_CONFIGURATION'
if [ -n "${KAST_CONFIGURATION_FILE+x}" ] && [ "$KAST_CONFIGURATION_FILE" != "$config_file" ]; then
  export KAST_SAVED_CONFIGURATION_FAILURE=configuration-selector-conflict
fi
export KAST_CONFIGURATION_FILE="$config_file"
LAUNCHER_CONFIGURATION
    # Releases before the integration entry point cannot consume typed saved-config failures.
    # Keep their admission fail-closed instead of forwarding an unknown environment field.
    if [[ ! -x "$root/bin/kast-codex" ]]; then
      printf '%s\n' 'if [ -n "${KAST_SAVED_CONFIGURATION_FAILURE+x}" ]; then'
      printf '%s\n' '  echo "kast: saved runtime configuration rejected" >&2' '  exit 1' 'fi'
    fi
    printf '%s\n' "runtime_archive=\"\$script_dir/../share/kast/runtime/$runtime_name\""
    printf 'control_executable="$script_dir/%s"\n' "$executable_name"
    printf '%s\n' 'if [ ! -x "$control_executable" ] || [ ! -f "$runtime_archive" ]; then'
    printf '%s\n' '  echo "kast: installed control or sidecar payload is missing" >&2'
    printf '%s\n' '  exit 1'
    printf '%s\n' 'fi'
    printf '%s\n' 'if [ -z "${KAST_GRADLE_JAVA_HOME+x}" ] && [ -n "${JAVA_HOME:-}" ]; then'
    printf '%s\n' '  export KAST_GRADLE_JAVA_HOME="${JAVA_HOME}"' 'fi'
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
  local version_output link payload_directory

  [[ -x "$root/bin/kast" ]] || fail "control archive has no executable bin/kast"
  if [[ -e "$root/bin/kast-codex" || -L "$root/bin/kast-codex" ]]; then
    [[ -f "$root/bin/kast-codex" && -x "$root/bin/kast-codex" ]] ||
      fail "control archive has an invalid bin/kast-codex"
  fi
  [[ -f "$root/share/kast/semantic-runtime.json" ]] ||
    fail "control archive has no semantic-runtime manifest"
  [[ -f "$root/share/kast/operation-registry.json" ]] ||
    fail "control archive has no operation registry"
  [[ -f "$root/share/kast/wire-schema.json" ]] ||
    fail "control archive has no wire schema"

  for payload_directory in bin lib share; do
    [[ -e "$root/$payload_directory" ]] || continue
    link="$(find "$root/$payload_directory" -type l -print -quit)"
    [[ -z "$link" ]] || fail "control payload contains a symbolic link: $link"
  done
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

if [[ ${1:-} == --local ]]; then
  installer_directory="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
  shift
  exec bash "$installer_directory/packaging/install-checkout.sh" "$installer_directory/install.sh" "$@"
fi

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
persisted_enable_app_server=""
persisted_enable_app_server_set=false
persisted_app_server_tools=""
persisted_app_server_tools_set=false
persisted_indexer_max_heap=""
persisted_indexer_max_heap_set=false
migration_configuration="${KAST_INSTALL_MIGRATE_CONFIGURATION:-}"
configuration_arguments=("$@")
for ((configuration_index=0; configuration_index<${#configuration_arguments[@]}; configuration_index++)); do
  if [[ "${configuration_arguments[$configuration_index]}" == --migrate-configuration ]]; then
    (( configuration_index + 1 < ${#configuration_arguments[@]} )) || fail "--migrate-configuration requires a path"
    migration_configuration="${configuration_arguments[$((configuration_index + 1))]}"
  fi
done
if [[ -n "$migration_configuration" ]]; then
  require_absolute_path "migration configuration" "$migration_configuration"
  [[ -f "$migration_configuration" && ! -L "$migration_configuration" ]] || fail "migration configuration must be a regular file"
  load_persisted_runtime_configuration "$migration_configuration"
fi

if [[ -n "${KAST_INDEXER_MAX_HEAP+x}" ]]; then
  indexer_max_heap="$KAST_INDEXER_MAX_HEAP"
  indexer_max_heap_set=true
else
  indexer_max_heap="$persisted_indexer_max_heap"
  indexer_max_heap_set="$persisted_indexer_max_heap_set"
fi

action="install"
installation_only=false
purge_existing=false
refresh_app_server=false
version="${KAST_VERSION:-}"
repository="${KAST_REPOSITORY:-$DEFAULT_REPOSITORY}"
release_base_url="${KAST_RELEASE_BASE_URL:-}"
assets_directory=""
install_root="${KAST_INSTALL_ROOT:-$default_install_root}"
bin_dir="${KAST_BIN_DIR:-${HOME}/.local/bin}"
runtime_store_explicit=false
runtime_directory_explicit=false
cache_root_explicit=false
if [[ ${KAST_RUNTIME_STORE+x} == x ]]; then
  runtime_store="$KAST_RUNTIME_STORE"
  runtime_store_explicit=true
elif [[ "$persisted_runtime_store_set" == true ]]; then
  runtime_store="$persisted_runtime_store"
  runtime_store_explicit=true
else
  runtime_store="$runtime_cache_root/semantic-runtimes"
fi
if [[ ${KAST_RUNTIME_DIRECTORY+x} == x ]]; then
  runtime_directory="$KAST_RUNTIME_DIRECTORY"
  runtime_directory_explicit=true
elif [[ "$persisted_runtime_directory_set" == true ]]; then
  runtime_directory="$persisted_runtime_directory"
  runtime_directory_explicit=true
else
  runtime_directory="$default_runtime_directory"
fi
if [[ ${KAST_CACHE_ROOT+x} == x ]]; then
  cache_root="$KAST_CACHE_ROOT"
  cache_root_explicit=true
elif [[ "$persisted_cache_root_set" == true ]]; then
  cache_root="$persisted_cache_root"
  cache_root_explicit=true
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
if [[ ${KAST_ENABLE_APP_SERVER+x} == x ]]; then
  enable_app_server="$KAST_ENABLE_APP_SERVER"
elif [[ "$persisted_enable_app_server_set" == true ]]; then
  enable_app_server="$persisted_enable_app_server"
else
  enable_app_server=1
fi
if [[ ${KAST_APP_SERVER_TOOLS+x} == x ]]; then
  app_server_tools="$KAST_APP_SERVER_TOOLS"
elif [[ "$persisted_app_server_tools_set" == true ]]; then
  app_server_tools="$persisted_app_server_tools"
else
  app_server_tools="query,source_read,semantic_query,impact_analyze,diagnostic_check,change_plan,change_apply,change_recover"
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
enable_app_server_option_set=false
app_server_tools_option_set=false
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
    --refresh-app-server)
      refresh_app_server=true
      shift
      ;;
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
      runtime_store_explicit=true
      shift 2
      ;;
    --runtime-directory)
      [[ $# -ge 2 ]] || fail "--runtime-directory requires a value"
      runtime_directory="$2"
      runtime_directory_explicit=true
      shift 2
      ;;
    --cache-root)
      [[ $# -ge 2 ]] || fail "--cache-root requires a value"
      cache_root="$2"
      cache_root_explicit=true
      shift 2
      ;;
    --enable-launchd)
      [[ $# -ge 2 ]] || fail "--enable-launchd requires a value"
      enable_launchd="$2"
      enable_launchd_option_set=true
      shift 2
      ;;
    --enable-app-server)
      [[ $# -ge 2 ]] || fail "--enable-app-server requires a value"
      enable_app_server="$2"
      enable_app_server_option_set=true
      shift 2
      ;;
    --app-server-tools)
      [[ $# -ge 2 ]] || fail "--app-server-tools requires a value"
      app_server_tools="$2"
      app_server_tools_option_set=true
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
    --migrate-configuration)
      [[ $# -ge 2 ]] || fail "--migrate-configuration requires a path"
      shift 2
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$refresh_app_server" == false || "$action" == install ]] ||
  fail "--refresh-app-server is valid only with install"

[[ "$idea_home_option_set" == false || -n "$idea_home" ]] ||
  fail "--idea-home must not be empty"

if [[ "$action" == "uninstall" ]]; then
  [[ "$purge_existing" == false ]] ||
    fail "--purge-existing is valid only with install"
  [[ -z "$assets_directory" ]] || fail "--assets-directory is valid only with install"
  [[ "$version_option_set" == false && "$repository_option_set" == false && \
    "$release_base_url_option_set" == false && "$enable_launchd_option_set" == false && \
    "$enable_app_server_option_set" == false && "$app_server_tools_option_set" == false && \
    "$idea_home_option_set" == false ]] ||
    fail "install configuration options are valid only with install"
  remove_selected_installation
  note "removed the selected manifest-owned Kast release"
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
require_command python3
require_command sleep

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
validate_enable_app_server "$enable_app_server"
[[ "$refresh_app_server" == false || "$enable_app_server" == 1 ]] ||
  fail "--refresh-app-server requires app server enablement"
if [[ "$indexer_max_heap_set" == true ]]; then
  require_literal_configuration_value "sidecar heap" "$indexer_max_heap"
fi
require_literal_configuration_value "app server tools" "$app_server_tools"
validate_app_server_tools "$app_server_tools"
app_server_tools="$(canonical_app_server_tools "$app_server_tools")"
runtime_socket_directory="$(runtime_socket_directory_for "$runtime_directory")"
default_runtime_socket_directory="$(
  runtime_socket_directory_for "$default_runtime_directory"
)"


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
activation_state="unstarted"
prior_current=""
prior_command=""
prior_codex_command=""
prior_configuration="absent"
staged_link=""
activation_control_open=false

acquire_activation_lock() {
  local lock_path="$install_root/activation.lock"
  [[ ! -L "$lock_path" ]] || fail "activation lock must not be a symlink"
  [[ ! -e "$lock_path" || -f "$lock_path" ]] || fail "activation lock must be a regular file"
  local prior_umask
  prior_umask="$(umask)"
  umask 077
  exec 9<>"$lock_path"
  umask "$prior_umask"
  activation_control_open=true
  # flock belongs to this shared open file description. Python admits/acquires it;
  # the shell retains descriptor 9 until activation/rollback completes.
  python3 - "$lock_path" <<'PYTHON_LOCK' || fail "activation lock ownership was not established"
import fcntl, os, pathlib, stat, sys, time
path = pathlib.Path(sys.argv[1])
root = path.parent
root_identity = root.lstat()
if root.resolve() != root or root_identity.st_uid != os.getuid() or stat.S_IMODE(root_identity.st_mode) & 0o022:
    sys.exit(1)
identity = os.fstat(9)
current = path.lstat()
if (not stat.S_ISREG(identity.st_mode) or identity.st_uid != os.getuid()
        or stat.S_IMODE(identity.st_mode) != 0o600
        or (identity.st_dev, identity.st_ino) != (current.st_dev, current.st_ino)):
    sys.exit(1)
deadline = time.monotonic() + 30
while True:
    try:
        fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        if time.monotonic() >= deadline:
            sys.exit(1)
        time.sleep(0.05)
current = path.lstat()
if (current.st_dev, current.st_ino) != (identity.st_dev, identity.st_ino):
    sys.exit(1)
PYTHON_LOCK
}

write_installation_manifest() {
python3 - "$target_root" "$version" "$control_digest" "$runtime_digest" "$payload_digest" \
  "$install_root" "$bin_dir" "$HOME" "${CODEX_HOME:-$HOME/.codex}" "$1" "$2" <<'PYTHON_MANIFEST'
import hashlib, json, os, pathlib, sys, tempfile
root = pathlib.Path(sys.argv[1])
payload_root = pathlib.Path(sys.argv[10])
manifest_mode = sys.argv[11]
version, control, runtime, payload = sys.argv[2:6]
install, bin_dir, home, codex_home = map(pathlib.Path, sys.argv[6:10])
if not codex_home.is_absolute():
    raise SystemExit("kast-install: declared Codex home must be absolute")
label = "io.github.amichne.kast.broker." + hashlib.sha256(str(root).encode()).hexdigest()[:32]
anchors = [
    {"kind": "current", "path": str(install / "current"), "expectedLinkTarget": "versions/" + root.name},
    {"kind": "command", "path": str(bin_dir / "kast"), "expectedLinkTarget": str(install / "current/bin/kast-complete"), "requiresCurrentTarget": "versions/" + root.name},
    {"kind": "codex-command", "path": str(bin_dir / "kast-codex"), "expectedLinkTarget": str(install / "current/bin/kast-codex-complete"), "requiresCurrentTarget": "versions/" + root.name},
    {"kind": "login", "path": str(home / "Library/LaunchAgents" / (label + ".login.plist")), "expectedExecutable": str(root / "bin/kast"), "expectedLabel": label + ".login"},
]
run = root / "state/run"
if len(str(run / ("kast-" + "0" * 43 + ".sock")).encode()) >= 104:
    anchors.append({"kind": "socket-alias", "path": "/tmp/kast-uds-" + hashlib.sha256(str(run).encode()).hexdigest()[:32],
                    "expectedLinkTarget": str(run), "identityReceipt": str(run / "endpoint-alias.json")})
for anchor in anchors:
    anchor["ownership"] = "declared-not-observed"
payload_files = []
for directory in ("bin", "lib", "share"):
    for candidate in sorted((payload_root / directory).rglob("*")):
        if candidate.is_symlink():
            raise SystemExit("kast-install: payload link identity is not admitted")
        if candidate.is_file():
            digest = hashlib.sha256()
            with candidate.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            payload_files.append({"path": candidate.relative_to(payload_root).as_posix(),
                                  "sha256": "sha256:" + digest.hexdigest(), "mode": candidate.stat().st_mode & 0o777})
document = {
    "schemaVersion": 1, "semanticVersion": version, "installationRoot": str(root),
    "payloadIdentity": "sha256:" + payload, "controlSha256": "sha256:" + control,
    "runtimeSha256": "sha256:" + runtime, "codexHome": str(codex_home),
    "configuration": str(root / "config/environment"), "workspaceRegistry": str(root / "config/workspaces.json"),
    "stateRoot": str(root / "state"), "externalAnchors": anchors, "payloadFiles": payload_files,
    "retention": {"payload": "until-explicit-uninstall", "config": "until-explicit-uninstall",
                  "state": "after-exact-process-retirement", "externalAnchors": "after-live-identity-match"},
}
path = payload_root / "installation.json"
if path.is_symlink() or (path.exists() and json.loads(path.read_text()) != document):
    raise SystemExit("kast-install: existing installation manifest identity differs")
if not path.exists():
    if manifest_mode != "create":
        raise SystemExit("kast-install: existing payload has no ownership manifest")
    fd, temporary = tempfile.mkstemp(prefix=".installation-", dir=payload_root)
    with os.fdopen(fd, "w") as output:
        json.dump(document, output, sort_keys=True, separators=(",", ":"))
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
PYTHON_MANIFEST
}

# The supported host is macOS. BSD mv -h replaces the link itself atomically;
# the destination must never be followed into the selected version directory.
replace_managed_link() {
  local destination="$1"
  local target="$2"
  staged_link="$(mktemp "${destination}.XXXXXX")"
  rm -f "$staged_link"
  ln -s "$target" "$staged_link"
  mv -f -h "$staged_link" "$destination"
  staged_link=""
}

restore_activation() {
  if [[ -n "$prior_current" ]]; then
    replace_managed_link "$current_link" "$prior_current"
  else
    rm -f "$current_link"
  fi
  if [[ -n "$prior_command" ]]; then
    replace_managed_link "$command_link" "$prior_command"
  else
    rm -f "$command_link"
  fi
  if [[ -n "$prior_codex_command" ]]; then
    replace_managed_link "$codex_command_link" "$prior_codex_command"
  else
    rm -f "$codex_command_link"
  fi
  if [[ "$prior_configuration" == "present" ]]; then
    staged_configuration="$(mktemp "$config_root/.environment-restore.XXXXXX")"
    cp -p "$temporary_root/prior-environment" "$staged_configuration"
    mv -f "$staged_configuration" "$config_file"
    staged_configuration=""
  else
    rm -f "$config_file"
  fi
  warning "activation failed; restored the prior command and configuration"
}

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ "$activation_state" == "pending" ]]; then
    restore_activation
  fi
  if [[ "$activation_control_open" == true ]]; then
    exec 9>&-
    activation_control_open=false
  fi
  rm -rf "$temporary_root"
  if [[ -n "$staged_root" && -e "$staged_root" ]]; then
    rm -rf "$staged_root"
  fi
  [[ -z "$staged_runtime" ]] || rm -f "$staged_runtime"
  [[ -z "$staged_runtime_checksum" ]] || rm -f "$staged_runtime_checksum"
  [[ -z "$staged_launcher" ]] || rm -f "$staged_launcher"
  [[ -z "$staged_configuration" ]] || rm -f "$staged_configuration"
  [[ -z "$staged_link" ]] || rm -f "$staged_link"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

archive="$temporary_root/$control_name"
checksum="$temporary_root/$control_name.sha256"
listing="$temporary_root/control.list"
runtime_archive="$temporary_root/$runtime_name"
runtime_checksum="$temporary_root/$runtime_name.sha256"
runtime_listing="$temporary_root/runtime.list"

note "preparing the matched Kast $version control and private semantic runtime"
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

[[ "$refresh_app_server" == false || -x "$verified_root/bin/kast-codex" ]] ||
  fail "the requested payload does not support App Server integration"

if [[ "$purge_existing" == true ]]; then
  remove_selected_installation
fi

versions_root="$install_root/versions"
[[ ! -L "$install_root" ]] || fail "installation root must not be a symlink"
mkdir -p "$install_root"
install_root="$(CDPATH='' cd -- "$install_root" && pwd -P)"
versions_root="$install_root/versions"
payload_digest="$(printf '%s\n%s\n' "$control_digest" "$runtime_digest" | shasum -a 256 | awk '{print $1}')"
release_directory="$version-$payload_digest"
target_root="$versions_root/$release_directory"
current_link="$install_root/current"
command_link="$bin_dir/kast"
codex_command_link="$bin_dir/kast-codex"
acquire_activation_lock
[[ ! -L "$versions_root" ]] || fail "versions directory must not be a symlink"
mkdir -p "$versions_root" "$bin_dir"
config_root="$target_root/config"
config_file="$config_root/environment"
[[ "$runtime_store_explicit" == true ]] || runtime_store="$target_root/runtime-payloads"
[[ "$runtime_directory_explicit" == true ]] || runtime_directory="$target_root/state/run"
[[ "$cache_root_explicit" == true ]] || cache_root="$target_root/state/cache"
[[ "$runtime_store" == "$target_root/runtime-payloads" ]] || fail "runtime store must remain in the selected physical release"
[[ "$runtime_directory" == "$target_root/state/run" ]] || fail "runtime directory must remain in the selected physical release"
[[ "$cache_root" == "$target_root/state/cache" ]] || fail "cache root must remain in the selected physical release"

if [[ -e "$target_root" || -L "$target_root" ]]; then
  [[ -d "$target_root" && ! -L "$target_root" ]] ||
    fail "existing version path is not a directory: $target_root"
  [[ -f "$target_root/.kast-control-sha256" && ! -L "$target_root/.kast-control-sha256" ]] ||
    fail "existing version has no control identity: $target_root"
  [[ "$(< "$target_root/.kast-control-sha256")" == "$control_digest" ]] ||
    fail "existing version does not match the immutable release: $target_root"
  write_installation_manifest "$target_root" verify
  verify_control_root "$target_root" "$version" "$java_executable" "$java_home" 9>&-
  [[ -f "$target_root/.kast-runtime-sha256" && ! -L "$target_root/.kast-runtime-sha256" ]] ||
    fail "existing version has no sidecar identity: $target_root"
  [[ "$(< "$target_root/.kast-runtime-sha256")" == "$runtime_digest" ]] ||
    fail "existing version sidecar does not match the immutable release: $target_root"
  [[ -f "$target_root/share/kast/runtime/$runtime_name" &&
     "$(shasum -a 256 "$target_root/share/kast/runtime/$runtime_name" | awk '{print $1}')" == "$runtime_digest" &&
     -x "$target_root/bin/kast-complete" ]] || fail "existing immutable payload is incomplete or changed"
else
  staged_root="$(mktemp -d "$versions_root/.install-${version}.XXXXXX")"
  tar -xzf "$archive" -C "$staged_root"
  verify_control_root "$staged_root" "$version" "$java_executable" "$java_home" 9>&-
  install_runtime_archive "$staged_root" "$runtime_archive" "$runtime_checksum" "$runtime_digest"
  install_complete_launcher \
    "$staged_root" "$java_executable" "$java_home" "$config_file"
  if [[ -x "$staged_root/bin/kast-codex" ]]; then
    install_complete_launcher "$staged_root" "$java_executable" "$java_home" "$config_file" kast-codex
  fi
  printf '%s\n' "$control_digest" > "$staged_root/.kast-control-sha256"
  printf '%s\n' "$runtime_digest" > "$staged_root/.kast-runtime-sha256"
  write_installation_manifest "$staged_root" create
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

if [[ -e "$codex_command_link" || -L "$codex_command_link" ]]; then
  [[ -L "$codex_command_link" ]] || fail "command path already exists and is not managed: $codex_command_link"
  prior_codex_command="$(readlink "$codex_command_link")"
  case "$prior_codex_command" in
    "$install_root/current/bin/kast-codex-complete"|"$install_root/versions/"*/bin/kast-codex-complete) ;;
    *) fail "command path is owned by another installation: $codex_command_link" ;;
  esac
fi

if [[ -e "$config_file" || -L "$config_file" ]]; then
  [[ -f "$config_file" && ! -L "$config_file" ]] ||
    fail "existing configuration is not a regular file: $config_file"
  cp -p "$config_file" "$temporary_root/prior-environment"
  prior_configuration="present"
fi

if [[ -n "$prior_current" ]]; then
  prior_physical_root="$(CDPATH='' cd -- "$current_link" && pwd -P)" ||
    fail "previous physical installation is unavailable"
  case "$prior_physical_root" in
    "$versions_root/"*) ;;
    *) fail "previous physical installation is outside the owned versions directory" ;;
  esac
  [[ -x "$prior_physical_root/bin/kast-complete" && -f "$prior_physical_root/.kast-control-sha256" &&
     -f "$prior_physical_root/.kast-runtime-sha256" ]] ||
    fail "previous installation identity is incomplete"
  prior_codex_home="$(python3 - "$prior_physical_root" <<'PYTHON_PRIOR'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = root / "installation.json"
if not manifest.is_file() or manifest.is_symlink():
    raise SystemExit("kast-install: previous installation has no admitted ownership manifest")
document = json.loads(manifest.read_text())
if document.get("schemaVersion") != 1 or document.get("installationRoot") != str(root):
    raise SystemExit("kast-install: previous installation root identity differs")
expected = document.get("payloadFiles")
if not isinstance(expected, list) or not expected:
    raise SystemExit("kast-install: previous payload ownership is incomplete")
observed = []
for directory in ("bin", "lib", "share"):
    for candidate in sorted((root / directory).rglob("*")):
        if candidate.is_symlink():
            raise SystemExit("kast-install: previous payload has an unproven link")
        if candidate.is_file():
            digest = hashlib.sha256()
            with candidate.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            observed.append({"path": candidate.relative_to(root).as_posix(),
                             "sha256": "sha256:" + digest.hexdigest(), "mode": candidate.stat().st_mode & 0o777})
if observed != expected:
    raise SystemExit("kast-install: previous payload bytes no longer establish ownership")
codex_home = document.get("codexHome")
if not isinstance(codex_home, str) or "\n" in codex_home or not pathlib.Path(codex_home).is_absolute():
    raise SystemExit("kast-install: previous Codex profile identity is incomplete")
print(codex_home)
PYTHON_PRIOR
)"
  note "retiring the previous physical App Server and its login entry before activation"
  env -u KAST_RUNTIME_STORE -u KAST_RUNTIME_DIRECTORY -u KAST_CACHE_ROOT \
    -u KAST_ENABLE_LAUNCHD -u KAST_ENABLE_APP_SERVER -u KAST_APP_SERVER_TOOLS \
    -u KAST_CONFIGURATION_FILE -u KAST_SAVED_CONFIGURATION_FAILURE \
    CODEX_HOME="$prior_codex_home" "$prior_physical_root/bin/kast-complete" app-server disable 9>&-
fi

activation_state="pending"
install_runtime_configuration "$config_file"

env -i HOME="$HOME" PATH="$PATH" JAVA_HOME="$java_home" \
  "$target_root/bin/kast-complete" config validate --file "$config_file" --json 9>&- >/dev/null
replace_managed_link "$current_link" "versions/$release_directory"
replace_managed_link "$command_link" "$install_root/current/bin/kast-complete"
if [[ -x "$target_root/bin/kast-codex" ]]; then
  replace_managed_link "$codex_command_link" "$install_root/current/bin/kast-codex-complete"
else
  rm -f "$codex_command_link"
fi

verify_control_root "$target_root" "$version" "$java_executable" "$java_home" 9>&-
env -u KAST_CONFIGURATION_FILE -u KAST_SAVED_CONFIGURATION_FAILURE "$command_link" --version 9>&- >/dev/null
activation_state="committed"

if [[ "$refresh_app_server" == true ]]; then
  note "enabling the installed App Server login service for the current workspace"
  env -u KAST_RUNTIME_STORE -u KAST_RUNTIME_DIRECTORY -u KAST_CACHE_ROOT \
    -u KAST_ENABLE_LAUNCHD -u KAST_ENABLE_APP_SERVER -u KAST_APP_SERVER_TOOLS \
    -u KAST_CONFIGURATION_FILE -u KAST_SAVED_CONFIGURATION_FAILURE \
    "$command_link" app-server enable 9>&- ||
    fail "Kast is installed, but App Server enablement failed; resolve the reported failure and run kast app-server enable"
fi

note "installed Kast $version"
note "command: $command_link"
if [[ -x "$target_root/bin/kast-codex" ]]; then
  note "integration command: $codex_command_link"
fi
note "configuration: $config_file"
note "private sidecar: $target_root/share/kast/runtime/$runtime_name"
info "runtime knobs: KAST_RUNTIME_STORE, KAST_RUNTIME_DIRECTORY, KAST_CACHE_ROOT, KAST_ENABLE_LAUNCHD, KAST_ENABLE_APP_SERVER, KAST_APP_SERVER_TOOLS"
case ":${PATH:-}:" in
  *":$bin_dir:"*) ;;
  *) warning "add $bin_dir to PATH" ;;
esac
