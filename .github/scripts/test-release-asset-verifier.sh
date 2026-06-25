#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
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

write_zip_asset() {
  local asset_path="$1"
  local kind="$2"
  python3 - "$asset_path" "$kind" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

asset_path = Path(sys.argv[1])
kind = sys.argv[2]

def write_entry(archive, name, data, mode=0o644):
    info = zipfile.ZipInfo(name)
    info.external_attr = (stat.S_IFREG | mode) << 16
    archive.writestr(info, data)

asset_path.parent.mkdir(parents=True, exist_ok=True)
if kind == "idea":
    with zipfile.ZipFile(asset_path, "w") as archive:
        write_entry(archive, "backend-idea/lib/backend-idea.jar", b"plugin")
else:
    raise SystemExit(f"unknown asset kind: {kind}")
PY
}

write_text_asset() {
  local asset_path="$1"
  printf 'contents for %s\n' "$(basename -- "$asset_path")" > "$asset_path"
}

write_expected_assets() {
  write_text_asset "${release_dir}/kast-${tag}-linux-x64.zip"
  write_text_asset "${release_dir}/kast-${tag}-linux-arm64.zip"
  write_text_asset "${release_dir}/kast-${tag}-macos-x64.zip"
  write_text_asset "${release_dir}/kast-${tag}-macos-arm64.zip"
  write_zip_asset "${release_dir}/kast-idea-${tag}.zip" idea
  write_text_asset "${release_dir}/kast-headless-linux-x64.tar.zst"
  python3 - "${release_dir}/kast-runtime-manifest.json" "${release_dir}/kast-headless-linux-x64.tar.zst" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
runtime_asset = Path(sys.argv[2])
payload = {
    "schemaVersion": 1,
    "kastVersion": "9.8.7",
    "kastGitSha": "0123456789abcdef",
    "os": "linux",
    "arch": "x64",
    "javaVersion": "21",
    "intellijBuild": "2025.3",
    "kotlinPluginVersion": "2.3.21",
    "kastIndexSchemaVersion": "7",
    "artifactSha256": hashlib.sha256(runtime_asset.read_bytes()).hexdigest(),
}
manifest_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
  printf '%s  %s\n' \
    "$(compute_sha256 "${release_dir}/kast-headless-linux-x64.tar.zst")" \
    "kast-headless-linux-x64.tar.zst" \
    > "${release_dir}/kast-headless-linux-x64.sha256"
  write_text_asset "${release_dir}/gradle-ro-dep-cache.tar.zst"
  printf '%s  %s\n' \
    "$(compute_sha256 "${release_dir}/gradle-ro-dep-cache.tar.zst")" \
    "gradle-ro-dep-cache.tar.zst" \
    > "${release_dir}/gradle-ro-dep-cache.sha256"
  write_text_asset "${release_dir}/openapi.yaml"
  write_text_asset "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz"
  printf '%s  %s\n' \
    "$(compute_sha256 "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz")" \
    "kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz" \
    > "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz.sha256"
}

write_sha256sums() {
  local release_dir="$1"
  shift

  : > "${release_dir}/SHA256SUMS"
  local asset_name
  for asset_name in "$@"; do
    printf '%s  %s\n' "$(compute_sha256 "${release_dir}/${asset_name}")" "$asset_name" >> "${release_dir}/SHA256SUMS"
  done
}

write_provenance() {
  python3 - "$release_dir" "$tag" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]
