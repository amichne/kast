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
published zip/tar/protocol assets, SHA256SUMS, and build-provenance.json.
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

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compatibility_renderer="${repo_root}/.github/scripts/render-runtime-compatibility.py"
[[ -x "$compatibility_renderer" ]] \
  || die "Runtime compatibility renderer is missing or not executable: $compatibility_renderer"
"$compatibility_renderer" validate-manifest \
  --manifest "${release_dir}/kast-runtime-compatibility.json" \
  --release-tag "$tag"

python3 - "$release_dir" "$tag" "$repo_root" <<'PY'
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]
repo_root = Path(sys.argv[3])

required = {
    "cli-linux-arm64": f"kast-{tag}-linux-arm64.zip",
    "cli-linux-x64": f"kast-{tag}-linux-x64.zip",
    "cli-macos-arm64": f"kast-{tag}-macos-arm64.zip",
    "cli-macos-x64": f"kast-{tag}-macos-x64.zip",
    "codex-plugin": f"kast-codex-plugin-{tag}.zip",
    "gradle-ro-cache": "gradle-ro-dep-cache.tar.zst",
    "headless-linux-x64": "kast-headless-linux-x64.tar.zst",
    "idea": f"kast-idea-{tag}.zip",
    "openapi": "openapi.yaml",
    "runtime-manifest": "kast-runtime-manifest.json",
    "runtime-compatibility": "kast-runtime-compatibility.json",
    "ubuntu-debian-headless-x86_64": f"kast-ubuntu-debian-headless-x86_64-{tag}.tar.gz",
}
optional = {}
supported = required | optional

def fail(message: str) -> None:
    raise SystemExit(message)

actual_assets = {
    path.name for path in release_dir.iterdir()
    if path.is_file()
    and (
        path.name.endswith(".zip")
        or path.name.endswith(".tar.gz")
        or path.name.endswith(".tar.zst")
        or path.name in ("kast-runtime-manifest.json", "kast-runtime-compatibility.json")
        or path.name == "openapi.yaml"
    )
    and not path.name.endswith(".tar.gz.sha256")
    and not path.name.endswith(".tar.zst.sha256")
}

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

missing_provenance = sorted(set(required) - set(by_platform))
if missing_provenance:
    fail(f"missing provenance for {missing_provenance}")

unexpected_provenance = sorted(set(by_platform) - set(supported))
if unexpected_provenance:
    fail(f"unexpected provenance for {unexpected_provenance}")

idea_provenance = by_platform["idea"]
if idea_provenance.get("pluginId") != "io.github.amichne.kast":
    fail("IDEA plugin provenance pluginId must be io.github.amichne.kast")
signer_fingerprint = idea_provenance.get("signerCertificateSha256")
if not isinstance(signer_fingerprint, str) or re.fullmatch(r"[0-9a-f]{64}", signer_fingerprint) is None:
    fail("IDEA plugin provenance signerCertificateSha256 must be lowercase SHA-256")
if idea_provenance.get("signatureVerified") is not True:
    fail("IDEA plugin provenance signatureVerified must be true")
if idea_provenance.get("ref") != f"refs/tags/{tag}":
    fail(f"IDEA plugin provenance ref must be refs/tags/{tag}")
release_sha = idea_provenance.get("sha")
if not isinstance(release_sha, str) or re.fullmatch(r"[0-9a-f]{40}", release_sha) is None:
    fail("IDEA plugin provenance sha must be a full lowercase Git commit SHA")
if idea_provenance.get("verificationTasks") != [
    ":backend-idea:verifyPluginStructure",
    ":backend-idea:verifyPluginXmlPresent",
    ":backend-idea:verifyPlugin",
    ":backend-idea:verifyPluginSignature",
]:
    fail("IDEA plugin provenance must carry the complete signed compatibility gate")

codex_provenance = by_platform["codex-plugin"]
if codex_provenance.get("ref") != f"refs/tags/{tag}":
    fail(f"Codex plugin provenance ref must be refs/tags/{tag}")
if codex_provenance.get("sha") != release_sha:
    fail("Codex plugin provenance sha must match signed IDEA provenance")
if codex_provenance.get("pluginVersion") != tag.removeprefix("v"):
    fail("Codex plugin provenance pluginVersion must match release tag")
if codex_provenance.get("generatorCommand") != "kast developer codex generate --release":
    fail("Codex plugin provenance must name the release-mode Rust generator")

expected_assets = set()
for platform_id, entry in by_platform.items():
    asset_name = supported[platform_id]
    expected_assets.add(asset_name)
    provenance_asset = entry.get("assetName")
    if provenance_asset != asset_name:
        fail(f"provenance asset mismatch for {platform_id}: expected {asset_name}, got {provenance_asset}")

