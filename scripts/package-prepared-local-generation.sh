#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
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

usage() {
  printf '%s\n' \
    'Usage: scripts/package-prepared-local-generation.sh --source-root <checkout> --prepared-generation <directory> --output <tar.zst>' \
    >&2
}

source_root=""
prepared_generation=""
output_path=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root)
      [[ $# -ge 2 ]] || die "Missing value for --source-root"
      source_root="$2"; shift 2 ;;
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
[[ -n "$prepared_generation" ]] || { usage; die "--prepared-generation is required"; }
[[ -n "$output_path" ]] || { usage; die "--output is required"; }
[[ -d "$prepared_generation" ]] || die "Prepared generation not found: $prepared_generation"
[[ -x "${prepared_generation}/bin/kast" ]] || die "Prepared generation has no executable bin/kast"
command -v tar >/dev/null 2>&1 || die "Missing required tool: tar"
command -v zstd >/dev/null 2>&1 || die "Missing required tool: zstd"

[[ -d "${prepared_generation}/backend-headless" ]] \
  || die "Prepared generation has no backend-headless directory"
[[ -f "${prepared_generation}/source-snapshot.json" ]] \
  || die "Prepared generation has no source snapshot"

output_parent="$(dirname -- "$output_path")"
output_name="$(basename -- "$output_path")"
sidecar_path="${output_path%.tar.zst}.sha256"
if [[ "$sidecar_path" == "$output_path" ]]; then
  sidecar_path="${output_path}.sha256"
fi
mkdir -p "$output_parent"
temporary_output="${output_path}.tmp-$$"
cleanup() {
  [[ ! -e "$temporary_output" ]] || /bin/unlink "$temporary_output"
}
trap cleanup EXIT

prepared_parent="$(cd -- "$(dirname -- "$prepared_generation")" && pwd -P)"
prepared_name="$(basename -- "$prepared_generation")"
COPYFILE_DISABLE=1 tar --no-xattrs --zstd \
  -C "$prepared_parent" \
  -cf "$temporary_output" \
  "$prepared_name"
mv "$temporary_output" "$output_path"
artifact_sha="$(compute_sha256 "$output_path")"
printf '%s  %s\n' "$artifact_sha" "$output_name" >"$sidecar_path"

printf 'Wrote %s\n' "$output_path" >&2
printf 'Wrote %s\n' "$sidecar_path" >&2
