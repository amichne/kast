#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/release/upload-immutable-release-asset.sh --tag <tag> --asset <path>

Upload one GitHub release asset exactly once. If the named asset already exists,
prove byte identity instead of replacing it. Remove only an incomplete starter
asset before a bounded upload retry.
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
command -v jq >/dev/null 2>&1 || die "jq is required to inspect release assets"
command -v python3 >/dev/null 2>&1 || die "python3 is required to bound release uploads"

asset_name="$(basename -- "$asset")"
local_digest="$(openssl dgst -sha256 -r "$asset" | awk '{ print $1 }')"
upload_attempts="${KAST_RELEASE_UPLOAD_ATTEMPTS:-3}"
upload_timeout_seconds="${KAST_RELEASE_UPLOAD_TIMEOUT_SECONDS:-300}"
metadata_timeout_seconds="${KAST_RELEASE_METADATA_TIMEOUT_SECONDS:-30}"
settle_attempts="${KAST_RELEASE_UPLOAD_SETTLE_ATTEMPTS:-3}"
[[ "$upload_attempts" =~ ^[1-9][0-9]*$ ]] \
  || die "KAST_RELEASE_UPLOAD_ATTEMPTS must be a positive integer"
[[ "$upload_timeout_seconds" =~ ^[1-9][0-9]*$ ]] \
  || die "KAST_RELEASE_UPLOAD_TIMEOUT_SECONDS must be a positive integer"
[[ "$metadata_timeout_seconds" =~ ^[1-9][0-9]*$ ]] \
  || die "KAST_RELEASE_METADATA_TIMEOUT_SECONDS must be a positive integer"
[[ "$settle_attempts" =~ ^[1-9][0-9]*$ ]] \
  || die "KAST_RELEASE_UPLOAD_SETTLE_ATTEMPTS must be a positive integer"
scratch_dir=""
remote_state=""
remote_digest=""
remote_api_url=""
remote_size=""
remote_count=0

# shellcheck disable=SC2329 # Invoked indirectly by the EXIT trap.
cleanup() {
  [[ -z "$scratch_dir" ]] || rm -rf "$scratch_dir"
}
trap cleanup EXIT

run_gh_with_timeout() {
  local timeout_seconds="$1"
  shift
  python3 - "$timeout_seconds" "$@" <<'PY'
import subprocess
import sys

timeout = int(sys.argv[1])
command = ["gh", *sys.argv[2:]]
try:
    result = subprocess.run(command, timeout=timeout, check=False)
except subprocess.TimeoutExpired:
    print(
        f"error: gh command exceeded {timeout} seconds: {' '.join(command[1:])}",
        file=sys.stderr,
    )
    raise SystemExit(124)
raise SystemExit(result.returncode)
PY
}

inspect_asset() {
  local payload matches inspection
  for ((inspection = 1; inspection <= upload_attempts; inspection += 1)); do
    if payload="$(run_gh_with_timeout "$metadata_timeout_seconds" release view "$tag" --json assets)"; then
      break
    fi
    [[ "$inspection" -lt "$upload_attempts" ]] \
      || die "Unable to inspect release assets for ${tag}"
    printf 'Retrying release asset inspection for %s after attempt %s failed\n' \
      "$tag" "$inspection" >&2
    sleep "$inspection"
  done
  matches="$(jq -c --arg name "$asset_name" \
    '[.assets[] | select(.name == $name)]' <<<"$payload")" \
    || die "Unable to parse release assets for ${tag}"
  remote_count="$(jq -r 'length' <<<"$matches")"
  [[ "$remote_count" -le 1 ]] \
    || die "Release ${tag} contains duplicate assets named ${asset_name}"
  remote_state=""
  remote_digest=""
  remote_api_url=""
  remote_size=""
  if [[ "$remote_count" -eq 1 ]]; then
    remote_state="$(jq -r '.[0].state // ""' <<<"$matches")"
    remote_digest="$(jq -r '.[0].digest // ""' <<<"$matches")"
    remote_api_url="$(jq -r '.[0].apiUrl // ""' <<<"$matches")"
    remote_size="$(jq -r '.[0].size // ""' <<<"$matches")"
  fi
}

