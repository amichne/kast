#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

compute_sha256() {
  local input_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$input_path" | awk '{ print $1 }'
  else
    shasum -a 256 "$input_path" | awk '{ print $1 }'
  fi
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
verifier="${repo_root}/scripts/release/verify-release-assets.sh"
[[ -x "$verifier" ]] || die "release asset verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-verify.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT
tag="v9.8.7"
release_dir="${scratch_dir}/release"
mkdir -p "$release_dir"

assets=(
  "openapi.yaml"
  "kast-linux-arm64-${tag}.tar.gz"
  "kast-linux-x64-${tag}.tar.gz"
  "kast-macos-arm64-${tag}.tar.gz"
  "kast-macos-x64-${tag}.tar.gz"
)

write_expected_assets() {
  local asset
  for asset in "${assets[@]}"; do
    printf 'contents for %s\n' "$asset" >"${release_dir}/${asset}"
    if [[ "$asset" == *.tar.gz ]]; then
      printf '%s  %s\n' \
        "$(compute_sha256 "${release_dir}/${asset}")" \
        "$asset" \
        >"${release_dir}/${asset}.sha256"
    fi
  done
}

write_sha256sums() {
  : >"${release_dir}/SHA256SUMS"
  local asset
  for asset in "$@"; do
    [[ -f "${release_dir}/${asset}" ]] || continue
    printf '%s  %s\n' \
      "$(compute_sha256 "${release_dir}/${asset}")" \
      "$asset" \
      >>"${release_dir}/SHA256SUMS"
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
    ("openapi", "openapi.yaml"),
    ("setup-linux-arm64", f"kast-linux-arm64-{tag}.tar.gz"),
    ("setup-linux-x64", f"kast-linux-x64-{tag}.tar.gz"),
    ("setup-macos-arm64", f"kast-macos-arm64-{tag}.tar.gz"),
    ("setup-macos-x64", f"kast-macos-x64-{tag}.tar.gz"),
]
builds = [
    {
        "platformId": platform,
        "assetName": asset,
        "assetDigest": "sha256:" + hashlib.sha256((release_dir / asset).read_bytes()).hexdigest(),
    }
    for platform, asset in entries
    if (release_dir / asset).is_file()
]
(release_dir / "build-provenance.json").write_text(
    json.dumps({"builds": builds}, indent=2) + "\n",
    encoding="utf-8",
)
PY
}

reset_fixture() {
  rm -rf "$release_dir"
  mkdir -p "$release_dir"
  write_expected_assets
  write_sha256sums "${assets[@]}"
  write_provenance
}

reset_fixture
"$verifier" --release-dir "$release_dir" --tag "$tag"

reset_fixture
rm "${release_dir}/kast-macos-x64-${tag}.tar.gz" \
  "${release_dir}/kast-macos-x64-${tag}.tar.gz.sha256"
write_sha256sums "${assets[@]}"
write_provenance
if "$verifier" --release-dir "$release_dir" --tag "$tag" \
  >"${scratch_dir}/missing.out" 2>"${scratch_dir}/missing.err"; then
  die "release with a missing setup bundle unexpectedly verified"
fi
grep -Fq "missing" "${scratch_dir}/missing.err" \
  || die "missing setup failure did not identify missing evidence"

reset_fixture
printf 'tampered\n' >>"${release_dir}/${assets[0]}"
if "$verifier" --release-dir "$release_dir" --tag "$tag" \
  >/dev/null 2>"${scratch_dir}/checksum.err"; then
  die "tampered asset unexpectedly verified"
fi
grep -Fq "checksum mismatch" "${scratch_dir}/checksum.err" \
  || die "tampered asset failure did not mention checksum mismatch"

reset_fixture
python3 - "${release_dir}/build-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["builds"] = [
    entry for entry in payload["builds"] if entry.get("platformId") != "openapi"
]
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
if "$verifier" --release-dir "$release_dir" --tag "$tag" \
  >/dev/null 2>"${scratch_dir}/provenance.err"; then
  die "missing provenance unexpectedly verified"
fi
grep -Fq "missing provenance" "${scratch_dir}/provenance.err" \
  || die "missing provenance failure did not identify the proof"

reset_fixture
extra_asset="${release_dir}/kast-${tag}-linux-x64.zip"
printf 'unpublished product\n' >"$extra_asset"
if "$verifier" --release-dir "$release_dir" --tag "$tag" \
  >/dev/null 2>"${scratch_dir}/extra.err"; then
  die "unexpected product asset unexpectedly verified"
fi
grep -Fq "unexpected release asset" "${scratch_dir}/extra.err" \
  || die "unexpected product failure did not identify the asset"

printf '%s\n' "Release asset verifier test passed"
