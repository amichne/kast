#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required tool: $1"
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
  cat >&2 <<'USAGE'
Usage: scripts/package-gradle-ro-cache.sh --gradle-user-home <dir> --output <tar.zst>

Package $GRADLE_USER_HOME/caches/modules-2 as gradle-ro-dep-cache.tar.zst,
excluding Gradle lock files and GC metadata.
USAGE
}

gradle_user_home=""
output_path=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gradle-user-home)
      [[ $# -ge 2 ]] || die "Missing value for --gradle-user-home"
      gradle_user_home="$2"; shift 2 ;;
    --gradle-user-home=*)
      gradle_user_home="${1#--gradle-user-home=}"; shift ;;
    --output)
      [[ $# -ge 2 ]] || die "Missing value for --output"
      output_path="$2"; shift 2 ;;
    --output=*)
      output_path="${1#--output=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$gradle_user_home" ]] || { usage; die "--gradle-user-home is required"; }
[[ -n "$output_path" ]] || { usage; die "--output is required"; }

modules_dir="${gradle_user_home}/caches/modules-2"
[[ -d "$modules_dir" ]] || die "Gradle modules-2 cache not found: $modules_dir"

need_tool tar
need_tool zstd

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-gradle-ro-cache.XXXXXX")"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

mkdir -p "${tmp_dir}/gradle-ro" "$(dirname -- "$output_path")"
tar \
  -C "${gradle_user_home}/caches" \
  --exclude='*.lock' \
  --exclude='gc.properties' \
  -cf - modules-2 \
  | tar -C "${tmp_dir}/gradle-ro" -xf -

if find "${tmp_dir}/gradle-ro" \( -name '*.lock' -o -name 'gc.properties' \) -print -quit | grep -q .; then
  die "Gradle read-only cache package contains lock or GC metadata"
fi

rm -f "$output_path"
COPYFILE_DISABLE=1 tar --no-xattrs --zstd -C "$tmp_dir" -cf "$output_path" gradle-ro
sidecar_path="${output_path%.tar.zst}.sha256"
if [[ "$sidecar_path" == "$output_path" ]]; then
  sidecar_path="${output_path}.sha256"
fi
printf '%s  %s\n' "$(compute_sha256 "$output_path")" "$(basename -- "$output_path")" > "$sidecar_path"

printf 'Wrote %s\n' "$output_path" >&2
printf 'Wrote %s\n' "$sidecar_path" >&2