verify_uploaded_asset() {
  [[ "$remote_state" == "uploaded" ]] \
    || die "Release asset ${tag}/${asset_name} is not uploaded"
  if [[ -n "$remote_digest" ]]; then
    [[ "$remote_digest" == "sha256:${local_digest}" ]] \
      || die "Local asset ${asset_name} (sha256:${local_digest}) differs from immutable release asset ${tag}/${asset_name} (${remote_digest})"
    printf 'Verified immutable release asset digest %s/%s\n' "$tag" "$asset_name"
    return
  fi

  scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-asset.XXXXXX")"
  run_gh_with_timeout "$upload_timeout_seconds" \
    release download "$tag" --pattern "$asset_name" --dir "$scratch_dir" \
    || die "Unable to download immutable release asset ${asset_name} from ${tag}"
  downloaded_asset="${scratch_dir}/${asset_name}"
  [[ -f "$downloaded_asset" ]] \
    || die "Release ${tag} reported ${asset_name}, but the asset could not be downloaded"
  if ! cmp -s "$asset" "$downloaded_asset"; then
    downloaded_digest="$(openssl dgst -sha256 -r "$downloaded_asset" | awk '{ print $1 }')"
    die "Local asset ${asset_name} (${local_digest}) differs from immutable release asset ${tag}/${asset_name} (${downloaded_digest})"
  fi
  printf 'Verified byte-identical immutable release asset %s/%s\n' "$tag" "$asset_name"
}

settle_incomplete_asset() {
  local endpoint current current_name current_state current_digest current_url current_size poll
  [[ "$remote_state" == "starter" && -z "$remote_digest" && "$remote_size" == "0" ]] \
    || die "Release asset ${tag}/${asset_name} has unsafe incomplete state=${remote_state} digest=${remote_digest:-null} size=${remote_size:-unknown}"
  [[ "$remote_api_url" =~ ^https://api\.github\.com/repos/[^/]+/[^/]+/releases/assets/[0-9]+$ ]] \
    || die "Release asset ${tag}/${asset_name} has no valid API URL"
  endpoint="${remote_api_url#https://api.github.com/}"
  for ((poll = 1; poll <= settle_attempts; poll += 1)); do
    [[ "$poll" -eq 1 ]] || sleep 1
    current="$(run_gh_with_timeout "$metadata_timeout_seconds" api "$endpoint")" \
      || die "Unable to recheck incomplete release asset ${tag}/${asset_name}"
    current_name="$(jq -r '.name // ""' <<<"$current")"
    current_state="$(jq -r '.state // ""' <<<"$current")"
    current_digest="$(jq -r '.digest // ""' <<<"$current")"
    current_url="$(jq -r '.apiUrl // .url // ""' <<<"$current")"
    current_size="$(jq -r '.size // ""' <<<"$current")"
    [[ "$current_name" == "$asset_name" ]] \
      || die "Incomplete release asset identity changed for ${tag}/${asset_name}"
    [[ "$current_url" == "$remote_api_url" ]] \
      || die "Incomplete release asset API identity changed for ${tag}/${asset_name}"
    case "$current_state" in
      uploaded)
        remote_state="$current_state"
        remote_digest="$current_digest"
        remote_size="$current_size"
        verify_uploaded_asset
        return 0
        ;;
      starter)
        [[ -z "$current_digest" && "$current_size" == "0" ]] \
          || die "Release asset ${tag}/${asset_name} changed while settling: state=${current_state} digest=${current_digest:-null} size=${current_size:-unknown}"
        ;;
      *)
        die "Release asset ${tag}/${asset_name} changed while settling: state=${current_state:-missing}"
        ;;
    esac
  done

  # The workflow concurrency group gives each tag one writer. The upload
  # subprocess has exited and the same empty asset stayed stable while polled.
  run_gh_with_timeout "$metadata_timeout_seconds" \
    api --method DELETE "$endpoint" --silent \
    || die "Unable to delete incomplete release asset ${tag}/${asset_name}"
  printf 'Deleted incomplete release asset %s/%s\n' "$tag" "$asset_name"
  return 1
}

reconcile_asset() {
  inspect_asset
  if [[ "$remote_count" -eq 0 ]]; then
    return 1
  fi
  case "$remote_state" in
    uploaded)
      verify_uploaded_asset
      return 0
      ;;
    starter)
      if settle_incomplete_asset; then
        return 0
      fi
      return 1
      ;;
    *)
      die "Release asset ${tag}/${asset_name} has unsupported state=${remote_state:-missing}"
      ;;
  esac
}

upload_once() {
  run_gh_with_timeout "$upload_timeout_seconds" release upload "$tag" "$asset"
}

if reconcile_asset; then
  exit 0
fi

for ((attempt = 1; attempt <= upload_attempts; attempt += 1)); do
  upload_status=0
  upload_once || upload_status="$?"
  if reconcile_asset; then
    printf 'Uploaded immutable release asset %s/%s\n' "$tag" "$asset_name"
    exit 0
  fi
  if [[ "$attempt" -lt "$upload_attempts" ]]; then
    printf 'Retrying release asset upload %s/%s after attempt %s failed with %s\n' \
      "$tag" "$asset_name" "$attempt" "$upload_status" >&2
    sleep "$attempt"
  fi
done

die "Unable to upload immutable release asset ${tag}/${asset_name} after ${upload_attempts} attempts"
