#!/usr/bin/env bash
# kast.sh - repo-local Kast build and install tooling
#
# Subcommands:
#   build    Build portable distribution artifacts  ->  dist/
#   install  Install Kast on macOS via Homebrew or Ubuntu/Debian via tarball
#   verify   Verify the installed Ubuntu/Debian tarball setup
#
# Explicit subcommand:
#   ./kast.sh build [plugin] [headless] [--all]
#   ./kast.sh install [--from PATH_OR_URL] [--version vX.Y.Z] [--skip-setup]
set -Eeuo pipefail

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

# Build paths are only meaningful when SCRIPT_DIR is set.
PLUGIN_DIST_DIR="${REPO_ROOT}/backend-idea/build/distributions"
HEADLESS_PORTABLE_DIST_DIR="${REPO_ROOT}/backend-headless/build/portable-dist/backend-headless"
HEADLESS_PORTABLE_ZIP_DIR="${REPO_ROOT}/backend-headless/build/distributions"

tmp_dir=""
_KAST_INSTALL_TMP_DIR=""
cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
  if [[ -n "$_KAST_INSTALL_TMP_DIR" && -d "$_KAST_INSTALL_TMP_DIR" ]]; then
    rm -rf "$_KAST_INSTALL_TMP_DIR"
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

die() {
  log_line "$(colorize '1;31' 'x')" "$*"
  exit 1
}

can_prompt() { [[ -r /dev/tty && -w /dev/tty ]] && { : </dev/tty >/dev/tty; } 2>/dev/null; }

# ===========================================================================
# Shared utilities
# ===========================================================================

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
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
  die "Neither sha256sum nor shasum is available"
}

_install_uname_s() {
  printf '%s\n' "${KAST_INSTALL_TEST_UNAME_S:-$(uname -s)}"
}

_install_uname_m() {
  printf '%s\n' "${KAST_INSTALL_TEST_UNAME_M:-$(uname -m)}"
}

_install_is_http_url() {
  case "$1" in
    http://*|https://*) return 0 ;;
    *) return 1 ;;
  esac
}

_install_validate_from() {
  local source="$1"
  [[ -n "$source" ]] || die "--from requires a non-empty file path or HTTP URL"
  if _install_is_http_url "$source"; then
    case "$source" in
      *\?*|*#*) die "--from URL must be an exact artifact URL without query or fragment: $source" ;;
    esac
    return
  fi
  case "$source" in
    *://*) die "--from supports only local file paths or HTTP(S) URLs: $source" ;;
  esac
  [[ -f "$source" ]] || die "--from local path does not exist or is not a file: $source"
}

_install_basename() {
  local source="$1"
  source="${source%%\?*}"
  source="${source%%#*}"
  printf '%s\n' "${source##*/}"
}

_install_parent() {
  local source="$1"
  source="${source%%\?*}"
  source="${source%%#*}"
  printf '%s\n' "${source%/*}"
}

_install_abs_path() {
  local path="$1"
  local dir
  local base
  dir="$(cd -- "$(dirname -- "$path")" && pwd)"
  base="$(basename -- "$path")"
  printf '%s/%s\n' "$dir" "$base"
}

_install_file_url() {
  local path
  path="$(_install_abs_path "$1")"
  path="${path//%/%25}"
  path="${path// /%20}"
  path="${path//#/%23}"
  path="${path//\?/%3F}"
  printf 'file://%s\n' "$path"
}

_install_ruby_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "$value"
}

_install_normalize_version() {
  local value="$1"
  printf '%s\n' "${value#v}"
}

_install_tag_version() {
  local value="$1"
  if [[ "$value" == v* ]]; then
    printf '%s\n' "$value"
  else
    printf 'v%s\n' "$value"
  fi
}

_install_semver_from_asset() {
  local asset_name="$1"
  case "$asset_name" in
    kast-v*-macos-arm64.zip)
      local version="${asset_name#kast-}"
      version="${version%-macos-arm64.zip}"
      printf '%s\n' "$version"
      ;;
    kast-v*-macos-x64.zip)
      local version="${asset_name#kast-}"
      version="${version%-macos-x64.zip}"
      printf '%s\n' "$version"
      ;;
    kast-ubuntu-debian-headless-x86_64-*.tar.gz)
      local version="${asset_name#kast-ubuntu-debian-headless-x86_64-}"
      version="${version%.tar.gz}"
      printf '%s\n' "$version"
      ;;
    *)
      return 1
      ;;
  esac
}

_install_latest_release_tag() {
  need_tool curl
  local latest_url
  latest_url="$(curl --fail --location --silent --show-error --output /dev/null --write-out '%{url_effective}' \
    https://github.com/amichne/kast/releases/latest)"
  local tag="${latest_url##*/}"
  [[ "$tag" == v* ]] || die "Could not resolve latest Kast release tag from ${latest_url}"
  printf '%s\n' "$tag"
}

_install_fetch() {
  local url="$1"
  local output_path="$2"
  need_tool curl
  curl --fail --location --retry 3 --retry-delay 2 --silent --show-error \
    --output "$output_path" \
    "$url"
}

_install_checksum_from_sums_file() {
  local sums_path="$1"
  local asset_name="$2"
  local digest
  digest="$(awk -v asset="$asset_name" '$2 == asset { print $1 }' "$sums_path")"
  [[ -n "$digest" ]] || die "SHA256SUMS does not contain ${asset_name}"
  [[ "$digest" =~ ^[0-9a-fA-F]{64}$ ]] || die "Invalid SHA-256 digest for ${asset_name}: ${digest}"
  printf '%s\n' "${digest,,}"
}

