#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required tool: $1"
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
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

file_uri() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path

print(Path(sys.argv[1]).resolve().as_uri())
PY
}

assert_tree_read_only() {
  local root="$1"
  python3 - "$root" <<'PY'
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in [root, *root.rglob("*")]:
    mode = path.lstat().st_mode
    if mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH):
        raise SystemExit(f"path is writable: {path}")
PY
}

repo_root="$(resolve_repo_root)"
default_version="$(python3 - "$repo_root" <<'PY'
import json
import sys
from pathlib import Path

repo_root = Path(sys.argv[1])
print(json.loads((repo_root / "packaging/homebrew/release-state.json").read_text(encoding="utf-8"))["current_release"].removeprefix("v"))
PY
)"
version="${KAST_SETUP_KAST_SMOKE_VERSION:-$default_version}"
build_artifacts="${KAST_SETUP_KAST_SMOKE_BUILD:-true}"
cli_binary="${KAST_SETUP_KAST_SMOKE_CLI_BINARY:-${repo_root}/cli-rs/target/release/kast}"
backend_archive="${KAST_SETUP_KAST_SMOKE_BACKEND_ARCHIVE:-${repo_root}/dist/headless.zip}"
keep_scratch="${KAST_SETUP_KAST_SMOKE_KEEP:-false}"
verify_gradle_warm="${KAST_SETUP_KAST_SMOKE_GRADLE_WARM:-true}"

case "$build_artifacts" in
  true|false) ;;
  *) die "KAST_SETUP_KAST_SMOKE_BUILD must be true or false" ;;
esac
case "$keep_scratch" in
  true|false) ;;
  *) die "KAST_SETUP_KAST_SMOKE_KEEP must be true or false" ;;
esac
case "$verify_gradle_warm" in
  true|false) ;;
  *) die "KAST_SETUP_KAST_SMOKE_GRADLE_WARM must be true or false" ;;
esac

need_tool node
need_tool python3
need_tool tar
need_tool zip
need_tool zstd

if [[ "$build_artifacts" == "true" ]]; then
  need_tool cargo
  KAST_VERSION="$version" cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --release --locked
  "${repo_root}/kast.sh" build headless --headless-idea-home-profile=agent -Pversion="$version"
fi

[[ -x "$cli_binary" ]] || die "Host-compatible Kast CLI binary not found: $cli_binary"
if [[ ! -f "$backend_archive" ]]; then
  backend_archive="$(find "${repo_root}/backend-headless/build/distributions" -maxdepth 1 -name 'backend-headless-*-portable.zip' -print -quit 2>/dev/null || true)"
fi
[[ -n "$backend_archive" && -f "$backend_archive" ]] || die "Headless backend archive not found; run KAST_SETUP_KAST_SMOKE_BUILD=true $0"
[[ -f "${repo_root}/setup-kast/dist/index.js" ]] || die "setup-kast dist is missing; run npm --prefix setup-kast test"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-setup-real-artifacts.XXXXXX")"
scratch_dir="$(cd -- "$scratch_dir" && pwd)"
print_failure_diagnostics() {
  printf '\nsetup-kast real artifact smoke diagnostics\n' >&2
  printf 'scratch: %s\n' "$scratch_dir" >&2
  if find "$scratch_dir" -type f -name '*.log' -print -quit | grep -q .; then
    while IFS= read -r log_file; do
      printf '\n==> %s\n' "$log_file" >&2
      tail -200 "$log_file" >&2 || true
    done < <(find "$scratch_dir" -type f -name '*.log' | sort)
  else
    printf 'no log files found under scratch\n' >&2
  fi
}

cleanup() {
  local status="$?"
  if [[ "$status" -ne 0 ]]; then
    print_failure_diagnostics
  fi
  if [[ "$keep_scratch" == "true" ]]; then
    printf 'keeping scratch directory: %s\n' "$scratch_dir" >&2
  else
    chmod -R u+w "$scratch_dir" >/dev/null 2>&1 || true
    rm -rf "$scratch_dir"
  fi
}
trap cleanup EXIT

