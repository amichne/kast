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
  --skip-build          Skip the Gradle build (use existing portable zip).
  --help, -h            Show this help.

Examples:
  ./release.sh --tag v1.0.0 --platform-id linux-x64
  ./release.sh --tag v1.0.0 --platform-id macos-arm64 --skip-build
USAGE
}

tag=""
platform_id=""
skip_build="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"
      shift 2
      ;;
    --platform-id)
      [[ $# -ge 2 ]] || die "Missing value for --platform-id"
      platform_id="$2"
      shift 2
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

if [[ "$skip_build" != "true" ]]; then
  log_section "Building portable distribution"
  (
    cd "$REPO_ROOT"
    "$GRADLEW" :kast:portableDistZip
  )
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

digest="$(shasum -a 256 "$asset_path" | awk '{print $1}')"

log_success "Release asset: ${asset_path}"
log "SHA-256: ${digest}"
