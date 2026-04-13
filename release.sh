#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/scripts/lib.sh"

readonly SCRIPT_DIR
readonly REPO_ROOT="$SCRIPT_DIR"
readonly GRADLEW="${REPO_ROOT}/gradlew"
readonly PORTABLE_ZIP_DIR="${REPO_ROOT}/kast/build/distributions"

usage() {
  cat <<'USAGE' >&2
Usage: ./release.sh --tag <version> --platform-id <id> [options]

Prepares a release asset from the portable distribution zip.

Options:
  --tag <version>       Release tag (e.g. v1.2.3). Required.
  --platform-id <id>    Platform identifier (e.g. linux-x64, macos-arm64). Required.
  --jvm-only            Build JVM-only distribution (no native binary).
  --skip-build          Skip the Gradle build (use existing portable zip).
  --help, -h            Show this help.

Examples:
  ./release.sh --tag v1.0.0 --platform-id linux-x64
  ./release.sh --tag v1.0.0 --platform-id macos-arm64 --skip-build
  ./release.sh --tag v1.0.0 --platform-id linux-x64-jvm --jvm-only
USAGE
}

tag=""
platform_id=""
skip_build="false"
jvm_only="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag=*)
      tag="${1#*=}"
      shift
      ;;
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"
      shift 2
      ;;
    --platform-id=*)
      platform_id="${1#*=}"
      shift
      ;;
    --platform-id)
      [[ $# -ge 2 ]] || die "Missing value for --platform-id"
      platform_id="$2"
      shift 2
      ;;
    --jvm-only)
      jvm_only="true"
      shift
      ;;
    --skip-build)
      skip_build="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$tag" ]] || die "Missing required --tag"
[[ -n "$platform_id" ]] || die "Missing required --platform-id"
[[ -x "$GRADLEW" ]] || die "Missing executable gradlew at ${GRADLEW}"

run_gradle_build() {
  local extra_args=()
  if [[ "$jvm_only" == "true" ]]; then
    extra_args+=("-PjvmOnly=true")
  fi
  (
    cd "$REPO_ROOT"
    "$GRADLEW" :kast:portableDistZip "${extra_args[@]+"${extra_args[@]}"}"
  )
}

if [[ "$skip_build" != "true" ]]; then
  log_section "Building portable distribution"
  if ! run_gradle_build; then
    log_note "Gradle build failed; stopping daemon and retrying with --no-daemon"
    "$GRADLEW" --stop >/dev/null 2>&1 || true
    retry_extra=()
    if [[ "$jvm_only" == "true" ]]; then
      retry_extra+=("-PjvmOnly=true")
    fi
    (
      cd "$REPO_ROOT"
      "$GRADLEW" --no-daemon :kast:portableDistZip "${retry_extra[@]+"${retry_extra[@]}"}"
    )
  fi
fi

resolve_portable_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PORTABLE_ZIP_DIR}"/kast-*-portable.zip; do
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "No portable zip found under ${PORTABLE_ZIP_DIR}. Run without --skip-build."
  printf '%s\n' "$newest"
}

source_zip="$(resolve_portable_zip)"

dist_root="${REPO_ROOT}/dist"
asset_name="kast-${tag}-${platform_id}.zip"
asset_path="${dist_root}/${asset_name}"

log_section "Preparing release asset"
mkdir -p "$dist_root"
cp "$source_zip" "$asset_path"

digest="$(compute_sha256 "$asset_path")"

printf '%s  %s\n' "$digest" "$asset_name" >> "${dist_root}/checksums.txt"

log_success "Release asset: ${asset_path}"
log "SHA-256: ${digest}"