_install_sidecar_checksum() {
  local sidecar_path="$1"
  local asset_name="$2"
  local expected_digest
  local expected_name
  read -r expected_digest expected_name < "$sidecar_path"
  [[ "$expected_name" == "$asset_name" ]] \
    || die "SHA-256 sidecar names ${expected_name}, expected ${asset_name}"
  [[ "$expected_digest" =~ ^[0-9a-fA-F]{64}$ ]] || die "Invalid SHA-256 sidecar digest for ${asset_name}"
  printf '%s\n' "${expected_digest,,}"
}

_install_macos_arch() {
  case "$(_install_uname_m)" in
    arm64|aarch64) printf '%s\n' "arm64" ;;
    x86_64) printf '%s\n' "x64" ;;
    *) die "Kast macOS install supports only arm64 and x86_64 hosts; found $(_install_uname_m)" ;;
  esac
}

_install_assert_macos_cli_asset() {
  local asset_name="$1"
  local expected_arch="$(_install_macos_arch)"
  case "$asset_name" in
    kast-v*-macos-"${expected_arch}".zip) ;;
    kast-v*-macos-*.zip)
      die "--from asset ${asset_name} does not match this macOS ${expected_arch} host"
      ;;
    *)
      die "--from on macOS must point at kast-v<version>-macos-${expected_arch}.zip"
      ;;
  esac
}

_install_brew_install_or_reinstall() {
  local kind="$1"
  local token="$2"
  local source="$3"
  if [[ "$kind" == "cask" ]]; then
    if brew list --cask "$token" >/dev/null 2>&1; then
      brew reinstall --cask "$source"
    else
      brew install --cask "$source"
    fi
  else
    if brew list --formula "$token" >/dev/null 2>&1; then
      brew reinstall "$source"
    else
      brew install "$source"
    fi
  fi
}

_install_run_setup() {
  if [[ "${_KAST_INSTALL_SKIP_SETUP:-false}" == "true" ]]; then
    log_note "Skipping kast setup"
    return
  fi
  need_tool kast
  log_step "Running kast setup"
  kast setup
}

_install_write_temp_homebrew_formula() {
  local output_path="$1"
  local version="$2"
  local cli_url="$3"
  local cli_sha="$4"

  local version_escaped cli_url_escaped
  version_escaped="$(_install_ruby_escape "$version")"
  cli_url_escaped="$(_install_ruby_escape "$cli_url")"
  cat > "$output_path" <<FORMULA
# frozen_string_literal: true

class Kast < Formula
  desc "Workspace control plane for Kotlin analysis daemons"
  homepage "https://github.com/amichne/kast"
  version "${version_escaped}"
  license "Apache-2.0"

  url "${cli_url_escaped}"
  sha256 "${cli_sha}"

  def install
    bin.install "kast"
  end

  test do
    assert_match "Kast CLI #{version}", shell_output("#{bin}/kast version")
  end
end
FORMULA
}

