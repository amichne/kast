#!/usr/bin/env bash
set -Eeuo pipefail

_KAST_UBUNTU_DEBIAN_TMP_DIR=""

cleanup_tmp() {
  if [[ -n "${_KAST_UBUNTU_DEBIAN_TMP_DIR:-}" && -d "$_KAST_UBUNTU_DEBIAN_TMP_DIR" ]]; then
    rm -rf "$_KAST_UBUNTU_DEBIAN_TMP_DIR"
  fi
}

trap cleanup_tmp EXIT

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '%s\n' "$*" >&2
}

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

toml_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s\n' "$value"
}

normalize_version() {
  local value="$1"
  printf '%s\n' "${value#v}"
}

infer_version_from_bundle_name() {
  local bundle_name="$1"
  case "$bundle_name" in
    kast-ubuntu-debian-x86_64-*.tar.gz)
      local inferred="${bundle_name#kast-ubuntu-debian-x86_64-}"
      inferred="${inferred%.tar.gz}"
      [[ -n "$inferred" ]] || return 1
      printf '%s\n' "$inferred"
      ;;
    kast-ubuntu-debian-x86_64-*)
      local inferred="${bundle_name#kast-ubuntu-debian-x86_64-}"
      [[ -n "$inferred" ]] || return 1
      printf '%s\n' "$inferred"
      ;;
    *)
      return 1
      ;;
  esac
}

infer_version_from_context() {
  if [[ -n "${KAST_UBUNTU_DEBIAN_ARTIFACT_PATH:-}" ]]; then
    infer_version_from_bundle_name "$(basename -- "$KAST_UBUNTU_DEBIAN_ARTIFACT_PATH")" && return
  fi

  local script_dir
  script_dir="$(resolve_script_dir)"
  if [[ -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ]]; then
    infer_version_from_bundle_name "$(basename -- "$(cd -- "${script_dir}/.." && pwd)")" && return
  fi

  return 1
}

resolve_script_dir() {
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/install-ubuntu-debian.sh [install|verify]

Canonical non-Brew Kast installer for Ubuntu/Debian x86_64 hosts.

Environment:
  KAST_UBUNTU_DEBIAN_VERSION        Version tag for download installs
  KAST_UBUNTU_DEBIAN_ARTIFACT_PATH  Local bundle tarball path
  KAST_UBUNTU_DEBIAN_BASE_URL       Release base URL for download installs
  KAST_UBUNTU_DEBIAN_ROOT           Install root, default ~/.local/share/kast/ubuntu-debian
  KAST_UBUNTU_DEBIAN_BIN_DIR        Symlink directory, default ~/.local/bin
  KAST_UBUNTU_DEBIAN_CONFIG_HOME    Config directory, default ~/.config/kast
  KAST_JAVA_CMD                     Java executable, default java
USAGE
}

assert_debian_like_host() {
  [[ "${KAST_UBUNTU_DEBIAN_TEST_BYPASS_HOST_CHECK:-false}" == "true" ]] && return
  [[ "$(uname -s)" == "Linux" ]] || die "This installer only supports Ubuntu/Debian Linux hosts"
  [[ "$(uname -m)" == "x86_64" ]] || die "This installer only supports x86_64 hosts"
  [[ -r /etc/os-release ]] || die "Cannot verify Ubuntu/Debian host: /etc/os-release is missing"

  # shellcheck disable=SC1091
  . /etc/os-release
  local distro="${ID:-} ${ID_LIKE:-}"
  case "$distro" in
    *debian*|*ubuntu*) ;;
    *) die "This installer only supports Ubuntu/Debian hosts; found ID=${ID:-unknown} ID_LIKE=${ID_LIKE:-unknown}" ;;
  esac
}

copy_tree() {
  local source_dir="$1"
  local destination_dir="$2"

  mkdir -p "$destination_dir"
  tar -C "$source_dir" -cf - . | tar -C "$destination_dir" -xf -
}

