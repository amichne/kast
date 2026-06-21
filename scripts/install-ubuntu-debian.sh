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

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
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
    *)
      return 1
      ;;
  esac
}

read_bundle_manifest_value() {
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

infer_version_from_context() {
  if [[ -n "${KAST_UBUNTU_DEBIAN_ARTIFACT_PATH:-}" ]]; then
    infer_version_from_bundle_name "$(basename -- "$KAST_UBUNTU_DEBIAN_ARTIFACT_PATH")" && return
  fi

  local script_dir
  script_dir="$(resolve_script_dir)"
  if [[ -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ]]; then
    read_bundle_manifest_value "${script_dir}/../manifest.json" "version" && return
    infer_version_from_bundle_name "$(basename -- "$(cd -- "${script_dir}/.." && pwd)")" && return
  fi

  return 1
}

infer_bundle_kind_from_name() {
  local bundle_name="$1"
  case "$bundle_name" in
    kast-ubuntu-debian-headless-x86_64-*) printf '%s\n' "headless" ;;
    *) return 1 ;;
  esac
}

infer_bundle_kind_from_context() {
  if [[ -n "${KAST_UBUNTU_DEBIAN_ARTIFACT_PATH:-}" ]]; then
    infer_bundle_kind_from_name "$(basename -- "$KAST_UBUNTU_DEBIAN_ARTIFACT_PATH")" && return
  fi

  local script_dir
  script_dir="$(resolve_script_dir)"
  if [[ -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ]]; then
    read_bundle_manifest_value "${script_dir}/../manifest.json" "backendKind" && return
    infer_bundle_kind_from_name "$(basename -- "$(cd -- "${script_dir}/.." && pwd)")" && return
  fi

  printf '%s\n' "headless"
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
  KAST_UBUNTU_DEBIAN_ROOT           Install root, default ~/.local/share/kast
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
  local quoted_install_root
  local quoted_config_home

  printf -v quoted_cli_path '%q' "$cli_path"
  printf -v quoted_install_root '%q' "$root_dir"
  printf -v quoted_config_home '%q' "$config_home"
  mkdir -p "$(dirname -- "$output_path")"
  cat > "$output_path" <<SHIM
#!/usr/bin/env bash
set -euo pipefail

export KAST_INSTALL_ROOT=${quoted_install_root}
export KAST_CONFIG_HOME=${quoted_config_home}

case " \${JAVA_OPTS:-} " in
  *" -Didea.force.use.core.classloader=true "*) ;;
  *) export JAVA_OPTS="\${JAVA_OPTS:+\${JAVA_OPTS} }-Didea.force.use.core.classloader=true" ;;
esac

exec ${quoted_cli_path} "\$@"
SHIM
  chmod 755 "$output_path"
}

install_kast_entrypoint() {
  local bundled_cli_path="${install_home}/bin/kast"

  local shim_cli_path="$bundled_cli_path"
  if [[ "$bin_path" == "$bundled_cli_path" ]]; then
    shim_cli_path="${install_home}/bin/kast-cli"
    mv "$bundled_cli_path" "$shim_cli_path"
  fi
  rm -f "$bin_path"
  write_headless_kast_shim "$bin_path" "$shim_cli_path"
}

link_active_headless_backend() {
  local stable_backend_dir="${install_home}/lib/backends/headless"
  mkdir -p "$stable_backend_dir"
  rm -rf "${stable_backend_dir}/current"
  ln -s "../headless-${version}" "${stable_backend_dir}/current"
}

activate_current_version() {
  previous_version=""
  if [[ -L "$current_link" ]]; then
    previous_version="$(basename -- "$(readlink "$current_link")")"
  elif [[ -d "$current_link" ]]; then
    previous_version="$(basename -- "$(cd -- "$current_link" && pwd -P)")"
  fi

  rm -rf "$current_link"
  ln -s "$install_home" "$current_link"

  if [[ -n "$previous_version" && "$previous_version" != "$version" && -d "${versions_dir}/${previous_version}" ]]; then
    rm -rf "$previous_link"
    ln -s "${versions_dir}/${previous_version}" "$previous_link"
  fi
}