_install_write_temp_homebrew_cask() {
  local output_path="$1"
  local version="$2"
  local plugin_url="$3"
  local plugin_sha="$4"

  local version_escaped plugin_url_escaped
  version_escaped="$(_install_ruby_escape "$version")"
  plugin_url_escaped="$(_install_ruby_escape "$plugin_url")"
  cat > "$output_path" <<CASK
# frozen_string_literal: true

artifact_version = "${version_escaped}"

jetbrains_config_root = lambda do
  Pathname.new(
    ENV.fetch(
      "KAST_JETBRAINS_CONFIG_ROOT",
      "#{Dir.home}/Library/Application Support/JetBrains",
    ),
  )
end

jetbrains_plugin_dirs = lambda do
  root = jetbrains_config_root.call
  next [] unless root.directory?

  dirs = root.children.filter_map do |path|
    next unless path.directory?

    product = path.basename.to_s
    match = product.match(/\A([A-Za-z][A-Za-z0-9]*)(\d{4})\.(\d+)(?:\.(\d+))?\z/)
    next unless match

    [
      match[1],
      match[2].to_i,
      match[3].to_i,
      (match[4] || "0").to_i,
      path/"plugins",
    ]
  end

  dirs.sort_by { |product, year, minor, patch, path| [product, -year, -minor, -patch, path.to_s] }.map(&:last)
end

cask "kast-plugin" do
  version artifact_version
  sha256 "${plugin_sha}"

  url "${plugin_url_escaped}"
  name "Kast IDEA Plugin"
  desc "JetBrains IDE plugin bundle for Kast Kotlin analysis"
  homepage "https://github.com/amichne/kast"

  stage_only true

  postflight do
    plugin_root = staged_path/"backend-idea"
    plugins_dirs = jetbrains_plugin_dirs.call

    if plugins_dirs.empty?
      opoo <<~EOS
        No JetBrains IDE config directory was found under #{jetbrains_config_root.call}.
        Launch a JetBrains IDE once, then run \`brew reinstall kast-plugin\`.
      EOS
      next
    end

    linked_dirs = []

    plugins_dirs.each do |plugins_dir|
      link_path = plugins_dir/"kast"
      FileUtils.mkdir_p plugins_dir

      if link_path.symlink?
        current = link_path.readlink.to_s
        if current == plugin_root.to_s
          linked_dirs << plugins_dir
          next
        end
        unless current.include?("/kast-plugin/")
          opoo "Not replacing existing link: #{link_path} -> #{current}"
          next
        end
        link_path.delete
      elsif link_path.exist?
        opoo "Not replacing existing path: #{link_path}"
        next
      end

      FileUtils.ln_s plugin_root, link_path
      linked_dirs << plugins_dir
    end

    if linked_dirs.empty?
      opoo "Kast plugin was not linked into any JetBrains IDE config directory"
    else
      linked_count = linked_dirs.length
      noun = (linked_count == 1) ? "directory" : "directories"
      ohai "Linked Kast plugin into #{linked_count} JetBrains IDE config #{noun}"
    end
  end

  uninstall_postflight do
    plugin_root = staged_path/"backend-idea"

    jetbrains_plugin_dirs.call.each do |plugins_dir|
      link_path = plugins_dir/"kast"
      next unless link_path.symlink?

      current = link_path.readlink.to_s
      next if current != plugin_root.to_s && current.exclude?("/Caskroom/kast-plugin/")

      link_path.delete
    end
  end
end
CASK
}

_install_macos_from_release_artifacts() {
  local source="${_KAST_INSTALL_FROM:-}"
  local requested_version="${_KAST_INSTALL_VERSION:-}"
  local arch="$(_install_macos_arch)"
  local cli_asset_name cli_url plugin_asset_name plugin_url version tag release_root cli_sha plugin_sha

  if [[ -n "$source" ]]; then
    _install_validate_from "$source"
    cli_asset_name="$(_install_basename "$source")"
    _install_assert_macos_cli_asset "$cli_asset_name"
    tag="$(_install_semver_from_asset "$cli_asset_name")"
    version="$(_install_normalize_version "$tag")"
    [[ -z "$requested_version" || "$(_install_tag_version "$requested_version")" == "$tag" ]] \
      || die "--from asset version ${tag} does not match --version $requested_version"
  else
    [[ -n "$requested_version" ]] || die "Internal error: macOS artifact install requires --from or --version"
    tag="$(_install_tag_version "$requested_version")"
    version="$(_install_normalize_version "$tag")"
    cli_asset_name="kast-${tag}-macos-${arch}.zip"
    source="https://github.com/amichne/kast/releases/download/${tag}/${cli_asset_name}"
  fi

  plugin_asset_name="kast-idea-${tag}.zip"

  if _install_is_http_url "$source"; then
    release_root="$(_install_parent "$source")"
    cli_url="$source"
    plugin_url="${release_root}/${plugin_asset_name}"
    _KAST_INSTALL_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
    local sums_path="${_KAST_INSTALL_TMP_DIR}/SHA256SUMS"
    _install_fetch "${release_root}/SHA256SUMS" "$sums_path"
    cli_sha="$(_install_checksum_from_sums_file "$sums_path" "$cli_asset_name")"
    plugin_sha="$(_install_checksum_from_sums_file "$sums_path" "$plugin_asset_name")"
  else
    local source_dir
    source_dir="$(cd -- "$(dirname -- "$source")" && pwd)"
    local cli_path="${source_dir}/${cli_asset_name}"
    local plugin_path="${source_dir}/${plugin_asset_name}"
    [[ -f "$cli_path" ]] || die "macOS CLI artifact not found: $cli_path"
    [[ -f "$plugin_path" ]] || die "Matching IDEA plugin artifact not found: $plugin_path"
    cli_url="$(_install_file_url "$cli_path")"
    plugin_url="$(_install_file_url "$plugin_path")"
    cli_sha="$(compute_sha256 "$cli_path")"
    plugin_sha="$(compute_sha256 "$plugin_path")"
  fi

  if [[ -z "$_KAST_INSTALL_TMP_DIR" ]]; then
    _KAST_INSTALL_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-install.XXXXXX")"
  fi
  local tap_dir="${_KAST_INSTALL_TMP_DIR}/homebrew-kast"
  mkdir -p "${tap_dir}/Formula" "${tap_dir}/Casks"
  local formula_path="${tap_dir}/Formula/kast.rb"
  local cask_path="${tap_dir}/Casks/kast-plugin.rb"
  _install_write_temp_homebrew_formula "$formula_path" "$version" "$cli_url" "$cli_sha"
  _install_write_temp_homebrew_cask "$cask_path" "$version" "$plugin_url" "$plugin_sha"

  need_tool brew
  log_section "Installing Kast ${tag} from release artifacts through Homebrew"
  _install_brew_install_or_reinstall "formula" "kast" "$formula_path"
  _install_brew_install_or_reinstall "cask" "kast-plugin" "$cask_path"
  _install_run_setup
}

_install_macos_default_homebrew() {
  need_tool brew
  log_section "Installing Kast through Homebrew"
  brew tap amichne/kast
  _install_brew_install_or_reinstall "formula" "kast" "amichne/kast/kast"
  _install_brew_install_or_reinstall "cask" "kast-plugin" "amichne/kast/kast-plugin"
  _install_run_setup
}

_install_macos() {
  if [[ -n "${_KAST_INSTALL_FROM:-}" || -n "${_KAST_INSTALL_VERSION:-}" ]]; then
    _install_macos_from_release_artifacts
  else
    _install_macos_default_homebrew
  fi
}

_linux_toml_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s\n' "$value"
}

_linux_read_bundle_manifest_value() {
  local manifest_path="$1"
  local field_name="$2"
  command -v python3 >/dev/null 2>&1 || return 1
  python3 - "$manifest_path" "$field_name" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
field_name = sys.argv[2]
try:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception:
    raise SystemExit(1)

if payload.get("schemaVersion") != 1:
    raise SystemExit(1)
if payload.get("kind") != "KAST_UBUNTU_DEBIAN_BUNDLE":
    raise SystemExit(1)

value = payload.get(field_name)
if not isinstance(value, str) or not value.strip():
    raise SystemExit(1)
print(value.strip())
PY
}

_linux_bundle_source_dir() {
  if [[ -n "$SCRIPT_DIR" && -f "${SCRIPT_DIR}/manifest.json" && -x "${SCRIPT_DIR}/bin/kast" ]]; then
    printf '%s\n' "$SCRIPT_DIR"
    return
  fi
  return 1
}

_linux_infer_version_from_context() {
  if [[ -n "${_KAST_INSTALL_FROM:-}" ]]; then
    _install_semver_from_asset "$(_install_basename "$_KAST_INSTALL_FROM")" && return
  fi
  local bundle_source
  if bundle_source="$(_linux_bundle_source_dir)"; then
    _linux_read_bundle_manifest_value "${bundle_source}/manifest.json" "version" && return
  fi
  return 1
}

_linux_infer_bundle_kind_from_context() {
  if [[ -n "${_KAST_INSTALL_FROM:-}" ]]; then
    case "$(_install_basename "$_KAST_INSTALL_FROM")" in
      kast-ubuntu-debian-headless-x86_64-*) printf '%s\n' "headless"; return ;;
    esac
  fi
  local bundle_source
  if bundle_source="$(_linux_bundle_source_dir)"; then
    _linux_read_bundle_manifest_value "${bundle_source}/manifest.json" "backendKind" && return
  fi
  printf '%s\n' "headless"
}

_linux_assert_host() {
  [[ "${KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK:-false}" == "true" ]] && return
  [[ "$(_install_uname_s)" == "Linux" ]] || die "This installer only supports Ubuntu/Debian Linux hosts"
  [[ "$(_install_uname_m)" == "x86_64" ]] || die "This installer only supports x86_64 hosts"

  local os_release="${KAST_INSTALL_TEST_OS_RELEASE:-/etc/os-release}"
  [[ -r "$os_release" ]] || die "Cannot verify Ubuntu/Debian host: ${os_release} is missing"
  # shellcheck disable=SC1090
  . "$os_release"
  local distro="${ID:-} ${ID_LIKE:-}"
  case "$distro" in
    *debian*|*ubuntu*) ;;
    *) die "This installer only supports Ubuntu/Debian hosts; found ID=${ID:-unknown} ID_LIKE=${ID_LIKE:-unknown}" ;;
  esac
}

_linux_copy_tree() {
  local source_dir="$1"
  local destination_dir="$2"
  mkdir -p "$destination_dir"
  tar -C "$source_dir" -cf - . | tar -C "$destination_dir" -xf -
}

_linux_write_headless_shim() {
  local output_path="$1"
  local cli_path="$2"
  local quoted_cli_path
  printf -v quoted_cli_path '%q' "$cli_path"
  mkdir -p "$(dirname -- "$output_path")"
  cat > "$output_path" <<SHIM
#!/usr/bin/env bash
set -euo pipefail

case " \${JAVA_OPTS:-} " in
  *" -Didea.force.use.core.classloader=true "*) ;;
  *) export JAVA_OPTS="\${JAVA_OPTS:+\${JAVA_OPTS} }-Didea.force.use.core.classloader=true" ;;
esac

exec ${quoted_cli_path} "\$@"
SHIM
  chmod 755 "$output_path"
}

_linux_install_kast_entrypoint() {
  local bundled_cli_path="${_KAST_LINUX_INSTALL_HOME}/bin/kast"
  local shim_cli_path="$bundled_cli_path"
  if [[ "$_KAST_LINUX_BIN_PATH" == "$bundled_cli_path" ]]; then
    shim_cli_path="${_KAST_LINUX_INSTALL_HOME}/bin/kast-cli"
    mv "$bundled_cli_path" "$shim_cli_path"
  fi
  rm -f "$_KAST_LINUX_BIN_PATH"
  _linux_write_headless_shim "$_KAST_LINUX_BIN_PATH" "$shim_cli_path"
}

_linux_write_config() {
  local normalized_version="$(_install_normalize_version "$_KAST_LINUX_VERSION")"
  local backend_config="[runtime]
defaultBackend = \"headless\"

[backends.headless]
runtimeLibsDir = \"$(_linux_toml_escape "$_KAST_LINUX_HEADLESS_RUNTIME_LIBS_DIR")\"
ideaHome = \"$(_linux_toml_escape "$_KAST_LINUX_HEADLESS_IDEA_HOME")\""
  local components='["cli", "headless-backend", "config"]'

  mkdir -p "$(dirname -- "$_KAST_LINUX_CONFIG_FILE")"
  cat > "$_KAST_LINUX_CONFIG_FILE" <<TOML
[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[paths]
installRoot = "$(_linux_toml_escape "$_KAST_LINUX_INSTALL_HOME")"
binDir = "$(_linux_toml_escape "$(dirname -- "$_KAST_LINUX_BIN_PATH")")"
libDir = "$(_linux_toml_escape "${_KAST_LINUX_INSTALL_HOME}/lib")"
cacheDir = "$(_linux_toml_escape "${_KAST_LINUX_INSTALL_HOME}/cache")"
logsDir = "$(_linux_toml_escape "${_KAST_LINUX_INSTALL_HOME}/logs")"
descriptorDir = "$(_linux_toml_escape "${_KAST_LINUX_INSTALL_HOME}/cache/daemons")"
socketDir = "$(_linux_toml_escape "${TMPDIR:-/tmp}")"

${backend_config}

[cli]
binaryPath = "$(_linux_toml_escape "$_KAST_LINUX_BIN_PATH")"

[install]
version = "$(_linux_toml_escape "$normalized_version")"
backendVersion = "$(_linux_toml_escape "$normalized_version")"
installedAt = "$(_linux_toml_escape "$_KAST_LINUX_PLATFORM"):${_KAST_LINUX_VERSION}"
platform = "$(_linux_toml_escape "$_KAST_LINUX_PLATFORM")"
components = ${components}
managedPaths = ["bin", "lib", "cache", "logs"]
shellRcPatches = []
repos = []
schemaVersion = 6
TOML
}

_linux_source_from_artifact() {
  local artifact_path="$1"
  local output_dir="$2"
  local top_level=""
  local member

  while IFS= read -r member; do
    case "$member" in
      ""|/*|../*|*/../*|*/..|.) die "Unsafe bundle archive member: ${member}" ;;
    esac
    local candidate="${member%%/*}"
    [[ -n "$candidate" ]] || die "Unsafe bundle archive member: ${member}"
    if [[ -z "$top_level" ]]; then
      top_level="$candidate"
    elif [[ "$top_level" != "$candidate" ]]; then
      die "Bundle archive must contain exactly one top-level directory"
    fi
  done < <(tar -tzf "$artifact_path")

  [[ -n "$top_level" ]] || die "Bundle archive is empty"
  mkdir -p "$output_dir"
  tar -xzf "$artifact_path" -C "$output_dir"

  local bundle_root="${output_dir}/${top_level}"
  [[ -d "$bundle_root" ]] || die "Bundle archive top-level entry is not a directory: ${top_level}"
  printf '%s\n' "$bundle_root"
}

_linux_fetch_artifact() {
  local artifact_path="$1"
  local artifact_name="$2"
  local source="${_KAST_INSTALL_FROM:-}"
  local expected_digest actual_digest

  if [[ -n "$source" ]]; then
    _install_validate_from "$source"
    [[ "$(_install_basename "$source")" == "$artifact_name" ]] \
      || die "--from asset $(_install_basename "$source") does not match expected ${artifact_name}"
    if _install_is_http_url "$source"; then
      _install_fetch "$source" "$artifact_path"
      _install_fetch "${source}.sha256" "${artifact_path}.sha256"
    else
      [[ -f "${source}.sha256" ]] || die "Missing SHA-256 sidecar: ${source}.sha256"
      cp "$source" "$artifact_path"
      cp "${source}.sha256" "${artifact_path}.sha256"
    fi
  else
    _install_fetch "${_KAST_LINUX_BASE_URL}/${artifact_name}" "$artifact_path"
    _install_fetch "${_KAST_LINUX_BASE_URL}/${artifact_name}.sha256" "${artifact_path}.sha256"
  fi

  [[ -f "${artifact_path}.sha256" ]] || die "Missing SHA-256 sidecar: ${artifact_name}.sha256"
  expected_digest="$(_install_sidecar_checksum "${artifact_path}.sha256" "$artifact_name")"
  actual_digest="$(compute_sha256 "$artifact_path")"
  [[ "$actual_digest" == "$expected_digest" ]] \
    || die "SHA-256 mismatch for ${artifact_name}"
  log_success "${artifact_name}: OK"
}

_linux_configure_paths() {
  _KAST_LINUX_VERSION="${_KAST_INSTALL_VERSION:-}"
  if [[ -z "$_KAST_LINUX_VERSION" ]]; then
    _KAST_LINUX_VERSION="$(_linux_infer_version_from_context || true)"
  fi
  if [[ -z "$_KAST_LINUX_VERSION" && -z "${_KAST_INSTALL_FROM:-}" ]]; then
    _KAST_LINUX_VERSION="$(_install_latest_release_tag)"
  fi
  [[ -n "$_KAST_LINUX_VERSION" ]] || die "Set --version or --from"
  _KAST_LINUX_VERSION="$(_install_tag_version "$_KAST_LINUX_VERSION")"
  _KAST_LINUX_NORMALIZED_VERSION="$(_install_normalize_version "$_KAST_LINUX_VERSION")"
  _KAST_LINUX_BUNDLE_KIND="$(_linux_infer_bundle_kind_from_context)"
  [[ "$_KAST_LINUX_BUNDLE_KIND" == "headless" ]] || die "Unsupported Ubuntu/Debian bundle kind: $_KAST_LINUX_BUNDLE_KIND"
  _KAST_LINUX_PLATFORM="ubuntu-debian-headless-x86_64"
  _KAST_LINUX_ARTIFACT_NAME="kast-${_KAST_LINUX_PLATFORM}-${_KAST_LINUX_VERSION}.tar.gz"
  _KAST_LINUX_ROOT_DIR="${KAST_UBUNTU_DEBIAN_ROOT:-${HOME}/.local/share/kast/ubuntu-debian}"
  _KAST_LINUX_INSTALL_HOME="${_KAST_LINUX_ROOT_DIR}/${_KAST_LINUX_VERSION}"
  _KAST_LINUX_BIN_DIR="${KAST_UBUNTU_DEBIAN_BIN_DIR:-${HOME}/.local/bin}"
  _KAST_LINUX_BIN_PATH="${_KAST_LINUX_BIN_DIR}/kast"
  _KAST_LINUX_CONFIG_HOME="${KAST_UBUNTU_DEBIAN_CONFIG_HOME:-${KAST_CONFIG_HOME:-${HOME}/.config/kast}}"
  _KAST_LINUX_CONFIG_FILE="${_KAST_LINUX_CONFIG_HOME}/config.toml"
  _KAST_LINUX_BASE_URL="https://github.com/amichne/kast/releases/download/${_KAST_LINUX_VERSION}"
  _KAST_LINUX_HEADLESS_ROOT="${_KAST_LINUX_INSTALL_HOME}/lib/backends/headless-${_KAST_LINUX_VERSION}"
  _KAST_LINUX_HEADLESS_RUNTIME_LIBS_DIR="${_KAST_LINUX_HEADLESS_ROOT}/runtime-libs"
  _KAST_LINUX_HEADLESS_IDEA_HOME="${_KAST_LINUX_HEADLESS_ROOT}/idea-home"
  _KAST_LINUX_JAVA_CMD="${KAST_JAVA_CMD:-java}"
}

_linux_detect_installed_backend_kind() {
  local source_dir="$1"
  if [[ -f "${source_dir}/lib/backends/headless-${_KAST_LINUX_VERSION}/runtime-libs/classpath.txt" ]]; then
    printf '%s\n' "headless"
    return
  fi
  die "Bundle source missing backend runtime-libs/classpath.txt for ${_KAST_LINUX_VERSION}"
}

_linux_validate_backend_source() {
  local source_dir="$1"
  local backend_kind="$2"
  [[ "$backend_kind" == "headless" ]] || die "Unsupported bundle backend kind: $backend_kind"
  [[ -f "${source_dir}/lib/backends/headless-${_KAST_LINUX_VERSION}/runtime-libs/classpath.txt" ]] \
    || die "Bundle source missing headless runtime-libs/classpath.txt"
  [[ -x "${source_dir}/lib/backends/headless-${_KAST_LINUX_VERSION}/kast-headless" ]] \
    || die "Bundle source missing headless kast-headless launcher"
  [[ -f "${source_dir}/lib/backends/headless-${_KAST_LINUX_VERSION}/idea-home/lib/nio-fs.jar" ]] \
    || die "Bundle source missing headless idea-home/lib/nio-fs.jar"
  [[ -f "${source_dir}/lib/backends/headless-${_KAST_LINUX_VERSION}/idea-home/modules/module-descriptors.dat" ]] \
    || die "Bundle source missing headless idea-home/modules/module-descriptors.dat"
  [[ -d "${source_dir}/lib/backends/headless-${_KAST_LINUX_VERSION}/idea-home/plugins/kast-headless" ]] \
    || die "Bundle source missing bundled kast-headless plugin"
}

_linux_verify_install() {
  _linux_configure_paths
  export PATH="${_KAST_LINUX_BIN_DIR}:${PATH}"
  export KAST_CONFIG_HOME="$_KAST_LINUX_CONFIG_HOME"

  need_tool "$_KAST_LINUX_JAVA_CMD"
  need_tool kast

  [[ -x "$_KAST_LINUX_BIN_PATH" ]] || die "Expected executable kast at ${_KAST_LINUX_BIN_PATH}"
  [[ -d "$_KAST_LINUX_INSTALL_HOME" ]] || die "Install root not found: ${_KAST_LINUX_INSTALL_HOME}"
  [[ -f "$_KAST_LINUX_CONFIG_FILE" ]] || die "Kast config not found: ${_KAST_LINUX_CONFIG_FILE}"
  local installed_backend_kind
  installed_backend_kind="$(_linux_detect_installed_backend_kind "$_KAST_LINUX_INSTALL_HOME")"
  _linux_validate_backend_source "$_KAST_LINUX_INSTALL_HOME" "$installed_backend_kind"

  local version_output
  version_output="$("$_KAST_LINUX_BIN_PATH" version)"
  printf '%s\n' "$version_output" | grep -Fq "$_KAST_LINUX_NORMALIZED_VERSION" \
    || die "kast version does not contain ${_KAST_LINUX_NORMALIZED_VERSION}: ${version_output}"

  [[ "$installed_backend_kind" == "headless" ]] || die "Installed backend kind must be headless"
  [[ -f "$_KAST_LINUX_BIN_PATH" && ! -L "$_KAST_LINUX_BIN_PATH" ]] || die "Expected headless ${_KAST_LINUX_BIN_PATH} to be an executable shim"
  grep -Fq -- "-Didea.force.use.core.classloader=true" "$_KAST_LINUX_BIN_PATH" \
    || die "Headless kast shim does not export the core classloader JVM option"
  grep -Fq 'defaultBackend = "headless"' "$_KAST_LINUX_CONFIG_FILE" \
    || die "config.toml does not default to headless runtime"
  grep -Fq "[backends.headless]" "$_KAST_LINUX_CONFIG_FILE" || die "config.toml does not include headless backend config"
  grep -Fq "runtimeLibsDir = \"${_KAST_LINUX_HEADLESS_RUNTIME_LIBS_DIR}\"" "$_KAST_LINUX_CONFIG_FILE" \
    || die "config.toml does not point at ${_KAST_LINUX_HEADLESS_RUNTIME_LIBS_DIR}"
  grep -Fq "ideaHome = \"${_KAST_LINUX_HEADLESS_IDEA_HOME}\"" "$_KAST_LINUX_CONFIG_FILE" \
    || die "config.toml does not point at ${_KAST_LINUX_HEADLESS_IDEA_HOME}"
  grep -Fq "binaryPath = \"${_KAST_LINUX_BIN_PATH}\"" "$_KAST_LINUX_CONFIG_FILE" \
    || die "config.toml does not point at ${_KAST_LINUX_BIN_PATH}"

  "$_KAST_LINUX_BIN_PATH" doctor >/dev/null
  printf '%s\n' "Kast Ubuntu/Debian bundle ${_KAST_LINUX_VERSION} verified"
}

_linux_install_bundle() {
  _linux_assert_host
  _linux_configure_paths
  need_tool tar
  need_tool "$_KAST_LINUX_JAVA_CMD"

  if [[ -x "$_KAST_LINUX_BIN_PATH" && -d "$_KAST_LINUX_INSTALL_HOME" ]]; then
    if "$_KAST_LINUX_BIN_PATH" version 2>/dev/null | grep -Fq "$_KAST_LINUX_NORMALIZED_VERSION"; then
      if _linux_verify_install >/dev/null 2>&1; then
        log_success "Kast Ubuntu/Debian bundle ${_KAST_LINUX_VERSION} is already installed"
        exit 0
      fi
    fi
  fi

  local bundle_source_dir=""
  if bundle_source_dir="$(_linux_bundle_source_dir)"; then
    :
  else
    _KAST_INSTALL_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-ubuntu-debian-install.XXXXXX")"
    local artifact_path="${_KAST_INSTALL_TMP_DIR}/${_KAST_LINUX_ARTIFACT_NAME}"
    _linux_fetch_artifact "$artifact_path" "$_KAST_LINUX_ARTIFACT_NAME"
    bundle_source_dir="$(_linux_source_from_artifact "$artifact_path" "${_KAST_INSTALL_TMP_DIR}/extract")"
  fi

  [[ -x "${bundle_source_dir}/bin/kast" ]] || die "Bundle source missing executable bin/kast"
  local source_backend_kind
  source_backend_kind="$(_linux_detect_installed_backend_kind "$bundle_source_dir")"
  _linux_validate_backend_source "$bundle_source_dir" "$source_backend_kind"

  mkdir -p "$_KAST_LINUX_ROOT_DIR" "$_KAST_LINUX_BIN_DIR"
  rm -rf "$_KAST_LINUX_INSTALL_HOME"
  _linux_copy_tree "$bundle_source_dir" "$_KAST_LINUX_INSTALL_HOME"
  mkdir -p "${_KAST_LINUX_INSTALL_HOME}/cache" "${_KAST_LINUX_INSTALL_HOME}/logs"
  chmod +x "${_KAST_LINUX_INSTALL_HOME}/bin/kast"
  [[ -f "${_KAST_LINUX_INSTALL_HOME}/kast.sh" ]] && chmod +x "${_KAST_LINUX_INSTALL_HOME}/kast.sh"
  _linux_install_kast_entrypoint

  _linux_write_config
  _linux_verify_install
  log_success "Kast Ubuntu/Debian bundle ${_KAST_LINUX_VERSION} installed at ${_KAST_LINUX_INSTALL_HOME}"
}

_install_usage() {
  cat >&2 <<'USAGE'
Usage:
  kast.sh install [--from PATH_OR_URL] [--version vX.Y.Z] [--skip-setup] [--yes]
  kast.sh verify [--version vX.Y.Z]

curl-pipe:
  curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/kast.sh | bash
  curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/kast.sh | bash -s -- --from PATH_OR_URL

Installer behavior:
  macOS installs through Homebrew, including the kast-plugin cask and kast setup.
  Ubuntu/Debian Linux x86_64 installs the headless tarball and verifies config.

Options:
  --from PATH_OR_URL   Install from an exact local file path or HTTP(S) release artifact URL.
  --version vX.Y.Z     Select a release tag for download installs.
  --skip-setup         On macOS, skip the final `kast setup` step.
  --yes                Accepted for non-interactive curl-pipe usage.
  --help, -h           Show this help.

Linux environment overrides:
  KAST_UBUNTU_DEBIAN_ROOT, KAST_UBUNTU_DEBIAN_BIN_DIR,
  KAST_UBUNTU_DEBIAN_CONFIG_HOME, KAST_JAVA_CMD.
USAGE
}

_install_parse_args() {
  _KAST_INSTALL_FROM=""
  _KAST_INSTALL_VERSION=""
  _KAST_INSTALL_SKIP_SETUP="${KAST_INSTALL_SKIP_SETUP:-false}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --from)
        [[ $# -ge 2 ]] || die "Missing value for --from"
        _KAST_INSTALL_FROM="$2"; shift 2 ;;
      --from=*)
        _KAST_INSTALL_FROM="${1#--from=}"; shift ;;
      --version)
        [[ $# -ge 2 ]] || die "Missing value for --version"
        _KAST_INSTALL_VERSION="$2"; shift 2 ;;
      --version=*)
        _KAST_INSTALL_VERSION="${1#--version=}"; shift ;;
      --skip-setup)
        _KAST_INSTALL_SKIP_SETUP=true; shift ;;
      --yes|-y)
        shift ;;
      --help|-h|help)
        _install_usage; exit 0 ;;
      *)
        _install_usage; die "Unknown install argument: $1" ;;
    esac
  done
  [[ -z "$_KAST_INSTALL_FROM" ]] || _install_validate_from "$_KAST_INSTALL_FROM"
}

cmd_install() {
  _install_parse_args "$@"
  if [[ "${KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK:-false}" == "true" ]]; then
    _linux_install_bundle
    return
  fi
  case "$(_install_uname_s)" in
    Darwin) _install_macos ;;
    Linux) _linux_install_bundle ;;
    *) die "Kast installer supports macOS and Ubuntu/Debian Linux only; found $(_install_uname_s)" ;;
  esac
}

cmd_verify() {
  _install_parse_args "$@"
  if [[ "${KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK:-false}" == "true" ]]; then
    _linux_verify_install
    return
  fi
  case "$(_install_uname_s)" in
    Linux) _linux_verify_install ;;
    Darwin)
      need_tool kast
      kast doctor
      ;;
    *) die "Kast verifier supports macOS and Ubuntu/Debian Linux only; found $(_install_uname_s)" ;;
  esac
}

# ===========================================================================
# cmd_build -- local dev build / packaging
# ===========================================================================

_BUILD_ALL_TARGETS=(plugin headless)
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
_HEADLESS_IDEA_HOME_PROFILE="${KAST_HEADLESS_IDEA_HOME_PROFILE:-full}"

_build_validate_headless_profile() {
  case "$_HEADLESS_IDEA_HOME_PROFILE" in
    full|minimal|agent) ;;
    *)
      die "Invalid --headless-idea-home-profile value: ${_HEADLESS_IDEA_HOME_PROFILE}; expected full, minimal, or agent"
      ;;
  esac
}

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

_build_resolve_headless_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${HEADLESS_PORTABLE_ZIP_DIR}"/backend-headless-*-portable.zip; do
    [[ -z "$newest" || "$candidate" -nt "$newest" ]] && newest="$candidate"
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a headless backend portable zip under ${HEADLESS_PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

_build_plugin() {
  log_section "Building target: plugin"
  _build_run_gradle_tasks_with_retry buildIdeaPlugin

  local source_zip; source_zip="$(_build_resolve_plugin_zip)"
  local dist_zip="${DIST_ROOT}/plugin.zip"
  log_step "Publishing plugin zip into ${dist_zip}"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"
}

_build_headless() {
  log_section "Building target: headless"
  rm -rf "${REPO_ROOT}/backend-headless/build/portable-dist" "${REPO_ROOT}/backend-headless/build/distributions"
  _build_run_gradle_tasks_with_retry stageHeadlessDist buildHeadlessPortableZip

  log_step "Verifying staged headless backend tree in ${HEADLESS_PORTABLE_DIST_DIR}"
  [[ -x "${HEADLESS_PORTABLE_DIST_DIR}/kast-headless" ]]                  || die "Missing staged backend-headless launcher"
  [[ -d "${HEADLESS_PORTABLE_DIST_DIR}/runtime-libs" ]]                   || die "Missing staged headless runtime-libs directory"
  [[ -f "${HEADLESS_PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]]     || die "Missing staged headless runtime classpath file"
  [[ -d "${HEADLESS_PORTABLE_DIST_DIR}/idea-home/plugins/kast-headless/lib" ]] || die "Missing staged headless plugin libraries"
  local jars=()
  shopt -s nullglob
  jars=("${HEADLESS_PORTABLE_DIST_DIR}"/libs/backend-headless-*-all.jar)
  shopt -u nullglob
  [[ "${#jars[@]}" -eq 0 ]] || die "Headless portable distribution must not include fat jars under ${HEADLESS_PORTABLE_DIST_DIR}/libs"

  local dist_dir="${DIST_ROOT}/headless"
  local dist_zip="${DIST_ROOT}/headless.zip"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing headless backend tree into ${dist_dir}"
  mkdir -p "$DIST_ROOT"
  cp -R "$HEADLESS_PORTABLE_DIST_DIR" "${tmp_dir}/headless"
  rm -rf "$dist_dir"
  mv "${tmp_dir}/headless" "$dist_dir"
  log_success "Published ${dist_dir}"

  local source_zip; source_zip="$(_build_resolve_headless_zip)"
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
  local headless_dir="${DIST_ROOT}/headless"
  if [[ -d "$headless_dir" && (! -f "${headless_dir}/kast-headless" || ! -d "${headless_dir}/runtime-libs") ]]; then
    log_step "Removing incomplete ${headless_dir} from a previous run"
    rm -rf "$headless_dir"
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
      plugin|headless)
        _build_selected_targets+=("$1"); shift ;;
      --all)
        _build_selected_targets=("${_BUILD_ALL_TARGETS[@]}"); shift ;;
      --help|-h)
        cat >&2 << 'USAGE'
Usage: ./kast.sh build [target...] [options]

Builds selected Kast components and publishes artifacts to dist/.

Targets (positional, repeatable):
  plugin       IDEA plugin zip           -> dist/plugin.zip
  headless     Headless IDEA backend     -> dist/headless/  dist/headless.zip

Options:
  --all            Build all targets.
  --headless-idea-home-profile=full|minimal|agent  Configure headless IDEA home profile (default: full).
  -Pname=value     Forward a Gradle project property to the build.
  --help, -h       Show this help.

When no targets are supplied and a TTY is available, fzf is used for
interactive multi-selection. Falls back to building all targets when
fzf is not installed.

Profile can also be set with KAST_HEADLESS_IDEA_HOME_PROFILE.
USAGE
        return 0
        ;;
      --headless-idea-home-profile=*)
        _HEADLESS_IDEA_HOME_PROFILE="${1#*=}"
        shift
        ;;
      -P*)
        _GRADLE_EXTRA_ARGS+=("$1")
        shift
        ;;
      *)
        die "Unknown argument: $1" ;;
    esac
  done

  _build_validate_headless_profile
  _GRADLE_EXTRA_ARGS+=("-PkastHeadlessIdeaHomeProfile=${_HEADLESS_IDEA_HOME_PROFILE}")

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
      headless) _build_headless ;;
    esac
  done

  _build_openapi

  log_section "Build complete"
  for target in "${_build_selected_targets[@]}"; do
    case "$target" in
      plugin)  log_success "plugin  ->  ${DIST_ROOT}/plugin.zip" ;;
      headless) log_success "headless ->  ${DIST_ROOT}/headless/  ${DIST_ROOT}/headless.zip" ;;
    esac
  done
}

# ===========================================================================
# Top-level dispatch
# ===========================================================================

usage_main() {
  cat >&2 << 'USAGE'
Usage: ./kast.sh <subcommand> [options]

Subcommands:
  build    Build portable distribution artifacts  ->  dist/
  install  Install Kast for this host
  verify   Verify the installed Kast setup

Run ./kast.sh <subcommand> --help for subcommand-specific options.

curl-pipe install:
  curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/kast.sh | bash

Local artifact install:
  ./kast.sh install --from dist/kast-v1.2.3-macos-arm64.zip
  ./kast.sh install --from dist/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz
USAGE
}

main() {
  local cmd="${1:-}"

  if [[ -z "$SCRIPT_DIR" ]] && [[ -z "$cmd" || "$cmd" == --* ]]; then
    cmd_install "$@"
    return
  fi

  case "$cmd" in
    build)          shift; cmd_build "$@" ;;
    install)        shift; cmd_install "$@" ;;
    verify)         shift; cmd_verify "$@" ;;
    --help|-h|help) usage_main ;;
    "")             usage_main; exit 1 ;;
    *)              die "Unknown subcommand: ${cmd}. Run ./kast.sh --help for usage." ;;
  esac
}

main "$@"