write_config() {
  local config_file="$1"
  local install_home="$2"
  local bin_path="$3"
  local runtime_libs_dir="$4"
  local version="$5"
  local normalized_version="$6"

  mkdir -p "$(dirname -- "$config_file")"
  cat > "$config_file" <<TOML
[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[paths]
installRoot = "$(toml_escape "$install_home")"
binDir = "$(toml_escape "$(dirname -- "$bin_path")")"
libDir = "$(toml_escape "${install_home}/lib")"
cacheDir = "$(toml_escape "${install_home}/cache")"
logsDir = "$(toml_escape "${install_home}/logs")"
descriptorDir = "$(toml_escape "${install_home}/cache/daemons")"
socketDir = "$(toml_escape "${TMPDIR:-/tmp}")"

[backends.standalone]
runtimeLibsDir = "$(toml_escape "$runtime_libs_dir")"

[cli]
binaryPath = "$(toml_escape "$bin_path")"

[install]
version = "$(toml_escape "$normalized_version")"
backendVersion = "$(toml_escape "$normalized_version")"
installedAt = "ubuntu-debian:${version}"
platform = "ubuntu-debian-x86_64"
components = ["cli", "backend", "config"]
managedPaths = ["bin", "lib", "cache", "logs"]
shellRcPatches = []
repos = []
schemaVersion = 6
TOML
}

source_from_artifact() {
  local artifact_path="$1"
  local output_dir="$2"
  local top_level=""

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

fetch_artifact() {
  local artifact_path="$1"
  local artifact_name="$2"
  local base_url="$3"

  local local_artifact="${KAST_UBUNTU_DEBIAN_ARTIFACT_PATH:-}"
  if [[ -n "$local_artifact" ]]; then
    [[ -f "$local_artifact" ]] || die "KAST_UBUNTU_DEBIAN_ARTIFACT_PATH does not exist: $local_artifact"
    [[ -f "${local_artifact}.sha256" ]] || die "Missing SHA-256 sidecar: ${local_artifact}.sha256"
    cp "$local_artifact" "$artifact_path"
    cp "${local_artifact}.sha256" "${artifact_path}.sha256"
  else
    need_tool curl
    curl --fail --location --retry 3 --retry-delay 2 --silent --show-error \
      --output "$artifact_path" \
      "${base_url}/${artifact_name}"
    curl --fail --location --retry 3 --retry-delay 2 --silent --show-error \
      --output "${artifact_path}.sha256" \
      "${base_url}/${artifact_name}.sha256"
  fi

  [[ -f "${artifact_path}.sha256" ]] || die "Missing SHA-256 sidecar: ${artifact_name}.sha256"

  local expected_digest
  local expected_name
  read -r expected_digest expected_name < "${artifact_path}.sha256"
  [[ "$expected_name" == "$artifact_name" ]] \
    || die "SHA-256 sidecar names ${expected_name}, expected ${artifact_name}"
  local actual_digest
  actual_digest="$(compute_sha256 "$artifact_path")"
  [[ "$actual_digest" == "$expected_digest" ]] \
    || die "SHA-256 mismatch for ${artifact_name}"
  log "${artifact_name}: OK"
}

configure_paths() {
  version="${KAST_UBUNTU_DEBIAN_VERSION:-}"
  if [[ -z "$version" ]]; then
    version="$(infer_version_from_context || true)"
  fi
  [[ -n "$version" ]] || die "Set KAST_UBUNTU_DEBIAN_VERSION or run from an extracted Ubuntu/Debian bundle"
  normalized_version="$(normalize_version "$version")"
  platform="ubuntu-debian-x86_64"
  artifact_name="kast-${platform}-${version}.tar.gz"
  root_dir="${KAST_UBUNTU_DEBIAN_ROOT:-${HOME}/.local/share/kast/ubuntu-debian}"
  install_home="${root_dir}/${version}"
  bin_dir="${KAST_UBUNTU_DEBIAN_BIN_DIR:-${HOME}/.local/bin}"
  bin_path="${bin_dir}/kast"
  config_home="${KAST_UBUNTU_DEBIAN_CONFIG_HOME:-${KAST_CONFIG_HOME:-${HOME}/.config/kast}}"
  config_file="${config_home}/config.toml"
  base_url="${KAST_UBUNTU_DEBIAN_BASE_URL:-https://github.com/amichne/kast/releases/download/${version}}"
  runtime_libs_dir="${install_home}/lib/backends/standalone-${version}/runtime-libs"
  java_cmd="${KAST_JAVA_CMD:-java}"
}

verify_install() {
  configure_paths
  export PATH="${bin_dir}:${PATH}"
  export KAST_CONFIG_HOME="$config_home"

  need_tool "$java_cmd"
  need_tool kast

  [[ -L "$bin_path" ]] || die "Expected ${bin_path} to be a symlink"
  [[ -x "$bin_path" ]] || die "Expected executable kast at ${bin_path}"
  [[ -d "$install_home" ]] || die "Install root not found: ${install_home}"
  [[ -f "$config_file" ]] || die "Kast config not found: ${config_file}"
  [[ -d "$runtime_libs_dir" ]] || die "Standalone runtime libs not found: ${runtime_libs_dir}"
  [[ -f "${runtime_libs_dir}/classpath.txt" ]] || die "Standalone classpath not found: ${runtime_libs_dir}/classpath.txt"

  local version_output
  version_output="$("$bin_path" version)"
  printf '%s\n' "$version_output" | grep -Fq "$normalized_version" \
    || die "kast version does not contain ${normalized_version}: ${version_output}"

  grep -Fq "runtimeLibsDir = \"${runtime_libs_dir}\"" "$config_file" \
    || die "config.toml does not point at ${runtime_libs_dir}"
  grep -Fq "binaryPath = \"${bin_path}\"" "$config_file" \
    || die "config.toml does not point at ${bin_path}"

  "$bin_path" doctor >/dev/null
  printf '%s\n' "Kast Ubuntu/Debian bundle ${version} verified"
}

install_bundle() {
  assert_debian_like_host
  configure_paths
  need_tool tar
  need_tool "$java_cmd"

  if [[ -x "$bin_path" && -d "$install_home" ]]; then
    if "$bin_path" version 2>/dev/null | grep -Fq "$normalized_version"; then
      if verify_install >/dev/null 2>&1; then
        log "Kast Ubuntu/Debian bundle ${version} is already installed"
        exit 0
      fi
    fi
  fi

  local script_dir
  script_dir="$(resolve_script_dir)"
  local bundle_source_dir=""

  if [[ -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ]]; then
    bundle_source_dir="$(cd -- "${script_dir}/.." && pwd)"
  else
    _KAST_UBUNTU_DEBIAN_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-ubuntu-debian-install.XXXXXX")"
    local artifact_path="${_KAST_UBUNTU_DEBIAN_TMP_DIR}/${artifact_name}"
    fetch_artifact "$artifact_path" "$artifact_name" "$base_url"
    bundle_source_dir="$(source_from_artifact "$artifact_path" "${_KAST_UBUNTU_DEBIAN_TMP_DIR}/extract")"
  fi

  [[ -x "${bundle_source_dir}/bin/kast" ]] || die "Bundle source missing executable bin/kast"
  [[ -f "${bundle_source_dir}/lib/backends/standalone-${version}/runtime-libs/classpath.txt" ]] \
    || die "Bundle source missing standalone runtime-libs/classpath.txt"

  mkdir -p "$root_dir" "$bin_dir"
  rm -rf "$install_home"
  copy_tree "$bundle_source_dir" "$install_home"
  mkdir -p "$install_home/cache" "$install_home/logs"
  chmod +x "$install_home/bin/kast" "$install_home/scripts/install-ubuntu-debian.sh"
  ln -sfn "$install_home/bin/kast" "$bin_path"

  write_config "$config_file" "$install_home" "$bin_path" "$runtime_libs_dir" "$version" "$normalized_version"
  verify_install
  log "Kast Ubuntu/Debian bundle ${version} installed at ${install_home}"
}

main() {
  local command="${1:-install}"
  case "$command" in
    install) install_bundle ;;
    verify) verify_install ;;
    --help|-h|help) usage ;;
    *) usage; die "Unknown command: ${command}" ;;
  esac
}

main "$@"
