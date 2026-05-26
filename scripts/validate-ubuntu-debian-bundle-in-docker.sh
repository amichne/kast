#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

need_tool() {
  local tool_name="$1"
  command -v "$tool_name" >/dev/null 2>&1 || die "Missing required tool: $tool_name"
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

repo_root="$(resolve_repo_root)"
bundle_path="${BUNDLE_PATH:-}"
version="${KAST_UBUNTU_DEBIAN_VERSION:-}"
java_version="${KAST_UBUNTU_DEBIAN_JAVA_VERSION:-21}"
container_image="${KAST_UBUNTU_DEBIAN_CONTAINER_IMAGE:-ubuntu:24.04}"

[[ -n "$bundle_path" ]] || die "BUNDLE_PATH is required"
[[ -f "$bundle_path" ]] || die "Bundle not found: $bundle_path"
[[ -f "${bundle_path}.sha256" ]] || die "Bundle SHA-256 sidecar not found: ${bundle_path}.sha256"
[[ "$java_version" =~ ^[0-9]+$ ]] || die "KAST_UBUNTU_DEBIAN_JAVA_VERSION must be numeric: $java_version"

bundle_dir="$(cd -- "$(dirname -- "$bundle_path")" && pwd)"
bundle_abs="${bundle_dir}/$(basename -- "$bundle_path")"
case "$bundle_abs" in
  "${repo_root}/"*) bundle_rel="${bundle_abs#"${repo_root}"/}" ;;
  *) die "BUNDLE_PATH must be inside the repo root for Docker validation: $bundle_abs" ;;
esac

if [[ -z "$version" ]]; then
  bundle_name="$(basename -- "$bundle_path")"
  case "$bundle_name" in
    kast-ubuntu-debian-x86_64-*.tar.gz)
      version="${bundle_name#kast-ubuntu-debian-x86_64-}"
      version="${version%.tar.gz}"
      [[ -n "$version" ]] || die "Could not infer version from bundle name: $bundle_name"
      ;;
    *)
      die "Bundle name must match kast-ubuntu-debian-x86_64-<version>.tar.gz: $bundle_name"
      ;;
  esac
fi

need_tool docker

docker run --rm \
  --platform linux/amd64 \
  -v "${repo_root}:/workspace" \
  -e "KAST_UBUNTU_DEBIAN_VERSION=${version}" \
  -e "KAST_UBUNTU_DEBIAN_ARTIFACT_PATH=/workspace/${bundle_rel}" \
  -e "KAST_UBUNTU_DEBIAN_ROOT=/tmp/kast-ubuntu-debian-root" \
  -e "KAST_UBUNTU_DEBIAN_BIN_DIR=/tmp/kast-ubuntu-debian-bin" \
  -e "KAST_UBUNTU_DEBIAN_CONFIG_HOME=/tmp/kast-ubuntu-debian-config" \
  -e "KAST_UBUNTU_DEBIAN_JAVA_VERSION=${java_version}" \
  -w /workspace \
  "${container_image}" \
  bash -lc '
    set -Eeuo pipefail
    export DEBIAN_FRONTEND=noninteractive
    java_version="${KAST_UBUNTU_DEBIAN_JAVA_VERSION}"

    apt-get update
    apt-get install -y --no-install-recommends ca-certificates curl tar coreutils git openjdk-${java_version}-jdk-headless

    ./scripts/install-ubuntu-debian.sh install
    ./scripts/install-ubuntu-debian.sh verify

    export PATH="${KAST_UBUNTU_DEBIAN_BIN_DIR}:${PATH}"
    export KAST_CONFIG_HOME="${KAST_UBUNTU_DEBIAN_CONFIG_HOME}"

    java -version
    kast version
    kast doctor
    kast up --workspace-root=/workspace --wait-timeout-ms=120000 --accept-indexing=true
    kast status --workspace-root=/workspace --no-auto-start=true --accept-indexing=true
    kast capabilities --workspace-root=/workspace --no-auto-start=true --accept-indexing=true
    kast stop --workspace-root=/workspace || true
  '