write_config() {
  local config_file="$1"

  mkdir -p "$(dirname -- "$config_file")"
  cat > "$config_file" <<TOML
[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[runtime]
defaultBackend = "headless"

[backends.headless]
enabled = true
TOML
}

write_install_manifest() {
  local manifest_file="$1"
  local timestamp="$2"
  local previous_version_json="null"
  if [[ -n "${previous_version:-}" && "$previous_version" != "$version" ]]; then
    previous_version_json="\"$(json_escape "$previous_version")\""
  fi

  mkdir -p "$(dirname -- "$manifest_file")"
  cat > "${manifest_file}.tmp.$$" <<JSON
{
  "tool": "kast",
  "installId": "$(json_escape "${install_id}")",
  "profile": "ubuntu-debian-headless",
  "activeVersion": "$(json_escape "$version")",
  "previousVersion": ${previous_version_json},
  "createdAt": "$(json_escape "$timestamp")",
  "updatedAt": "$(json_escape "$timestamp")",
  "roots": {
    "install": "$(json_escape "$root_dir")",
    "bin": "$(json_escape "$bin_dir")",
    "config": "$(json_escape "$config_home")",
    "data": "$(json_escape "$data_dir")",
    "cache": "$(json_escape "$cache_dir")",
    "runtime": "$(json_escape "$runtime_dir")",
    "logs": "$(json_escape "$logs_dir")",
    "locks": "$(json_escape "$locks_dir")"
  },
  "entrypoints": {
    "shim": "$(json_escape "$bin_path")",
    "activeBinary": "$(json_escape "${install_home}/bin/kast")"
  },
  "schemas": {
    "manifest": 1,
    "workspaceRegistry": 1,
    "symbolIndex": 3
  },
  "version": "$(json_escape "$normalized_version")",
  "backendVersion": "$(json_escape "$normalized_version")",
  "installedAt": "$(json_escape "$platform"):${version}",
  "platform": "$(json_escape "$platform")",
  "components": ["cli", "headless-backend", "manifest"],
  "backends": [
    {
      "name": "headless",
      "version": "$(json_escape "$normalized_version")",
      "installDir": "$(json_escape "$headless_root")",
      "runtimeLibsDir": "$(json_escape "$headless_runtime_libs_dir")",
      "ideaHome": "$(json_escape "$headless_idea_home")"
    }
  ],
  "managedPaths": ["bin", "lib", "cache", "logs"],
  "ownedPaths": [
    "$(json_escape "$bin_path")",
    "$(json_escape "$current_link")",
    "$(json_escape "$previous_link")",
    "$(json_escape "$versions_dir")",
    "$(json_escape "$runtime_dir")",
    "$(json_escape "$locks_dir")"
  ],
  "legacyPaths": [
    "$(json_escape "${HOME}/.kast")",
    "$(json_escape "${HOME}/.config/kast/daemons")",
    "$(json_escape "${HOME}/.kast/cache/daemons")"
  ],
  "shellRcPatches": [],
  "repos": [],
  "schemaVersion": 3
}
JSON
  mv "${manifest_file}.tmp.$$" "$manifest_file"
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
  [[ "$bundle_kind" == "headless" ]] || die "Unsupported Ubuntu/Debian bundle kind: $bundle_kind"
  platform="ubuntu-debian-headless-x86_64"
  artifact_name="kast-${platform}-${version}.tar.gz"
  root_dir="${KAST_UBUNTU_DEBIAN_ROOT:-${HOME}/.local/share/kast}"
  versions_dir="${root_dir}/versions"
  install_home="${versions_dir}/${version}"
  current_link="${root_dir}/current"
  previous_link="${root_dir}/previous"
  bin_dir="${KAST_UBUNTU_DEBIAN_BIN_DIR:-${HOME}/.local/bin}"
  bin_path="${bin_dir}/kast"
  config_home="${KAST_UBUNTU_DEBIAN_CONFIG_HOME:-${KAST_CONFIG_HOME:-${HOME}/.config/kast}}"
  config_file="${config_home}/config.toml"
  manifest_file="${root_dir}/install.json"
  data_dir="${root_dir}/state"
  cache_dir="${KAST_CACHE_HOME:-${HOME}/.cache/kast}"
  runtime_dir="${root_dir}/runtime"
  logs_dir="${HOME}/.local/state/kast/logs"
  locks_dir="${root_dir}/locks"
  base_url="${KAST_UBUNTU_DEBIAN_BASE_URL:-https://github.com/amichne/kast/releases/download/${version}}"
  headless_root="${install_home}/lib/backends/headless/current"
  headless_runtime_libs_dir="${headless_root}/runtime-libs"
  headless_idea_home="${headless_root}/idea-home"
  install_id="${KAST_INSTALL_ID:-kast-${platform}-${normalized_version}}"
  java_cmd="${KAST_JAVA_CMD:-java}"
}

detect_installed_backend_kind() {
  local source_dir="$1"
  if [[ -f "${source_dir}/lib/backends/headless-${version}/runtime-libs/classpath.txt" ]]; then
    printf '%s\n' "headless"
    return
  fi
  die "Bundle source missing backend runtime-libs/classpath.txt for ${version}"
}

validate_backend_source() {
  local source_dir="$1"
  local backend_kind="$2"

  [[ "$backend_kind" == "headless" ]] || die "Unsupported bundle backend kind: $backend_kind"
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
}

verify_install() {
  configure_paths
  export PATH="${bin_dir}:${PATH}"
  export KAST_INSTALL_ROOT="$root_dir"
  export KAST_CONFIG_HOME="$config_home"

  need_tool "$java_cmd"
  need_tool kast

  [[ -x "$bin_path" ]] || die "Expected executable kast at ${bin_path}"
  [[ -d "$install_home" ]] || die "Install root not found: ${install_home}"
  [[ -L "$current_link" ]] || die "Current install link not found: ${current_link}"
  [[ -f "$manifest_file" ]] || die "Kast install manifest not found: ${manifest_file}"
  [[ -f "$config_file" ]] || die "Kast config not found: ${config_file}"
  local installed_backend_kind
  installed_backend_kind="$(detect_installed_backend_kind "$install_home")"
  validate_backend_source "$install_home" "$installed_backend_kind"
  [[ -f "${headless_runtime_libs_dir}/classpath.txt" ]] \
    || die "Manifest-backed runtime libs missing: ${headless_runtime_libs_dir}"

  local version_output
  version_output="$("$bin_path" version)"
  printf '%s\n' "$version_output" | grep -Fq "$normalized_version" \
    || die "kast version does not contain ${normalized_version}: ${version_output}"

  [[ "$installed_backend_kind" == "headless" ]] || die "Installed backend kind must be headless"
  [[ -f "$bin_path" && ! -L "$bin_path" ]] || die "Expected headless ${bin_path} to be an executable shim"
  grep -Fq -- "-Didea.force.use.core.classloader=true" "$bin_path" \
    || die "Headless kast shim does not export the core classloader JVM option"
  grep -Fq 'defaultBackend = "headless"' "$config_file" \
    || die "config.toml does not default to headless runtime"
  grep -Fq "[backends.headless]" "$config_file" || die "config.toml does not include headless backend config"
  if grep -Eq '^(installRoot|binDir|libDir|cacheDir|logsDir|descriptorDir|socketDir|runtimeLibsDir|ideaHome|binaryPath) = ' "$config_file"; then
    die "config.toml must not write install-owned paths"
  fi
  grep -Fq "\"runtimeLibsDir\": \"${headless_runtime_libs_dir}\"" "$manifest_file" \
    || die "install.json does not point at ${headless_runtime_libs_dir}"
  grep -Fq "\"activeBinary\": \"${install_home}/bin/kast\"" "$manifest_file" \
    || die "install.json does not point at active binary"

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

  mkdir -p "$versions_dir" "$bin_dir" "$data_dir" "$cache_dir" "$runtime_dir" "$logs_dir" "$locks_dir"
  local staged_home="${versions_dir}/${version}.tmp.$$"
  rm -rf "$staged_home"
  copy_tree "$bundle_source_dir" "$staged_home"
  rm -rf "$install_home"
  mv "$staged_home" "$install_home"
  mkdir -p "$install_home/cache" "$install_home/logs"
  chmod +x "$install_home/bin/kast" "$install_home/scripts/install-ubuntu-debian.sh"
  link_active_headless_backend
  activate_current_version
  install_kast_entrypoint

  write_config "$config_file"
  write_install_manifest "$manifest_file" "unix:$(date +%s)"
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
