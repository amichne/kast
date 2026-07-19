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
  --prepared-generations <parent-directory> \
  --output <prepared-generation.tar.zst>

Attest one already-built CLI and backend, publish one immutable prepared
generation, verify it with its exact CLI, and package that generation once.
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
prepared_generations=""
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
    --prepared-generations)
      [[ $# -ge 2 ]] || die "Missing value for --prepared-generations"
      prepared_generations="$2"; shift 2 ;;
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
[[ -n "$prepared_generations" ]] || { usage; die "--prepared-generations is required"; }
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
provenance_directory="${scratch_dir}/provenance"
prepared_generation_selection="${scratch_dir}/prepared-generation-selection"
mkdir -p "$provenance_directory" "$prepared_generations" "$(dirname -- "$output_path")"
"${repo_root}/scripts/extract-safe-zip.py" "$cli_archive" "$cli_extract"
"${repo_root}/scripts/extract-safe-zip.py" "$backend_archive" "$backend_extract"

source_bound_cli="${cli_extract}/kast"
source_bound_backend="${backend_extract}/backend-headless"
[[ -x "$source_bound_cli" ]] || die "CLI archive must contain executable kast at its root"
[[ -d "$source_bound_backend" ]] || die "Backend archive must contain backend-headless/"

cli_provenance="${provenance_directory}/cli.json"
backend_provenance="${provenance_directory}/backend.json"
"$source_bound_cli" --output json developer local attest \
  --source-root "$source_root" \
  --expected-source-snapshot "$source_snapshot" \
  --artifact-kind cli \
  --artifact "$source_bound_cli" \
  --output-file "$cli_provenance" \
  >/dev/null
"$source_bound_cli" --output json developer local attest \
  --source-root "$source_root" \
  --expected-source-snapshot "$source_snapshot" \
  --artifact-kind headless-backend \
  --artifact "$source_bound_backend" \
  --output-file "$backend_provenance" \
  >/dev/null

"$source_bound_cli" --output json developer local prepare \
  --source-root "$source_root" \
  --expected-source-snapshot "$source_snapshot" \
  --cli-binary "$source_bound_cli" \
  --cli-provenance "$cli_provenance" \
  --backend-directory "$source_bound_backend" \
  --backend-provenance "$backend_provenance" \
  --output-directory "$prepared_generations" \
  --selection-file "$prepared_generation_selection" \
  >/dev/null

[[ -f "$prepared_generation_selection" ]] \
  || die "Prepared-generation selection was not published"
IFS= read -r selected_prepared_generation < "$prepared_generation_selection" \
  || die "Prepared-generation selection is empty"
[[ -n "$selected_prepared_generation" ]] \
  || die "Prepared-generation selection is blank"
[[ -d "$selected_prepared_generation" ]] \
  || die "Selected prepared generation not found: $selected_prepared_generation"
prepared_generations="$(cd -- "$prepared_generations" && pwd -P)"
selected_prepared_generation="$(cd -- "$selected_prepared_generation" && pwd -P)"
[[ "$(dirname -- "$selected_prepared_generation")" == "$prepared_generations" ]] \
  || die "Selected prepared generation escaped its parent: $selected_prepared_generation"
[[ -f "${selected_prepared_generation}/generation.json" ]] \
  || die "Selected prepared generation has no ledger: $selected_prepared_generation"

"${repo_root}/scripts/package-prepared-local-generation.sh" \
  --source-root "$source_root" \
  --prepared-generation "$selected_prepared_generation" \
  --output "$output_path"

printf 'Prepared one source-attested local generation at %s\n' \
  "$selected_prepared_generation" >&2
