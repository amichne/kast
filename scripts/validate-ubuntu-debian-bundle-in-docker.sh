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
bundle_kind="${KAST_UBUNTU_DEBIAN_BUNDLE_KIND:-}"
java_version="${KAST_UBUNTU_DEBIAN_JAVA_VERSION:-21}"
container_image="${KAST_UBUNTU_DEBIAN_CONTAINER_IMAGE:-ubuntu:24.04}"
wait_timeout_ms="${KAST_UBUNTU_DEBIAN_WAIT_TIMEOUT_MS:-120000}"

[[ -n "$bundle_path" ]] || die "BUNDLE_PATH is required"
[[ -f "$bundle_path" ]] || die "Bundle not found: $bundle_path"
[[ -f "${bundle_path}.sha256" ]] || die "Bundle SHA-256 sidecar not found: ${bundle_path}.sha256"
[[ "$java_version" =~ ^[0-9]+$ ]] || die "KAST_UBUNTU_DEBIAN_JAVA_VERSION must be numeric: $java_version"
[[ "$wait_timeout_ms" =~ ^[0-9]+$ ]] || die "KAST_UBUNTU_DEBIAN_WAIT_TIMEOUT_MS must be numeric: $wait_timeout_ms"

bundle_dir="$(cd -- "$(dirname -- "$bundle_path")" && pwd)"
bundle_abs="${bundle_dir}/$(basename -- "$bundle_path")"
case "$bundle_abs" in
  "${repo_root}/"*) bundle_rel="${bundle_abs#"${repo_root}"/}" ;;
  *) die "BUNDLE_PATH must be inside the repo root for Docker validation: $bundle_abs" ;;
esac

if [[ -z "$version" ]]; then
  bundle_name="$(basename -- "$bundle_path")"
  case "$bundle_name" in
    kast-ubuntu-debian-headless-x86_64-*.tar.gz)
      version="${bundle_name#kast-ubuntu-debian-headless-x86_64-}"
      version="${version%.tar.gz}"
      [[ -n "$version" ]] || die "Could not infer version from bundle name: $bundle_name"
      ;;
    kast-ubuntu-debian-x86_64-*.tar.gz)
      version="${bundle_name#kast-ubuntu-debian-x86_64-}"
      version="${version%.tar.gz}"
      [[ -n "$version" ]] || die "Could not infer version from bundle name: $bundle_name"
      ;;
    *)
      die "Bundle name must match kast-ubuntu-debian-headless-x86_64-<version>.tar.gz: $bundle_name"
      ;;
  esac
fi

if [[ -z "$bundle_kind" ]]; then
  bundle_name="$(basename -- "$bundle_path")"
  case "$bundle_name" in
    kast-ubuntu-debian-headless-x86_64-*) bundle_kind="headless" ;;
    *) die "Bundle name must match a supported Ubuntu/Debian bundle: $bundle_name" ;;
  esac
fi
case "$bundle_kind" in
  headless) ;;
  *) die "KAST_UBUNTU_DEBIAN_BUNDLE_KIND must be headless: $bundle_kind" ;;
esac

need_tool docker

docker run --rm \
  --platform linux/amd64 \
  -v "${repo_root}:/workspace" \
  -e "KAST_UBUNTU_DEBIAN_VERSION=${version}" \
  -e "KAST_UBUNTU_DEBIAN_BUNDLE_KIND=${bundle_kind}" \
  -e "KAST_UBUNTU_DEBIAN_ARTIFACT_PATH=/workspace/${bundle_rel}" \
  -e "KAST_UBUNTU_DEBIAN_ROOT=/tmp/kast-ubuntu-debian-root" \
  -e "KAST_UBUNTU_DEBIAN_BIN_DIR=/tmp/kast-ubuntu-debian-bin" \
  -e "KAST_UBUNTU_DEBIAN_CONFIG_HOME=/tmp/kast-ubuntu-debian-config" \
  -e "KAST_UBUNTU_DEBIAN_JAVA_VERSION=${java_version}" \
  -e "KAST_UBUNTU_DEBIAN_WAIT_TIMEOUT_MS=${wait_timeout_ms}" \
  -e "KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE=/tmp/kast-ubuntu-debian-smoke-workspace" \
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
    backend_args=()
    if [[ "${KAST_UBUNTU_DEBIAN_BUNDLE_KIND}" == "headless" ]]; then
      backend_args=(--backend=headless)
    fi
    smoke_source_root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}/src/main/kotlin"
    mkdir -p "${smoke_source_root}"
    printf "%s\n" "package smoke" "class Smoke" > "${smoke_source_root}/Smoke.kt"

    java -version
    kast version
    kast doctor
    kast up \
      "${backend_args[@]}" \
      --workspace-root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}" \
      --source-roots="${smoke_source_root}" \
      --module-name=ubuntu-debian-smoke \
      --accept-indexing=true \
      --wait-timeout-ms="${KAST_UBUNTU_DEBIAN_WAIT_TIMEOUT_MS}"
    kast status "${backend_args[@]}" --workspace-root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}" --no-auto-start=true
    kast capabilities "${backend_args[@]}" --workspace-root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}" --accept-indexing=true --no-auto-start=true
    kast stop "${backend_args[@]}" --workspace-root="${KAST_UBUNTU_DEBIAN_SMOKE_WORKSPACE}" || true
  '
