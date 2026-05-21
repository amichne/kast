#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/verify-release-assets.sh --release-dir <dir> --tag <vX.Y.Z>

Verify a downloaded Kast release directory. The directory must contain the five
published zip assets, SHA256SUMS, and build-provenance.json.
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
import io
import json
import sys
import zipfile
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]

expected = {
    "linux-x64": f"kast-{tag}-linux-x64.zip",
    "macos-arm64": f"kast-{tag}-macos-arm64.zip",
    "headless-agent-linux-x64": f"kast-headless-agent-{tag}-linux-x64.zip",
    "intellij": f"kast-intellij-{tag}.zip",
    "standalone": f"kast-standalone-{tag}.zip",
}
expected_assets = set(expected.values())

def fail(message: str) -> None:
    raise SystemExit(message)

def inspect_no_shrunk_runtime(display_name: str, payload: bytes) -> None:
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            names = archive.namelist()
            for name in names:
                if name.endswith("/kast-shrunk.jar") or name == "kast-shrunk.jar":
                    fail(f"{display_name} contains ProGuard/R8 shrunk runtime artifact: {name}")
                if name.endswith("runtime-libs/classpath.txt"):
                    classpath = archive.read(name).decode("utf-8", errors="replace")
                    if "kast-shrunk.jar" in classpath:
                        fail(f"{display_name} classpath references ProGuard/R8 shrunk runtime artifact: {name}")

            for nested in ("artifacts/kast-cli.zip", "artifacts/kast-standalone.zip"):
                if nested in names:
                    nested_payload = archive.read(nested)
                    inspect_no_shrunk_runtime(f"{display_name}!/{nested}", nested_payload)
                    if nested == "artifacts/kast-cli.zip":
                        inspect_native_cli_payload(f"{display_name}!/{nested}", nested_payload, "linux-x64")
    except zipfile.BadZipFile as error:
        fail(f"{display_name} is not a valid zip archive: {error}")

def inspect_native_cli_payload(display_name: str, payload: bytes, platform_id: str) -> None:
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            candidate_names = ("kast-cli/kast-cli", "kast/kast-cli")
            launcher_name = next((name for name in candidate_names if name in archive.namelist()), None)
            if launcher_name is None:
                fail(f"{display_name} does not contain a kast-cli launcher")
            launcher = archive.read(launcher_name)
    except zipfile.BadZipFile as error:
        fail(f"{display_name} is not a valid zip archive: {error}")

    if launcher.startswith(b"#!"):
        fail(f"{display_name} contains a shell launcher; expected a native image")
    if platform_id == "linux-x64" and not launcher.startswith(b"\x7fELF"):
        fail(f"{display_name} launcher is not an ELF native image")
    if platform_id == "macos-arm64" and launcher[:4] not in {
        b"\xcf\xfa\xed\xfe",
        b"\xfe\xed\xfa\xcf",
        b"\xca\xfe\xba\xbe",
        b"\xbe\xba\xfe\xca",
    }:
        fail(f"{display_name} launcher is not a Mach-O native image")

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

actual_assets = {path.name for path in release_dir.glob("*.zip")}
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

for platform_id, asset_name in expected.items():
    payload = (release_dir / asset_name).read_bytes()
    inspect_no_shrunk_runtime(asset_name, payload)
    if platform_id in {"linux-x64", "macos-arm64"}:
        inspect_native_cli_payload(asset_name, payload, platform_id)

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
