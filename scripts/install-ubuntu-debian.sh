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
    kast-ubuntu-debian-headless-x86_64-*.tar.gz)
      local inferred="${bundle_name#kast-ubuntu-debian-headless-x86_64-}"
      inferred="${inferred%.tar.gz}"
      [[ -n "$inferred" ]] || return 1
      printf '%s\n' "$inferred"
      ;;
    kast-ubuntu-debian-headless-x86_64-*)
      local inferred="${bundle_name#kast-ubuntu-debian-headless-x86_64-}"
      [[ -n "$inferred" ]] || return 1
      printf '%s\n' "$inferred"
      ;;
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

infer_bundle_kind_from_name() {
  local bundle_name="$1"
  case "$bundle_name" in
    kast-ubuntu-debian-headless-x86_64-*) printf '%s\n' "headless" ;;
    kast-ubuntu-debian-x86_64-*) printf '%s\n' "standalone" ;;
    *) return 1 ;;
  esac
}

infer_bundle_kind_from_context() {
  if [[ -n "${KAST_UBUNTU_DEBIAN_BUNDLE_KIND:-}" ]]; then
    printf '%s\n' "$KAST_UBUNTU_DEBIAN_BUNDLE_KIND"
    return
  fi

  if [[ -n "${KAST_UBUNTU_DEBIAN_ARTIFACT_PATH:-}" ]]; then
    infer_bundle_kind_from_name "$(basename -- "$KAST_UBUNTU_DEBIAN_ARTIFACT_PATH")" && return
  fi

  local script_dir
  script_dir="$(resolve_script_dir)"
  if [[ -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ]]; then
    infer_bundle_kind_from_name "$(basename -- "$(cd -- "${script_dir}/.." && pwd)")" && return
  fi

  printf '%s\n' "standalone"
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
  KAST_UBUNTU_DEBIAN_BUNDLE_KIND    standalone or headless, default standalone
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

write_headless_kast_shim() {
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

install_kast_entrypoint() {
  local backend_kind="$1"
  local bundled_cli_path="${install_home}/bin/kast"

  case "$backend_kind" in
    standalone)
      if [[ "$bin_path" != "$bundled_cli_path" ]]; then
        rm -f "$bin_path"
        ln -sfn "$bundled_cli_path" "$bin_path"
      fi
      ;;
    headless)
      local shim_cli_path="$bundled_cli_path"
      if [[ "$bin_path" == "$bundled_cli_path" ]]; then
        shim_cli_path="${install_home}/bin/kast-cli"
        mv "$bundled_cli_path" "$shim_cli_path"
      fi
      rm -f "$bin_path"
      write_headless_kast_shim "$bin_path" "$shim_cli_path"
      ;;
    *)
      die "Unsupported bundle backend kind: $backend_kind"
      ;;
  esac
}

write_config() {
  local config_file="$1"
  local install_home="$2"
  local bin_path="$3"
  local backend_kind="$4"
  local version="$5"
  local normalized_version="$6"

  local backend_config=""
  local components=""
  case "$backend_kind" in
    standalone)
      backend_config="[backends.standalone]
runtimeLibsDir = \"$(toml_escape "$standalone_runtime_libs_dir")\""
      components='["cli", "standalone-backend", "config"]'
      ;;
    headless)
      backend_config="[runtime]
defaultBackend = \"headless\"

[backends.headless]
runtimeLibsDir = \"$(toml_escape "$headless_runtime_libs_dir")\"
ideaHome = \"$(toml_escape "$headless_idea_home")\""
      components='["cli", "headless-backend", "config"]'
      ;;
    *)
      die "Unsupported bundle backend kind: $backend_kind"
      ;;
  esac

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

${backend_config}

[cli]
binaryPath = "$(toml_escape "$bin_path")"

[install]
version = "$(toml_escape "$normalized_version")"
backendVersion = "$(toml_escape "$normalized_version")"
installedAt = "$(toml_escape "$platform"):${version}"
platform = "$(toml_escape "$platform")"
components = ${components}
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
  bundle_kind="$(infer_bundle_kind_from_context)"
  case "$bundle_kind" in
    standalone) platform="ubuntu-debian-x86_64" ;;
    headless) platform="ubuntu-debian-headless-x86_64" ;;
    *) die "Unsupported KAST_UBUNTU_DEBIAN_BUNDLE_KIND: $bundle_kind" ;;
  esac
  artifact_name="kast-${platform}-${version}.tar.gz"
  root_dir="${KAST_UBUNTU_DEBIAN_ROOT:-${HOME}/.local/share/kast/ubuntu-debian}"
  install_home="${root_dir}/${version}"
  bin_dir="${KAST_UBUNTU_DEBIAN_BIN_DIR:-${HOME}/.local/bin}"
  bin_path="${bin_dir}/kast"
  config_home="${KAST_UBUNTU_DEBIAN_CONFIG_HOME:-${KAST_CONFIG_HOME:-${HOME}/.config/kast}}"
  config_file="${config_home}/config.toml"
  base_url="${KAST_UBUNTU_DEBIAN_BASE_URL:-https://github.com/amichne/kast/releases/download/${version}}"
  standalone_root="${install_home}/lib/backends/standalone-${version}"
  standalone_runtime_libs_dir="${standalone_root}/runtime-libs"
  headless_root="${install_home}/lib/backends/headless-${version}"
  headless_runtime_libs_dir="${headless_root}/runtime-libs"
  headless_idea_home="${headless_root}/idea-home"
  java_cmd="${KAST_JAVA_CMD:-java}"
}

