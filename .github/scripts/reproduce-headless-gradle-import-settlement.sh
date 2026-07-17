#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'Usage: reproduce-headless-gradle-import-settlement.sh --archive <prepared-generation.tar.zst> [--iterations <count>] [--evidence-dir <directory>]' \
    >&2
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

archive=""
iterations=5
evidence_dir=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --archive)
      [[ $# -ge 2 ]] || die '--archive requires a value'
      archive="$2"
      shift 2
      ;;
    --iterations)
      [[ $# -ge 2 ]] || die '--iterations requires a value'
      iterations="$2"
      shift 2
      ;;
    --evidence-dir)
      [[ $# -ge 2 ]] || die '--evidence-dir requires a value'
      evidence_dir="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage
      die "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$archive" ]] || { usage; die '--archive is required'; }
[[ -f "$archive" ]] || die "Prepared generation archive not found: $archive"
[[ "$iterations" =~ ^[1-9][0-9]*$ ]] || die "--iterations must be a positive integer: $iterations"

repo_root="$(resolve_repo_root)"
if [[ -z "$evidence_dir" ]]; then
  evidence_dir="${repo_root}/build/headless-gradle-settlement-reproducer"
fi
mkdir -p "$evidence_dir"

for ((iteration = 1; iteration <= iterations; iteration += 1)); do
  printf 'Headless Gradle settlement iteration %d/%d\n' "$iteration" "$iterations"
  KAST_PREPARED_GENERATION_ARCHIVE="$archive" \
  KAST_SEMANTIC_FIXTURE_EVIDENCE_DIR="${evidence_dir}/iteration-${iteration}" \
    "${repo_root}/.github/scripts/test-local-development-semantic-fixture.sh"
done

printf 'Completed %d deterministic headless Gradle settlement iterations; evidence: %s\n' \
  "$iterations" \
  "$evidence_dir"
