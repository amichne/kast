#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/scripts/lib.sh"

readonly SCRIPT_DIR
readonly REPO_ROOT="$SCRIPT_DIR"
readonly GRADLEW="${REPO_ROOT}/gradlew"
readonly DIST_ROOT="${REPO_ROOT}/dist"
readonly DIST_DIR="${DIST_ROOT}/kast"
readonly DIST_ZIP="${DIST_ROOT}/kast.zip"
readonly PORTABLE_DIST_DIR="${REPO_ROOT}/kast/build/portable-dist/kast"
readonly PORTABLE_ZIP_DIR="${REPO_ROOT}/kast/build/distributions"
readonly GRADLE_ARGS=()

tmp_dir=""

cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
}

trap cleanup EXIT

usage() {
  cat <<'USAGE' >&2
Usage: ./build.sh [options]

Builds the local kast CLI package from source, publishes:
  dist/kast
  dist/kast.zip

Options:
  --help, -h         Show this help.
USAGE
}

verify_prerequisites() {
  [[ -n "$REPO_ROOT" && -d "$REPO_ROOT" ]] || die "Could not determine the repo root"
  [[ -x "$GRADLEW" ]] || die "Missing executable gradlew at ${GRADLEW}"
}

run_gradle_build() {
  log_step "Building staged CLI tree and portable zip"
  (
    cd "$REPO_ROOT"
    "$GRADLEW" stageCliDist buildCliPortableZip "${GRADLE_ARGS[@]}"
  )
}

verify_cli_stage() {
  log_step "Verifying staged CLI tree in ${PORTABLE_DIST_DIR}"
  [[ -x "${PORTABLE_DIST_DIR}/kast" ]] || die "Missing staged kast launcher"
  [[ -d "${PORTABLE_DIST_DIR}/bin" ]] || die "Missing staged bin directory"
  [[ -x "${PORTABLE_DIST_DIR}/bin/kast" ]] || die "Missing staged kast native binary"
  [[ -d "${PORTABLE_DIST_DIR}/runtime-libs" ]] || die "Missing staged runtime-libs directory"
  [[ -f "${PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]] || die "Missing staged runtime classpath file"

  local jars=()
  shopt -s nullglob
  jars=("${PORTABLE_DIST_DIR}"/libs/kast-*-all.jar)
  shopt -u nullglob

  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${PORTABLE_DIST_DIR}/libs"
}

resolve_portable_zip() {
  local newest=""
  local candidate=""

  shopt -s nullglob
  for candidate in "${PORTABLE_ZIP_DIR}"/kast-*-portable.zip; do
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  shopt -u nullglob

  [[ -n "$newest" ]] || die "Expected a portable zip under ${PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

publish_dist_tree() {
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing staged CLI tree into ${DIST_DIR}"
  mkdir -p "$DIST_ROOT"
  cp -R "$PORTABLE_DIST_DIR" "${tmp_dir}/kast"
  rm -rf "$DIST_DIR"
  mv "${tmp_dir}/kast" "$DIST_DIR"
  log_success "Published ${DIST_DIR}"
}

publish_dist_zip() {
  local source_zip="$1"

  log_step "Publishing portable zip into ${DIST_ZIP}"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$DIST_ZIP"
  log_success "Published ${DIST_ZIP}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

verify_prerequisites

log_section "Kast local build"
run_gradle_build
verify_cli_stage

portable_zip="$(resolve_portable_zip)"
publish_dist_tree
publish_dist_zip "$portable_zip"

log_success "Local build is ready at ${DIST_DIR}"
log "Portable zip: ${DIST_ZIP}"