detect_installed_backend_kind() {
  local source_dir="$1"
  local has_standalone=false
  local has_headless=false

  [[ -f "${source_dir}/lib/backends/standalone-${version}/runtime-libs/classpath.txt" ]] && has_standalone=true
  [[ -f "${source_dir}/lib/backends/headless-${version}/runtime-libs/classpath.txt" ]] && has_headless=true

  if [[ "$has_standalone" == "true" && "$has_headless" == "true" ]]; then
    die "Bundle source must not contain both standalone and headless backends for ${version}"
  fi
  if [[ "$has_standalone" == "true" ]]; then
    printf '%s\n' "standalone"
    return
  fi
  if [[ "$has_headless" == "true" ]]; then
    printf '%s\n' "headless"
    return
  fi
  die "Bundle source missing backend runtime-libs/classpath.txt for ${version}"
}

validate_backend_source() {
  local source_dir="$1"
  local backend_kind="$2"

  case "$backend_kind" in
    standalone)
      [[ -f "${source_dir}/lib/backends/standalone-${version}/runtime-libs/classpath.txt" ]] \
        || die "Bundle source missing standalone runtime-libs/classpath.txt"
      [[ -x "${source_dir}/lib/backends/standalone-${version}/kast-standalone" ]] \
        || die "Bundle source missing standalone kast-standalone launcher"
      ;;
    headless)
      [[ -f "${source_dir}/lib/backends/headless-${version}/runtime-libs/classpath.txt" ]] \
        || die "Bundle source missing headless runtime-libs/classpath.txt"
      [[ -x "${source_dir}/lib/backends/headless-${version}/kast-headless" ]] \
        || die "Bundle source missing headless kast-headless launcher"
      [[ -f "${source_dir}/lib/backends/headless-${version}/idea-home/lib/nio-fs.jar" ]] \
        || die "Bundle source missing headless idea-home/lib/nio-fs.jar"
      [[ -f "${source_dir}/lib/backends/headless-${version}/idea-home/modules/module-descriptors.dat" ]] \
        || die "Bundle source missing headless idea-home/modules/module-descriptors.dat"
      [[ -d "${source_dir}/lib/backends/headless-${version}/idea-home/plugins/kast-headless" ]] \
        || die "Bundle source missing bundled kast-headless plugin"
      ;;
    *)
      die "Unsupported bundle backend kind: $backend_kind"
      ;;
  esac
}

verify_install() {
  configure_paths
  export PATH="${bin_dir}:${PATH}"
  export KAST_CONFIG_HOME="$config_home"

  need_tool "$java_cmd"
  need_tool kast

  [[ -x "$bin_path" ]] || die "Expected executable kast at ${bin_path}"
  [[ -d "$install_home" ]] || die "Install root not found: ${install_home}"
  [[ -f "$config_file" ]] || die "Kast config not found: ${config_file}"
  local installed_backend_kind
  installed_backend_kind="$(detect_installed_backend_kind "$install_home")"
  validate_backend_source "$install_home" "$installed_backend_kind"

  local version_output
  version_output="$("$bin_path" version)"
  printf '%s\n' "$version_output" | grep -Fq "$normalized_version" \
    || die "kast version does not contain ${normalized_version}: ${version_output}"

  case "$installed_backend_kind" in
    standalone)
      if [[ "$bin_path" != "${install_home}/bin/kast" ]]; then
        [[ -L "$bin_path" ]] || die "Expected standalone ${bin_path} to be a symlink"
      fi
      grep -Fq "[backends.standalone]" "$config_file" || die "config.toml does not include standalone backend config"
      grep -Fq "runtimeLibsDir = \"${standalone_runtime_libs_dir}\"" "$config_file" \
        || die "config.toml does not point at ${standalone_runtime_libs_dir}"
      ;;
    headless)
      [[ -f "$bin_path" && ! -L "$bin_path" ]] || die "Expected headless ${bin_path} to be an executable shim"
      grep -Fq -- "-Didea.force.use.core.classloader=true" "$bin_path" \
        || die "Headless kast shim does not export the core classloader JVM option"
      grep -Fq 'defaultBackend = "headless"' "$config_file" \
        || die "config.toml does not default to headless runtime"
      grep -Fq "[backends.headless]" "$config_file" || die "config.toml does not include headless backend config"
      grep -Fq "runtimeLibsDir = \"${headless_runtime_libs_dir}\"" "$config_file" \
        || die "config.toml does not point at ${headless_runtime_libs_dir}"
      grep -Fq "ideaHome = \"${headless_idea_home}\"" "$config_file" \
        || die "config.toml does not point at ${headless_idea_home}"
      ;;
  esac
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
  local source_backend_kind
  source_backend_kind="$(detect_installed_backend_kind "$bundle_source_dir")"
  validate_backend_source "$bundle_source_dir" "$source_backend_kind"

  mkdir -p "$root_dir" "$bin_dir"
  rm -rf "$install_home"
  copy_tree "$bundle_source_dir" "$install_home"
  mkdir -p "$install_home/cache" "$install_home/logs"
  chmod +x "$install_home/bin/kast" "$install_home/scripts/install-ubuntu-debian.sh"
  install_kast_entrypoint "$source_backend_kind"

  write_config "$config_file" "$install_home" "$bin_path" "$source_backend_kind" "$version" "$normalized_version"
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
