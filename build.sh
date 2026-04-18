#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/scripts/lib.sh"

readonly SCRIPT_DIR
readonly REPO_ROOT="$SCRIPT_DIR"
readonly GRADLEW="${REPO_ROOT}/gradlew"
readonly DIST_ROOT="${REPO_ROOT}/dist"
readonly PORTABLE_DIST_DIR="${REPO_ROOT}/kast-cli/build/portable-dist/kast-cli"
readonly PORTABLE_ZIP_DIR="${REPO_ROOT}/kast-cli/build/distributions"
readonly PLUGIN_DIST_DIR="${REPO_ROOT}/backend-intellij/build/distributions"
readonly BACKEND_PORTABLE_DIST_DIR="${REPO_ROOT}/backend-standalone/build/portable-dist/backend-standalone"
readonly BACKEND_PORTABLE_ZIP_DIR="${REPO_ROOT}/backend-standalone/build/distributions"

readonly ALL_TARGETS=(cli plugin backend)

tmp_dir=""
selected_targets=()

cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
}

trap cleanup EXIT

usage() {
  cat <<'USAGE' >&2
Usage: ./build.sh [target...] [options]

Builds selected Kast components and publishes artifacts to dist/.

Targets (positional, repeatable):
  cli          Native CLI binary + wrapper  → dist/cli/   dist/cli.zip
  plugin       IntelliJ plugin zip          → dist/plugin.zip
  backend      Backend-standalone server    → dist/backend/  dist/backend.zip

Options:
  --all            Build all targets.
  --help, -h       Show this help.

When no targets are supplied and a TTY is available, fzf is used for
interactive multi-selection (tab to toggle, ctrl-a to select all).
Falls back to building all targets when fzf is not installed.
USAGE
}

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------

verify_prerequisites() {
  [[ -n "$REPO_ROOT" && -d "$REPO_ROOT" ]] || die "Could not determine the repo root"
  [[ -x "$GRADLEW" ]] || die "Missing executable gradlew at ${GRADLEW}"
}

ensure_healthy_daemon() {
  if ! "$GRADLEW" --status >/dev/null 2>&1; then
    log_step "Stopping stale Gradle daemons"
    "$GRADLEW" --stop >/dev/null 2>&1 || true
  fi
}

# ---------------------------------------------------------------------------
# Interactive target selection
# ---------------------------------------------------------------------------

select_targets_fzf() {
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && selected_targets+=("$line")
  done < <(
    printf '%s\n' "${ALL_TARGETS[@]}" | fzf \
      --multi \
      --prompt="Select build targets: " \
      --header="<tab> toggle  <ctrl-a> select all  <enter> confirm" \
      --bind="ctrl-a:select-all" \
      --height="~50%" \
      --layout=reverse \
      --border=rounded
  )
  [[ "${#selected_targets[@]}" -gt 0 ]] || die "No targets selected"
}

select_targets_interactive() {
  if command -v fzf >/dev/null 2>&1 && can_prompt; then
    select_targets_fzf
  else
    log_note "fzf not found or no TTY — building all targets"
    selected_targets=("${ALL_TARGETS[@]}")
  fi
}

# ---------------------------------------------------------------------------
# Gradle helpers
# ---------------------------------------------------------------------------

run_gradle_tasks() {
  (
    cd "$REPO_ROOT"
    "$GRADLEW" "$@"
  )
}

run_gradle_tasks_with_retry() {
  if run_gradle_tasks "$@"; then
    return 0
  fi
  log_note "Gradle build failed; stopping daemon and retrying"
  "$GRADLEW" --stop >/dev/null 2>&1 || true
  run_gradle_tasks "$@"
}

# ---------------------------------------------------------------------------
# CLI build + publish  (shared by 'cli' and 'cli-jvm' targets)
# ---------------------------------------------------------------------------

