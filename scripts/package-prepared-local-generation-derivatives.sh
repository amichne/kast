#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'Usage: scripts/package-prepared-local-generation-derivatives.sh --source-root <checkout> --prepared-generation-archive <tar.zst> --dist-directory <directory> --bundle-version <version> --runtime-version <version>' \
    >&2
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

source_root=""
prepared_generation_archive=""
dist_directory=""
bundle_version=""
runtime_version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root)
      [[ $# -ge 2 ]] || die 'Missing value for --source-root'
      source_root="$2"; shift 2 ;;
    --prepared-generation-archive)
      [[ $# -ge 2 ]] || die 'Missing value for --prepared-generation-archive'
      prepared_generation_archive="$2"; shift 2 ;;
    --dist-directory)
      [[ $# -ge 2 ]] || die 'Missing value for --dist-directory'
      dist_directory="$2"; shift 2 ;;
    --bundle-version)
      [[ $# -ge 2 ]] || die 'Missing value for --bundle-version'
      bundle_version="$2"; shift 2 ;;
    --runtime-version)
      [[ $# -ge 2 ]] || die 'Missing value for --runtime-version'
      runtime_version="$2"; shift 2 ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$source_root" ]] || { usage; die '--source-root is required'; }
[[ -n "$prepared_generation_archive" ]] || { usage; die '--prepared-generation-archive is required'; }
[[ -n "$dist_directory" ]] || { usage; die '--dist-directory is required'; }
[[ -n "$bundle_version" ]] || { usage; die '--bundle-version is required'; }
[[ -n "$runtime_version" ]] || { usage; die '--runtime-version is required'; }
[[ -d "$source_root" ]] || die "Source root not found: $source_root"
[[ -f "$prepared_generation_archive" ]] \
  || die "Prepared generation archive not found: $prepared_generation_archive"
command -v tar >/dev/null 2>&1 || die 'tar is required'
command -v zip >/dev/null 2>&1 || die 'zip is required'

repo_root="$(resolve_repo_root)"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-prepared-derivatives.XXXXXX")"
cleanup() {
  find "$scratch_dir" -depth -delete
}
trap cleanup EXIT

prepared_parent="${scratch_dir}/prepared"
mkdir -p "$prepared_parent" "$dist_directory"
tar --zstd --extract --no-same-owner \
  --file "$prepared_generation_archive" \
  --directory "$prepared_parent"

prepared_generation=""
prepared_candidate_count=0
while IFS= read -r candidate; do
  prepared_generation="$candidate"
  prepared_candidate_count=$((prepared_candidate_count + 1))
done < <(find "$prepared_parent" -mindepth 1 -maxdepth 1 -type d -print)
[[ "$prepared_candidate_count" -eq 1 ]] \
  || die 'Prepared archive must contain exactly one generation directory'

prepared_cli="${prepared_generation}/bin/kast"
prepared_backend="${prepared_generation}/backend-headless"
[[ -x "$prepared_cli" ]] || die 'Prepared generation does not contain executable bin/kast'
[[ -d "$prepared_backend" ]] || die 'Prepared generation does not contain backend-headless/'
"$prepared_cli" --output json developer local verify \
  --source-root "$source_root" \
  --prepared-generation "$prepared_generation" \
  >/dev/null

cli_staging="${scratch_dir}/cli"
backend_staging="${scratch_dir}/backend"
mkdir -p "$cli_staging" "${backend_staging}/backend-headless"
cp "$prepared_cli" "${cli_staging}/kast"
chmod 755 "${cli_staging}/kast"
cp -R "${prepared_backend}/." "${backend_staging}/backend-headless/"
cli_archive="${scratch_dir}/kast-v0.0.0-ci-linux-x64.zip"
backend_archive="${scratch_dir}/kast-local-source-bound-backend.zip"
(cd "$cli_staging" && zip -X -9 -q "$cli_archive" kast)
(cd "$backend_staging" && zip -X -9 -q -r "$backend_archive" backend-headless)

bundle_asset="${dist_directory}/kast-ubuntu-debian-headless-x86_64-${bundle_version}.tar.gz"
"$prepared_cli" developer release package ubuntu-debian-bundle \
  --repo-root "$source_root" \
  --cli-archive "$cli_archive" \
  --backend-archive "$backend_archive" \
  --version "$bundle_version" \
  --bundle-output "$bundle_asset"

"${repo_root}/scripts/package-headless-runtime.sh" \
  --cli-archive "$cli_archive" \
  --backend-archive "$backend_archive" \
  --version "$runtime_version" \
  --output "${dist_directory}/kast-headless-linux-x64.tar.zst" \
  --manifest-output "${dist_directory}/kast-runtime-manifest.json"

gradle_seed="${scratch_dir}/gradle-ro-seed"
mkdir -p "${gradle_seed}/caches/modules-2/files-2.1/headless/smoke"
printf '%s\n' 'fixture' > "${gradle_seed}/caches/modules-2/files-2.1/headless/smoke/artifact.pom"
"${repo_root}/scripts/package-gradle-ro-cache.sh" \
  --gradle-user-home "$gradle_seed" \
  --output "${dist_directory}/gradle-ro-dep-cache.tar.zst"

printf 'Derived Linux packages from verified prepared generation %s\n' \
  "$prepared_generation" >&2