cli_zip="${scratch_dir}/kast-${version}-host.zip"
cli_zip_root="${scratch_dir}/cli-zip"
mkdir -p "$cli_zip_root"
cp "$cli_binary" "${cli_zip_root}/kast"
chmod 755 "${cli_zip_root}/kast"
(cd "$cli_zip_root" && zip -9 -q "$cli_zip" kast)

runtime_artifact="${scratch_dir}/kast-headless-linux-x64.tar.zst"
runtime_manifest="${scratch_dir}/kast-runtime-manifest.json"
"${repo_root}/scripts/package-devin-runtime.sh" \
  --cli-archive "$cli_zip" \
  --backend-archive "$backend_archive" \
  --version "$version" \
  --output "$runtime_artifact" \
  --manifest-output "$runtime_manifest"
runtime_sha="$(compute_sha256 "$runtime_artifact")"

gradle_home="${scratch_dir}/gradle-home"
mkdir -p "${gradle_home}/caches/modules-2/files-2.1/setup-kast/smoke"
printf '%s\n' "fixture" > "${gradle_home}/caches/modules-2/files-2.1/setup-kast/smoke/artifact.pom"
gradle_cache_artifact="${scratch_dir}/gradle-ro-dep-cache.tar.zst"
"${repo_root}/scripts/package-gradle-ro-cache.sh" \
  --gradle-user-home "$gradle_home" \
  --output "$gradle_cache_artifact"
gradle_cache_sha="$(compute_sha256 "$gradle_cache_artifact")"

install_root="${scratch_dir}/install"
home_root="${scratch_dir}/home"
env_file="${scratch_dir}/github-env"
path_file="${scratch_dir}/github-path"
mkdir -p "$home_root"
: > "$env_file"
: > "$path_file"

env \
  RUNNER_OS=Linux \
  RUNNER_ARCH=X64 \
  HOME="$home_root" \
  KAST_CACHE_HOME="${home_root}/.cache/kast" \
  KAST_CONFIG_HOME="${home_root}/.config/kast" \
  GITHUB_ENV="$env_file" \
  GITHUB_PATH="$path_file" \
  INPUT_VERSION="$version" \
  INPUT_ARTIFACT_URL="$(file_uri "$runtime_artifact")" \
  INPUT_ARTIFACT_SHA256="$runtime_sha" \
  INPUT_MANIFEST_URL="$(file_uri "$runtime_manifest")" \
  INPUT_INSTALL_DIR="$install_root" \
  INPUT_GRADLE_RO_CACHE_URL="$(file_uri "$gradle_cache_artifact")" \
  INPUT_GRADLE_RO_CACHE_SHA256="$gradle_cache_sha" \
  INPUT_FAIL_ON_CACHE_MISS=true \
  INPUT_STRICT=true \
  node "${repo_root}/setup-kast/dist/index.js"

installed_kast="${install_root}/current/bin/kast"
[[ -x "$installed_kast" ]] || die "Installed kast binary is missing: $installed_kast"
[[ -f "${install_root}/current/kast-runtime-manifest.json" ]] || die "Installed runtime manifest is missing"
[[ -d "${install_root}/cache/gradle-ro/modules-2" ]] || die "Installed Gradle read-only cache is missing"
assert_tree_read_only "${install_root}/cache/gradle-ro"

export KAST_HOME="${install_root}/current"
export KAST_CACHE_HOME="${home_root}/.cache/kast"
export KAST_CONFIG_HOME="${home_root}/.config/kast"
export GRADLE_RO_DEP_CACHE="${install_root}/cache/gradle-ro"
export GRADLE_USER_HOME="${home_root}/.gradle"
export PATH="${install_root}/current/bin:${PATH}"

verify_args=(
  --install-dir "$KAST_HOME"
  --workspace-id setup-kast-local-smoke
  --module-name setup-kast-local-smoke
  --wait-timeout-ms "${KAST_SETUP_KAST_SMOKE_WAIT_TIMEOUT_MS:-120000}"
)
if [[ "$verify_gradle_warm" == "true" ]]; then
  verify_args+=(--gradle-root "$repo_root")
fi
"${repo_root}/scripts/verify-setup-kast-install.sh" "${verify_args[@]}"

printf '%s\n' "setup-kast real artifact smoke passed"