unexpected_assets = sorted(actual_assets - expected_assets)
if unexpected_assets:
    fail(f"unexpected release asset: {unexpected_assets}")

missing_assets = sorted(expected_assets - actual_assets)
if missing_assets:
    fail(f"missing release asset: {missing_assets}")

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

actual_sidecars = {path.name for path in release_dir.glob("*.sha256")}
expected_sidecars = {}
for asset_name in expected_assets:
    if asset_name.endswith(".tar.gz"):
        expected_sidecars[f"{asset_name}.sha256"] = asset_name
    elif asset_name.endswith(".tar.zst"):
        expected_sidecars[f"{asset_name.removesuffix('.tar.zst')}.sha256"] = asset_name
missing_sidecars = sorted(set(expected_sidecars) - actual_sidecars)
if missing_sidecars:
    fail(f"missing checksum sidecar: {missing_sidecars}")
unexpected_sidecars = sorted(actual_sidecars - set(expected_sidecars))
if unexpected_sidecars:
    fail(f"unexpected checksum sidecar: {unexpected_sidecars}")

for sidecar_name, asset_name in expected_sidecars.items():
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

for platform_id, asset_name in supported.items():
    if platform_id not in by_platform:
        continue
    entry = by_platform[platform_id]
    provenance_digest = entry.get("assetDigest")
    expected_digest = "sha256:" + sha_entries[asset_name]
    if provenance_digest != expected_digest:
        fail(f"provenance digest mismatch for {platform_id}: expected {expected_digest}, got {provenance_digest}")

manifest_path = release_dir / "kast-runtime-manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if not isinstance(manifest, dict):
    fail("runtime manifest must be a JSON object")
required_manifest_keys = [
    "schemaVersion",
    "kastVersion",
    "kastGitSha",
    "os",
    "arch",
    "javaVersion",
    "intellijBuild",
    "kotlinPluginVersion",
    "kastIndexSchemaVersion",
    "artifactSha256",
]
unexpected_manifest_keys = sorted(set(manifest) - set(required_manifest_keys))
if unexpected_manifest_keys:
    fail(f"runtime manifest contains unsupported field: {unexpected_manifest_keys}")
for key in required_manifest_keys:
    if key not in manifest:
        fail(f"runtime manifest missing {key}")
if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1:
    fail("runtime manifest schemaVersion must be 1")
if not isinstance(manifest["kastVersion"], str) or manifest["kastVersion"].removeprefix("v") != tag.removeprefix("v"):
    fail("runtime manifest kastVersion must match release tag")
if not isinstance(manifest["kastGitSha"], str) or not re.fullmatch(r"[0-9a-f]{7,40}", manifest["kastGitSha"]):
    fail("runtime manifest kastGitSha must be 7 to 40 lowercase hexadecimal characters")
if manifest["os"] != "linux" or manifest["arch"] != "x64":
    fail("runtime manifest must describe linux/x64")
if not isinstance(manifest["javaVersion"], str) or not re.fullmatch(r"[0-9]+", manifest["javaVersion"]):
    fail("runtime manifest javaVersion must be numeric text")
if not isinstance(manifest["intellijBuild"], str) or not manifest["intellijBuild"].strip():
    fail("runtime manifest intellijBuild must be non-empty text")
if not isinstance(manifest["kotlinPluginVersion"], str) or not manifest["kotlinPluginVersion"].strip():
    fail("runtime manifest kotlinPluginVersion must be non-empty text")
if not isinstance(manifest["kastIndexSchemaVersion"], str) or not re.fullmatch(r"[0-9]+", manifest["kastIndexSchemaVersion"]):
    fail("runtime manifest kastIndexSchemaVersion must be numeric text")
if not isinstance(manifest["artifactSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", manifest["artifactSha256"]):
    fail("runtime manifest artifactSha256 must be a lowercase SHA-256 digest")
runtime_asset = "kast-headless-linux-x64.tar.zst"
if manifest["artifactSha256"] != sha_entries.get(runtime_asset):
    fail("runtime manifest artifactSha256 must match kast-headless-linux-x64.tar.zst")

compatibility = json.loads(
    (release_dir / "kast-runtime-compatibility.json").read_text(encoding="utf-8")
)
if compatibility["releaseSha"] != release_sha:
    fail("runtime compatibility manifest releaseSha must match signed IDEA provenance")

codex_asset = release_dir / supported["codex-plugin"]
subprocess.run(
    [
        str(repo_root / ".github" / "scripts" / "verify-codex-plugin-package.py"),
        "--archive",
        str(codex_asset),
        "--version",
        tag.removeprefix("v"),
    ],
    check=True,
)

print(f"Verified Kast release assets for {tag} in {release_dir}")
PY
