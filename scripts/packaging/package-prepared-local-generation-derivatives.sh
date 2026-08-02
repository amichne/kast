#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'Usage: scripts/packaging/package-prepared-local-generation-derivatives.sh --kind ubuntu-debian-bundle --source-root <checkout> --prepared-generation-archive <tar.zst> --dist-directory <directory> --bundle-version <version>' \
    >&2
}

package_kind=""
source_root=""
prepared_generation_archive=""
dist_directory=""
bundle_version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --kind)
      [[ $# -ge 2 ]] || die 'Missing value for --kind'
      package_kind="$2"; shift 2 ;;
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
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$package_kind" ]] || { usage; die '--kind is required'; }
[[ "$package_kind" == "ubuntu-debian-bundle" ]] \
  || { usage; die "Unsupported package kind: $package_kind"; }
[[ -n "$bundle_version" ]] \
  || { usage; die '--bundle-version is required for ubuntu-debian-bundle'; }
[[ -n "$source_root" ]] || { usage; die '--source-root is required'; }
[[ -n "$prepared_generation_archive" ]] || { usage; die '--prepared-generation-archive is required'; }
[[ -n "$dist_directory" ]] || { usage; die '--dist-directory is required'; }
[[ -d "$source_root" ]] || die "Source root not found: $source_root"
[[ -f "$prepared_generation_archive" ]] \
  || die "Prepared generation archive not found: $prepared_generation_archive"
command -v tar >/dev/null 2>&1 || die 'tar is required'
command -v zip >/dev/null 2>&1 || die 'zip is required'

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

prepared_control="${prepared_generation}/libexec/kastctl"
prepared_agent="${prepared_generation}/bin/kast"
prepared_backend="${prepared_generation}/backend-headless"
[[ -x "$prepared_control" ]] || die 'Prepared generation does not contain executable libexec/kastctl'
[[ -x "$prepared_agent" ]] || die 'Prepared generation does not contain executable bin/kast'
cmp -s "$prepared_control" "$prepared_agent" \
  || die 'Prepared generation entrypoints must be byte-identical'
[[ -d "$prepared_backend" ]] || die 'Prepared generation does not contain backend-headless/'
cli_staging="${scratch_dir}/cli"
backend_staging="${scratch_dir}/backend"
mkdir -p "$cli_staging" "${backend_staging}/backend-headless"
cp "$prepared_control" "${cli_staging}/kastctl"
cp "$prepared_agent" "${cli_staging}/kast"
chmod 755 "${cli_staging}/kastctl" "${cli_staging}/kast"
cp -R "${prepared_backend}/." "${backend_staging}/backend-headless/"
cli_archive="${scratch_dir}/kast-v0.0.0-ci-linux-x64.zip"
backend_archive="${scratch_dir}/kast-local-source-bound-backend.zip"
(cd "$cli_staging" && zip -X -0 -q "$cli_archive" kastctl kast)
(cd "$backend_staging" && zip -X -0 -q -r "$backend_archive" backend-headless)

bundle_asset="${dist_directory}/kast-ubuntu-debian-headless-x86_64-${bundle_version}.tar.gz"
"$prepared_control" developer release package ubuntu-debian-bundle \
  --repo-root "$source_root" \
  --cli-archive "$cli_archive" \
  --backend-archive "$backend_archive" \
  --version "$bundle_version" \
  --bundle-output "$bundle_asset"

printf 'Derived %s package from immutable CI runtime input %s\n' \
  "$package_kind" "$prepared_generation" >&2
