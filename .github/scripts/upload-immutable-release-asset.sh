#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/upload-immutable-release-asset.sh --tag <tag> --asset <path>

Upload one GitHub release asset exactly once. If the named asset already exists,
download it and prove byte identity instead of replacing it.
USAGE
}

tag=""
asset=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"
      shift 2
      ;;
    --tag=*)
      tag="${1#--tag=}"
      shift
      ;;
    --asset)
      [[ $# -ge 2 ]] || die "Missing value for --asset"
      asset="$2"
      shift 2
      ;;
    --asset=*)
      asset="${1#--asset=}"
      shift
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

[[ -n "$tag" ]] || { usage; die "--tag is required"; }
[[ -n "$asset" ]] || { usage; die "--asset is required"; }
[[ -f "$asset" ]] || die "Release asset does not exist: $asset"
command -v gh >/dev/null 2>&1 || die "gh is required to upload release assets"

asset_name="$(basename -- "$asset")"
asset_names="$(gh release view "$tag" --json assets --jq '.assets[].name')" \
  || die "Unable to inspect release assets for ${tag}"
existing_count="$(awk -v name="$asset_name" '$0 == name { count += 1 } END { print count + 0 }' <<< "$asset_names")"

if [[ "$existing_count" -gt 1 ]]; then
  die "Release ${tag} contains duplicate assets named ${asset_name}"
fi

if [[ "$existing_count" -eq 1 ]]; then
  scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-asset.XXXXXX")"
  # shellcheck disable=SC2329 # Invoked indirectly by the EXIT trap.
  cleanup() {
    rm -rf "$scratch_dir"
  }
  trap cleanup EXIT

  gh release download "$tag" --pattern "$asset_name" --dir "$scratch_dir" \
    || die "Unable to download immutable release asset ${asset_name} from ${tag}"
  downloaded_asset="${scratch_dir}/${asset_name}"
  [[ -f "$downloaded_asset" ]] \
    || die "Release ${tag} reported ${asset_name}, but the asset could not be downloaded"
  if ! cmp -s "$asset" "$downloaded_asset"; then
    local_digest="$(openssl dgst -sha256 -r "$asset" | awk '{ print $1 }')"
    remote_digest="$(openssl dgst -sha256 -r "$downloaded_asset" | awk '{ print $1 }')"
    die "Local asset ${asset_name} (${local_digest}) differs from immutable release asset ${tag}/${asset_name} (${remote_digest})"
  fi
  printf 'Verified byte-identical immutable release asset %s/%s\n' "$tag" "$asset_name"
  exit 0
fi

gh release upload "$tag" "$asset"
printf 'Uploaded immutable release asset %s/%s\n' "$tag" "$asset_name"
