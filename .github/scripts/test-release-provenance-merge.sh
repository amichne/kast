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

write_optional_provenance() {
  local root="$1"
  local platform="$2"
  local asset="$3"
  local digest_suffix="$4"

  mkdir -p "${root}/dist"
  cat > "${root}/dist/build-provenance-${platform}.json" <<JSON
{
  "platformId": "${platform}",
  "assetName": "${asset}",
  "assetDigest": "sha256:$(printf '%064d' "$digest_suffix")"
}
JSON
}

assert_platform_count() {
  local file_path="$1"
  local platform="$2"
  local expected="$3"
  python3 - "$file_path" "$platform" "$expected" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
platform = sys.argv[2]
expected = int(sys.argv[3])
actual = [entry.get("platformId") for entry in payload.get("builds", [])].count(platform)
if actual != expected:
    raise SystemExit(f"expected {expected} entries for {platform}, got {actual}")
PY
}

repo_root="$(resolve_repo_root)"
merger="${repo_root}/scripts/merge-release-provenance.py"
[[ -x "$merger" ]] || die "release provenance merger is missing or not executable: $merger"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-provenance-merge.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

tag="v9.8.7"
base="${scratch_dir}/build-provenance.json"
python3 - "$base" "$tag" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
tag = sys.argv[2]
entries = [
    ("cli-linux-arm64", f"kast-{tag}-linux-arm64.zip"),
    ("cli-linux-x64", f"kast-{tag}-linux-x64.zip"),
    ("cli-macos-arm64", f"kast-{tag}-macos-arm64.zip"),
    ("cli-macos-x64", f"kast-{tag}-macos-x64.zip"),
    ("headless", f"kast-headless-{tag}.zip"),
    ("intellij", f"kast-intellij-{tag}.zip"),
    ("standalone", f"kast-standalone-{tag}.zip"),
]
payload = {
    "builds": [
        {
            "platformId": platform,
            "assetName": asset,
            "assetDigest": "sha256:" + str(index).zfill(64),
        }
        for index, (platform, asset) in enumerate(entries, start=1)
    ]
}
path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

write_optional_provenance \
  "${scratch_dir}/standalone" \
  "ubuntu-debian-x86_64" \
  "kast-ubuntu-debian-x86_64-${tag}.tar.gz" \
  8
write_optional_provenance \
  "${scratch_dir}/headless" \
  "ubuntu-debian-headless-x86_64" \
  "kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz" \
  9
write_optional_provenance \
  "${scratch_dir}/devin" \
  "devin-headless-linux-x64" \
  "kast-devin-headless-runtime-linux-x64-${tag}.tar.gz" \
  10

one_output="${scratch_dir}/one.json"
"$merger" \
  --base "$base" \
  --output "$one_output" \
  "${scratch_dir}/standalone"
assert_platform_count "$one_output" "ubuntu-debian-x86_64" 1
assert_platform_count "$one_output" "ubuntu-debian-headless-x86_64" 0
assert_platform_count "$one_output" "devin-headless-linux-x64" 0

both_output="${scratch_dir}/both.json"
"$merger" \
  --base "$one_output" \
  --output "$both_output" \
  "${scratch_dir}/standalone" \
  "${scratch_dir}/headless" \
  "${scratch_dir}/devin"
assert_platform_count "$both_output" "ubuntu-debian-x86_64" 1
assert_platform_count "$both_output" "ubuntu-debian-headless-x86_64" 1
assert_platform_count "$both_output" "devin-headless-linux-x64" 1

rerun_output="${scratch_dir}/rerun.json"
"$merger" \
  --base "$both_output" \
  --output "$rerun_output" \
  "${scratch_dir}/standalone" \
  "${scratch_dir}/headless" \
  "${scratch_dir}/devin"
assert_platform_count "$rerun_output" "ubuntu-debian-x86_64" 1
assert_platform_count "$rerun_output" "ubuntu-debian-headless-x86_64" 1
assert_platform_count "$rerun_output" "devin-headless-linux-x64" 1

write_optional_provenance \
  "${scratch_dir}/required" \
  "standalone" \
  "kast-standalone-${tag}.zip" \
  11
if "$merger" \
  --base "$base" \
  --output "${scratch_dir}/required-output.json" \
  "${scratch_dir}/required" \
  >"${scratch_dir}/required.out" 2>"${scratch_dir}/required.err"; then
  die "required platform replacement unexpectedly passed"
fi
grep -Fq "cannot append required provenance platform" "${scratch_dir}/required.err" \
  || die "required platform failure did not explain the refusal"

printf '%s\n' "Release provenance merge test passed"