entries = [
    ("cli-linux-x64", f"kast-{tag}-linux-x64.zip"),
    ("cli-linux-arm64", f"kast-{tag}-linux-arm64.zip"),
    ("cli-macos-x64", f"kast-{tag}-macos-x64.zip"),
    ("cli-macos-arm64", f"kast-{tag}-macos-arm64.zip"),
    ("gradle-ro-cache", "gradle-ro-dep-cache.tar.zst"),
    ("headless-linux-x64", "kast-headless-linux-x64.tar.zst"),
    ("openapi", "openapi.yaml"),
    ("runtime-manifest", "kast-runtime-manifest.json"),
    ("ubuntu-debian-headless-x86_64", f"kast-ubuntu-debian-headless-x86_64-{tag}.tar.gz"),
    ("idea", f"kast-idea-{tag}.zip"),
]
payload = {
    "builds": [
        {
            "platformId": platform,
            "assetName": asset,
            "assetDigest": "sha256:" + hashlib.sha256((release_dir / asset).read_bytes()).hexdigest(),
        }
        for platform, asset in entries
        if (release_dir / asset).is_file()
    ]
}
(release_dir / "build-provenance.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

repo_root="$(resolve_repo_root)"
verifier="${repo_root}/scripts/verify-release-assets.sh"
[[ -x "$verifier" ]] || die "release asset verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-verify.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

tag="v9.8.7"
release_dir="${scratch_dir}/release"
mkdir -p "$release_dir"

assets=(
  "kast-${tag}-linux-x64.zip"
  "kast-${tag}-linux-arm64.zip"
  "kast-${tag}-macos-x64.zip"
  "kast-${tag}-macos-arm64.zip"
  "gradle-ro-dep-cache.tar.zst"
  "kast-headless-linux-x64.tar.zst"
  "openapi.yaml"
  "kast-runtime-manifest.json"
  "kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz"
  "kast-idea-${tag}.zip"
)

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance

"$verifier" --release-dir "$release_dir" --tag "$tag"

rm -rf "$release_dir"
mkdir -p "$release_dir"
core_assets=(
  "kast-${tag}-linux-x64.zip"
  "kast-${tag}-linux-arm64.zip"
  "kast-${tag}-macos-x64.zip"
  "kast-${tag}-macos-arm64.zip"
  "gradle-ro-dep-cache.tar.zst"
  "kast-headless-linux-x64.tar.zst"
  "openapi.yaml"
  "kast-runtime-manifest.json"
  "kast-idea-${tag}.zip"
)
write_expected_assets
rm -f \
  "${release_dir}/kast-ubuntu-debian-headless_x86_64-${tag}.tar.gz" \
  "${release_dir}/kast-ubuntu-debian-headless_x86_64-${tag}.tar.gz.sha256" \
  "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz" \
  "${release_dir}/kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz.sha256"
write_sha256sums "$release_dir" "${core_assets[@]}"
write_provenance

if "$verifier" --release-dir "$release_dir" --tag "$tag" >"${scratch_dir}/missing-tarball.out" 2>"${scratch_dir}/missing-tarball.err"; then
  die "release without Linux headless tarball unexpectedly verified"
fi
grep -Fq "missing provenance" "${scratch_dir}/missing-tarball.err" \
  || die "missing Linux headless tarball failure did not mention missing provenance"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
printf 'tampered\n' >> "${release_dir}/${assets[0]}"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/checksum.err"; then
  die "tampered asset unexpectedly verified"
fi
grep -Fq "checksum mismatch" "${scratch_dir}/checksum.err" || die "tampered asset failure did not mention checksum mismatch"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
python3 - "${release_dir}/build-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["builds"] = [
    entry for entry in payload["builds"]
    if entry.get("platformId") != "idea"
]
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/provenance.err"; then
  die "missing provenance unexpectedly verified"
fi
grep -Fq "missing provenance" "${scratch_dir}/provenance.err" || die "missing provenance failure did not mention missing provenance"

write_expected_assets
python3 - "${release_dir}/kast-runtime-manifest.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["kastVersion"] = "9.8.8"
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/manifest-version.err"; then
  die "wrong runtime manifest version unexpectedly verified"
fi
grep -Fq "kastVersion" "${scratch_dir}/manifest-version.err" || die "wrong manifest version failure did not mention kastVersion"

write_expected_assets
write_sha256sums "$release_dir" "${assets[@]}"
write_provenance
extra_asset="${release_dir}/kast-${tag}-debug.zip"
write_text_asset "$extra_asset"
printf '%s  %s\n' "$(compute_sha256 "$extra_asset")" "$(basename -- "$extra_asset")" >> "${release_dir}/SHA256SUMS"

if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/extra.err"; then
  die "extra release asset unexpectedly verified"
fi
grep -Fq "unexpected release asset" "${scratch_dir}/extra.err" || die "extra asset failure did not mention unexpected release asset"

printf '%s\n' "Release asset verifier test passed"
