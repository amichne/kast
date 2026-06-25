#!/usr/bin/env bash
set -Eeuo pipefail

_KAST_UBUNTU_DEBIAN_TMP_DIR=""

cleanup_tmp() {
  if [[ -n "${_KAST_UBUNTU_DEBIAN_TMP_DIR:-}" && -d "$_KAST_UBUNTU_DEBIAN_TMP_DIR" ]]; then
    rm -rf -- "$_KAST_UBUNTU_DEBIAN_TMP_DIR"
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

resolve_script_dir() {
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/install-ubuntu-debian.sh [install|verify]

Canonical non-Brew Kast bootstrap installer for Ubuntu/Debian x86_64 hosts.

Environment:
  KAST_UBUNTU_DEBIAN_VERSION        Version tag for download installs
  KAST_UBUNTU_DEBIAN_ARTIFACT_PATH  Local bundle tarball path
  KAST_UBUNTU_DEBIAN_BASE_URL       Release base URL for download installs
  KAST_UBUNTU_DEBIAN_ROOT           Install root, default ~/.local/share/kast
  KAST_UBUNTU_DEBIAN_BIN_DIR        Shim directory, default ~/.local/bin
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

validate_version() {
  local candidate="$1"
  [[ -n "$candidate" ]] || die "KAST_UBUNTU_DEBIAN_VERSION must not be empty"
  case "$candidate" in
    "."|".."|*[!A-Za-z0-9._+-]*)
      die "KAST_UBUNTU_DEBIAN_VERSION must be a single version label using only ASCII letters, digits, '.', '_', '-', or '+'"
      ;;
  esac
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

if payload.get("schemaVersion") != 2:
    raise SystemExit(1)
if payload.get("kind") != "KAST_INSTALL_BUNDLE":
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
    cp -- "$local_artifact" "$artifact_path"
    cp -- "${local_artifact}.sha256" "${artifact_path}.sha256"
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
  validate_version "$version"
  platform="ubuntu-debian-headless-x86_64"
  artifact_name="kast-${platform}-${version}.tar.gz"
  root_dir="${KAST_UBUNTU_DEBIAN_ROOT:-${HOME}/.local/share/kast}"
  bin_dir="${KAST_UBUNTU_DEBIAN_BIN_DIR:-${HOME}/.local/bin}"
  config_home="${KAST_UBUNTU_DEBIAN_CONFIG_HOME:-${KAST_CONFIG_HOME:-${HOME}/.config/kast}}"
  bin_path="${bin_dir}/kast"
  base_url="${KAST_UBUNTU_DEBIAN_BASE_URL:-https://github.com/amichne/kast/releases/download/${version}}"
  java_cmd="${KAST_JAVA_CMD:-java}"
}

resolve_bundle_source() {
  local script_dir
  script_dir="$(resolve_script_dir)"
  if [[ -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ]]; then
    cd -- "${script_dir}/.." && pwd
    return
  fi

  _KAST_UBUNTU_DEBIAN_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kast-ubuntu-debian-install.XXXXXX")"
  local artifact_path="${_KAST_UBUNTU_DEBIAN_TMP_DIR}/${artifact_name}"
  fetch_artifact "$artifact_path" "$artifact_name" "$base_url"
  source_from_artifact "$artifact_path" "${_KAST_UBUNTU_DEBIAN_TMP_DIR}/extract"
}

run_bundle_activation() {
  local bundle_source_dir="$1"
  local verify_only="$2"
  local bundled_kast="${bundle_source_dir}/bin/kast"
  [[ -x "$bundled_kast" ]] || die "Bundle source missing executable bin/kast"

  local activation_args=(
    release activate bundle
    --source "$bundle_source_dir"
    --install-root "$root_dir"
    --bin-dir "$bin_dir"
    --config-home "$config_home"
  )
  if [[ "$verify_only" == "true" ]]; then
    activation_args+=(--verify-only)
  fi

  KAST_INSTALL_ROOT="$root_dir" \
  KAST_CONFIG_HOME="$config_home" \
    "$bundled_kast" "${activation_args[@]}"
}

existing_install_matches() {
  local manifest_file="${root_dir}/install.json"
  [[ -x "$bin_path" ]] || return 1
  [[ -f "$manifest_file" ]] || return 1
  command -v python3 >/dev/null 2>&1 || return 1

  python3 - "$manifest_file" "$version" "$root_dir" <<'PY' || return 1
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
expected_version = sys.argv[2]
install_root = sys.argv[3]
install_home = f"{install_root}/versions/{expected_version}"

def fail(message):
    print(message, file=sys.stderr)
    raise SystemExit(1)

try:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception as error:
    fail(f"Could not read install manifest {manifest_path}: {error}")

if payload.get("tool") != "kast":
    fail("install.json tool is not kast")
if payload.get("profile") != "ubuntu-debian-headless":
    fail("install.json profile is not ubuntu-debian-headless")
if payload.get("activeVersion") != expected_version:
    fail(f"install.json activeVersion is {payload.get('activeVersion')!r}, expected {expected_version!r}")
if payload.get("roots", {}).get("install") != install_root:
    fail("install.json roots.install does not match the requested install root")
if payload.get("entrypoints", {}).get("activeBinary") != f"{install_home}/bin/kast":
    fail("install.json activeBinary does not match the requested version")

backends = payload.get("backends")
if not isinstance(backends, list):
    fail("install.json backends must be a list")
headless = next((entry for entry in backends if entry.get("name") == "headless"), None)
if headless is None:
    fail("install.json does not include the headless backend")
if headless.get("runtimeLibsDir") != f"{install_home}/lib/backends/headless/current/runtime-libs":
    fail("install.json headless runtimeLibsDir does not match the requested version")
if headless.get("ideaHome") != f"{install_home}/lib/backends/headless/current/idea-home":
    fail("install.json headless ideaHome does not match the requested version")
PY

  KAST_INSTALL_ROOT="$root_dir" \
  KAST_CONFIG_HOME="$config_home" \
    "$bin_path" ready >/dev/null
}

install_bundle() {
  assert_debian_like_host
  configure_paths
  need_tool "$java_cmd"

  if existing_install_matches >/dev/null 2>&1; then
    log "Kast Ubuntu/Debian bundle ${version} is already installed"
    return
  fi

  local bundle_source_dir
  need_tool tar
  bundle_source_dir="$(resolve_bundle_source)"
  run_bundle_activation "$bundle_source_dir" false
  log "Kast Ubuntu/Debian bundle ${version} installed at ${root_dir}/versions/${version}"
}

verify_install() {
  configure_paths
  need_tool "$java_cmd"

  local script_dir
  script_dir="$(resolve_script_dir)"
  if [[ -n "${KAST_UBUNTU_DEBIAN_ARTIFACT_PATH:-}" || ( -f "${script_dir}/../manifest.json" && -x "${script_dir}/../bin/kast" ) ]]; then
    need_tool tar
    local bundle_source_dir
    bundle_source_dir="$(resolve_bundle_source)"
    run_bundle_activation "$bundle_source_dir" true
    printf '%s\n' "Kast Ubuntu/Debian bundle ${version} verified"
    return
  fi

  need_tool python3
  existing_install_matches || die "Installed Kast bundle does not match requested version ${version}"
  printf '%s\n' "Kast Ubuntu/Debian bundle ${version} verified"
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
