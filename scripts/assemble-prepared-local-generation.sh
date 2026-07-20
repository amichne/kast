#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/assemble-prepared-local-generation.sh \
  --source-root <checkout> \
  --source-snapshot <json> \
  --cli-archive <zip> \
  --backend-archive <zip> \
  --prepared-generation <directory> \
  --output <prepared-generation.tar.zst>

Assemble one immutable CI runtime input from already-verified CLI and backend
artifacts, then package it once. This is not a developer-machine authority.
USAGE
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/.." && pwd
}

source_root=""
source_snapshot=""
cli_archive=""
backend_archive=""
prepared_generation=""
output_path=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root)
      [[ $# -ge 2 ]] || die "Missing value for --source-root"
      source_root="$2"; shift 2 ;;
    --source-snapshot)
      [[ $# -ge 2 ]] || die "Missing value for --source-snapshot"
      source_snapshot="$2"; shift 2 ;;
    --cli-archive)
      [[ $# -ge 2 ]] || die "Missing value for --cli-archive"
      cli_archive="$2"; shift 2 ;;
    --backend-archive)
      [[ $# -ge 2 ]] || die "Missing value for --backend-archive"
      backend_archive="$2"; shift 2 ;;
    --prepared-generation)
      [[ $# -ge 2 ]] || die "Missing value for --prepared-generation"
      prepared_generation="$2"; shift 2 ;;
    --output)
      [[ $# -ge 2 ]] || die "Missing value for --output"
      output_path="$2"; shift 2 ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$source_root" ]] || { usage; die "--source-root is required"; }
[[ -n "$source_snapshot" ]] || { usage; die "--source-snapshot is required"; }
[[ -n "$cli_archive" ]] || { usage; die "--cli-archive is required"; }
[[ -n "$backend_archive" ]] || { usage; die "--backend-archive is required"; }
[[ -n "$prepared_generation" ]] || { usage; die "--prepared-generation is required"; }
[[ -n "$output_path" ]] || { usage; die "--output is required"; }
[[ -d "$source_root" ]] || die "Source root not found: $source_root"
[[ -f "$source_snapshot" ]] || die "Source snapshot not found: $source_snapshot"
[[ -f "$cli_archive" ]] || die "CLI archive not found: $cli_archive"
[[ -f "$backend_archive" ]] || die "Backend archive not found: $backend_archive"

repo_root="$(resolve_repo_root)"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-prepared-generation.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

cli_extract="${scratch_dir}/cli"
backend_extract="${scratch_dir}/backend"
mkdir -p "$(dirname -- "$prepared_generation")" "$(dirname -- "$output_path")"
"${repo_root}/scripts/extract-safe-zip.py" "$cli_archive" "$cli_extract"
"${repo_root}/scripts/extract-safe-zip.py" "$backend_archive" "$backend_extract"

source_bound_cli="${cli_extract}/kast"
source_bound_backend="${backend_extract}/backend-headless"
[[ -x "$source_bound_cli" ]] || die "CLI archive must contain executable kast at its root"
[[ -d "$source_bound_backend" ]] || die "Backend archive must contain backend-headless/"

[[ ! -e "$prepared_generation" ]] || die "Prepared output already exists: $prepared_generation"
mkdir -p "${prepared_generation}/bin"
cp "$source_bound_cli" "${prepared_generation}/bin/kast"
chmod 755 "${prepared_generation}/bin/kast"
cp -R "$source_bound_backend" "${prepared_generation}/backend-headless"
cp "$source_snapshot" "${prepared_generation}/source-snapshot.json"

"${repo_root}/scripts/package-prepared-local-generation.sh" \
  --source-root "$source_root" \
  --prepared-generation "$prepared_generation" \
  --output "$output_path"

printf 'Prepared one immutable CI runtime input at %s\n' \
  "$prepared_generation" >&2
