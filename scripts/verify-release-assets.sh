#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/verify-release-assets.sh --release-dir <dir> --tag <vX.Y.Z>

Verify a downloaded Kast release directory. The directory must contain the
published zip/tar assets, SHA256SUMS, and build-provenance.json.
USAGE
}

release_dir=""
tag=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-dir)
      [[ $# -ge 2 ]] || die "Missing value for --release-dir"
      release_dir="$2"; shift 2 ;;
    --release-dir=*)
      release_dir="${1#--release-dir=}"; shift ;;
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"; shift 2 ;;
    --tag=*)
      tag="${1#--tag=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$release_dir" ]] || { usage; die "--release-dir is required"; }
[[ -n "$tag" ]] || { usage; die "--tag is required"; }
[[ "$tag" == v* ]] || die "--tag must start with v: $tag"
[[ -d "$release_dir" ]] || die "Release directory not found: $release_dir"
[[ -f "${release_dir}/SHA256SUMS" ]] || die "SHA256SUMS not found in $release_dir"
[[ -f "${release_dir}/build-provenance.json" ]] || die "build-provenance.json not found in $release_dir"

python3 - "$release_dir" "$tag" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]

expected = {
    "ubuntu-debian-x86_64": f"kast-ubuntu-debian-x86_64-{tag}.tar.gz",
    "intellij": f"kast-intellij-{tag}.zip",
    "standalone": f"kast-standalone-{tag}.zip",
}
expected_assets = set(expected.values())
expected_sidecars = {
    f"kast-ubuntu-debian-x86_64-{tag}.tar.gz.sha256",
}

def fail(message: str) -> None:
    raise SystemExit(message)

sha_entries = {}
for raw_line in (release_dir / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line:
        continue
    parts = line.split()
    if len(parts) != 2:
        fail(f"invalid SHA256SUMS line: {raw_line}")
    digest, asset_name = parts
    if asset_name in sha_entries:
        fail(f"duplicate checksum entry for {asset_name}")
    sha_entries[asset_name] = digest

actual_assets = {
    path.name for path in release_dir.iterdir()
    if path.is_file()
    and (path.name.endswith(".zip") or path.name.endswith(".tar.gz"))
    and not path.name.endswith(".tar.gz.sha256")
}
unexpected_assets = sorted(actual_assets - expected_assets)
if unexpected_assets:
    fail(f"unexpected release asset: {unexpected_assets}")

missing_assets = sorted(expected_assets - actual_assets)
if missing_assets:
    fail(f"missing release asset: {missing_assets}")

unexpected_checksums = sorted(set(sha_entries) - expected_assets)
if unexpected_checksums:
    fail(f"unexpected checksum entry: {unexpected_checksums}")

for asset_name in expected_assets:
    asset_path = release_dir / asset_name
    expected_digest = sha_entries.get(asset_name)
    if expected_digest is None:
        fail(f"missing checksum entry for {asset_name}")
    actual_digest = hashlib.sha256(asset_path.read_bytes()).hexdigest()
    if actual_digest != expected_digest:
        fail(f"checksum mismatch for {asset_name}: expected {expected_digest}, got {actual_digest}")

actual_sidecars = {path.name for path in release_dir.glob("*.tar.gz.sha256")}
missing_sidecars = sorted(expected_sidecars - actual_sidecars)
if missing_sidecars:
    fail(f"missing checksum sidecar: {missing_sidecars}")
unexpected_sidecars = sorted(actual_sidecars - expected_sidecars)
if unexpected_sidecars:
    fail(f"unexpected checksum sidecar: {unexpected_sidecars}")

for sidecar_name in expected_sidecars:
    asset_name = sidecar_name.removesuffix(".sha256")
    sidecar_path = release_dir / sidecar_name
    sidecar_lines = [
        line.strip() for line in sidecar_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if len(sidecar_lines) != 1:
        fail(f"invalid checksum sidecar for {asset_name}")
    parts = sidecar_lines[0].split()
    if len(parts) != 2 or parts[1] != asset_name:
        fail(f"checksum sidecar does not name {asset_name}")
    if parts[0] != sha_entries.get(asset_name):
        fail(f"checksum sidecar mismatch for {asset_name}")

payload = json.loads((release_dir / "build-provenance.json").read_text(encoding="utf-8"))
builds = payload.get("builds")
if not isinstance(builds, list):
    fail("build-provenance.json must contain a builds array")

by_platform = {}
for entry in builds:
    if not isinstance(entry, dict):
        fail("build-provenance.json builds entries must be objects")
    platform_id = entry.get("platformId")
    if platform_id in by_platform:
        fail(f"duplicate provenance entry for {platform_id}")
    by_platform[platform_id] = entry

missing_provenance = sorted(set(expected) - set(by_platform))
if missing_provenance:
    fail(f"missing provenance for {missing_provenance}")

unexpected_provenance = sorted(set(by_platform) - set(expected))
if unexpected_provenance:
    fail(f"unexpected provenance for {unexpected_provenance}")

for platform_id, asset_name in expected.items():
    entry = by_platform[platform_id]
    provenance_asset = entry.get("assetName")
    if provenance_asset != asset_name:
        fail(f"provenance asset mismatch for {platform_id}: expected {asset_name}, got {provenance_asset}")
    provenance_digest = entry.get("assetDigest")
    expected_digest = "sha256:" + sha_entries[asset_name]
    if provenance_digest != expected_digest:
        fail(f"provenance digest mismatch for {platform_id}: expected {expected_digest}, got {provenance_digest}")

print(f"Verified Kast release assets for {tag} in {release_dir}")
PY