build_cli_gradle() {
  local gradle_args=(stageCliDist buildCliPortableZip)
  # Wipe intermediate outputs so a prior CLI build doesn't bleed into this one.
  rm -rf "${REPO_ROOT}/kast-cli/build/portable-dist" "${REPO_ROOT}/kast-cli/build/distributions"
  run_gradle_tasks_with_retry "${gradle_args[@]}"
}

verify_cli_stage() {
  log_step "Verifying staged CLI tree in ${PORTABLE_DIST_DIR}"
  [[ -x "${PORTABLE_DIST_DIR}/kast-cli" ]] || die "Missing staged kast-cli launcher"
  [[ -d "${PORTABLE_DIST_DIR}/runtime-libs" ]] || die "Missing staged runtime-libs directory"
  [[ -f "${PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]] || die "Missing staged runtime classpath file"

  local jars=()
  shopt -s nullglob
  jars=("${PORTABLE_DIST_DIR}"/libs/kast-cli-*-all.jar)
  shopt -u nullglob
  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${PORTABLE_DIST_DIR}/libs"
}

resolve_portable_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PORTABLE_ZIP_DIR}"/kast-cli-*-portable.zip; do
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a portable zip under ${PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

publish_cli() {
  local target_name="$1"
  local dist_dir="${DIST_ROOT}/${target_name}"
  local dist_zip="${DIST_ROOT}/${target_name}.zip"

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing CLI tree into ${dist_dir}"
  mkdir -p "$DIST_ROOT"
  cp -R "$PORTABLE_DIST_DIR" "${tmp_dir}/${target_name}"
  rm -rf "$dist_dir"
  mv "${tmp_dir}/${target_name}" "$dist_dir"
  log_success "Published ${dist_dir}"

  local source_zip
  source_zip="$(resolve_portable_zip)"
  log_step "Publishing portable zip into ${dist_zip}"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"

  rm -rf "$tmp_dir"
  tmp_dir=""
}

build_and_publish_cli() {
  local target="cli"
  log_section "Building target: ${target}"
  build_cli_gradle
  verify_cli_stage
  publish_cli "$target"
}

# ---------------------------------------------------------------------------
# Plugin build + publish
# ---------------------------------------------------------------------------

resolve_plugin_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${PLUGIN_DIST_DIR}"/*.zip; do
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a plugin zip under ${PLUGIN_DIST_DIR}"
  printf '%s\n' "$newest"
}

build_and_publish_plugin() {
  log_section "Building target: plugin"
  run_gradle_tasks_with_retry buildIntellijPlugin

  local source_zip
  local dist_zip="${DIST_ROOT}/plugin.zip"
  source_zip="$(resolve_plugin_zip)"
  log_step "Publishing plugin zip into ${dist_zip}"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"
}

# ---------------------------------------------------------------------------
# Backend build + publish
# ---------------------------------------------------------------------------

resolve_backend_zip() {
  local newest="" candidate=""
  shopt -s nullglob
  for candidate in "${BACKEND_PORTABLE_ZIP_DIR}"/backend-standalone-*-portable.zip; do
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  shopt -u nullglob
  [[ -n "$newest" ]] || die "Expected a backend portable zip under ${BACKEND_PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

verify_backend_stage() {
  log_step "Verifying staged backend tree in ${BACKEND_PORTABLE_DIST_DIR}"
  [[ -x "${BACKEND_PORTABLE_DIST_DIR}/backend-standalone" ]] || die "Missing staged backend-standalone launcher"
  [[ -d "${BACKEND_PORTABLE_DIST_DIR}/runtime-libs" ]] || die "Missing staged runtime-libs directory"
  [[ -f "${BACKEND_PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]] || die "Missing staged runtime classpath file"

  local jars=()
  shopt -s nullglob
  jars=("${BACKEND_PORTABLE_DIST_DIR}"/libs/backend-standalone-*-all.jar)
  shopt -u nullglob
  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${BACKEND_PORTABLE_DIST_DIR}/libs"
}

build_and_publish_backend() {
  log_section "Building target: backend"
  rm -rf "${REPO_ROOT}/backend-standalone/build/portable-dist" "${REPO_ROOT}/backend-standalone/build/distributions"
  run_gradle_tasks_with_retry stageBackendDist buildBackendPortableZip

  verify_backend_stage

  local dist_dir="${DIST_ROOT}/backend"
  local dist_zip="${DIST_ROOT}/backend.zip"

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing backend tree into ${dist_dir}"
  mkdir -p "$DIST_ROOT"
  cp -R "$BACKEND_PORTABLE_DIST_DIR" "${tmp_dir}/backend"
  rm -rf "$dist_dir"
  mv "${tmp_dir}/backend" "$dist_dir"
  log_success "Published ${dist_dir}"

  local source_zip
  source_zip="$(resolve_backend_zip)"
  log_step "Publishing portable zip into ${dist_zip}"
  cp "$source_zip" "$dist_zip"
  log_success "Published ${dist_zip}"

  rm -rf "$tmp_dir"
  tmp_dir=""
}

# ---------------------------------------------------------------------------
# OpenAPI spec generation + publish
# ---------------------------------------------------------------------------

build_and_publish_openapi() {
  log_section "Generating OpenAPI specification"
  run_gradle_tasks_with_retry stageOpenApiSpec
  local dist_spec="${DIST_ROOT}/openapi.yaml"
  [[ -f "$dist_spec" ]] || die "Missing generated OpenAPI spec at ${dist_spec}"
  log_success "openapi  →  ${dist_spec}"
}

# ---------------------------------------------------------------------------
# Stale-output cleanup
# ---------------------------------------------------------------------------

clean_stale_outputs() {
  local dist_dir="${DIST_ROOT}/cli"
  if [[ -d "$dist_dir" ]]; then
    if [[ ! -f "${dist_dir}/kast-cli" || ! -d "${dist_dir}/runtime-libs" ]]; then
      log_step "Removing incomplete ${dist_dir} from a previous run"
      rm -rf "$dist_dir"
    fi
  fi

  local backend_dir="${DIST_ROOT}/backend"
  if [[ -d "$backend_dir" ]]; then
    if [[ ! -f "${backend_dir}/backend-standalone" || ! -d "${backend_dir}/runtime-libs" ]]; then
      log_step "Removing incomplete ${backend_dir} from a previous run"
      rm -rf "$backend_dir"
    fi
  fi

  shopt -s nullglob
  local stale
  for stale in "${TMPDIR:-/tmp}"/kast-build.??????; do
    [[ -d "$stale" ]] && rm -rf "$stale"
  done
  shopt -u nullglob
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [[ $# -gt 0 ]]; do
  case "$1" in
    cli|plugin|backend)
      selected_targets+=("$1")
      shift
      ;;
    --all)
      selected_targets=("${ALL_TARGETS[@]}")
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

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

verify_prerequisites
clean_stale_outputs

if [[ "${#selected_targets[@]}" -eq 0 ]]; then
  select_targets_interactive
fi

log_section "Kast local build"
ensure_healthy_daemon

for target in "${selected_targets[@]}"; do
  case "$target" in
    cli)         build_and_publish_cli ;;
    plugin)      build_and_publish_plugin ;;
    backend)     build_and_publish_backend ;;
  esac
done

# Always publish the OpenAPI spec alongside other artifacts
build_and_publish_openapi

log_section "Build complete"
for target in "${selected_targets[@]}"; do
  case "$target" in
    cli)
      log_success "${target}  →  ${DIST_ROOT}/${target}/  ${DIST_ROOT}/${target}.zip"
      ;;
    plugin)
      log_success "plugin  →  ${DIST_ROOT}/plugin.zip"
      ;;
    backend)
      log_success "backend  →  ${DIST_ROOT}/backend/  ${DIST_ROOT}/backend.zip"
      ;;
  esac
done
