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

write_provenance() {
  local path="$1"
  local platform="$2"
  local asset="$3"

  mkdir -p "$(dirname -- "$path")"
  cat > "$path" <<JSON
{
  "platformId": "${platform}",
  "assetName": "${asset}",
  "assetDigest": "sha256:$(printf '%064d' 1)"
}
JSON
}

repo_root="$(resolve_repo_root)"
assembler="${repo_root}/scripts/assemble-release-provenance.py"
[[ -x "$assembler" ]] || die "release provenance assembler is missing or not executable: $assembler"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-provenance.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

tag="v9.8.7"
write_provenance \
  "${scratch_dir}/provenance-cli-linux-x64/dist/build-provenance-cli-linux-x64.json" \
  "cli-linux-x64" \
  "kast-${tag}-linux-x64.zip"
write_provenance \
  "${scratch_dir}/provenance-cli-linux-arm64/dist/build-provenance-cli-linux-arm64.json" \
  "cli-linux-arm64" \
  "kast-${tag}-linux-arm64.zip"
write_provenance \
  "${scratch_dir}/provenance-cli-macos-x64/dist/build-provenance-cli-macos-x64.json" \
  "cli-macos-x64" \
  "kast-${tag}-macos-x64.zip"
write_provenance \
  "${scratch_dir}/provenance-cli-macos-arm64/dist/build-provenance-cli-macos-arm64.json" \
  "cli-macos-arm64" \
  "kast-${tag}-macos-arm64.zip"
write_provenance \
  "${scratch_dir}/provenance-idea/dist/build-provenance-idea.json" \
  "idea" \
  "kast-idea-${tag}.zip"
write_provenance \
  "${scratch_dir}/provenance-headless-linux-x64/dist/build-provenance-headless-linux-x64.json" \
  "headless-linux-x64" \
  "kast-headless-linux-x64.tar.zst"
write_provenance \
  "${scratch_dir}/provenance-runtime-manifest/dist/build-provenance-runtime-manifest.json" \
  "runtime-manifest" \
  "kast-runtime-manifest.json"
write_provenance \
  "${scratch_dir}/provenance-ubuntu-debian-headless/dist/build-provenance-ubuntu-debian-headless.json" \
  "ubuntu-debian-headless-x86_64" \
  "kast-ubuntu-debian-headless-x86_64-${tag}.tar.gz"

output="${scratch_dir}/dist/build-provenance.json"
"$assembler" \
  --output "$output" \
  "${scratch_dir}/provenance-cli-linux-arm64" \
  "${scratch_dir}/provenance-cli-linux-x64" \
  "${scratch_dir}/provenance-cli-macos-arm64" \
  "${scratch_dir}/provenance-cli-macos-x64" \
  "${scratch_dir}/provenance-headless-linux-x64" \
  "${scratch_dir}/provenance-idea" \
  "${scratch_dir}/provenance-runtime-manifest" \
  "${scratch_dir}/provenance-ubuntu-debian-headless"

python3 - "$output" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
platforms = [entry.get("platformId") for entry in payload.get("builds", [])]
expected = [
    "cli-linux-arm64",
    "cli-linux-x64",
    "cli-macos-arm64",
    "cli-macos-x64",
    "headless-linux-x64",
    "idea",
    "runtime-manifest",
    "ubuntu-debian-headless-x86_64",
]
if platforms != expected:
    raise SystemExit(f"unexpected platform order: {platforms!r}")
PY

"$assembler" \
  --output "$output" \
  "${scratch_dir}/provenance-cli-linux-arm64" \
  "${scratch_dir}/provenance-cli-linux-x64" \
  "${scratch_dir}/provenance-cli-macos-arm64" \
  "${scratch_dir}/provenance-cli-macos-x64" \
  "${scratch_dir}/provenance-headless-linux-x64" \
  "${scratch_dir}/provenance-idea" \
  "${scratch_dir}/provenance-runtime-manifest" \
  "${scratch_dir}/provenance-ubuntu-debian-headless"

rm "${scratch_dir}/provenance-idea/dist/build-provenance-idea.json"
if "$assembler" --output "$output" "${scratch_dir}/provenance-cli-linux-arm64" "${scratch_dir}/provenance-cli-linux-x64" "${scratch_dir}/provenance-cli-macos-arm64" "${scratch_dir}/provenance-cli-macos-x64" "${scratch_dir}/provenance-headless-linux-x64" "${scratch_dir}/provenance-idea" "${scratch_dir}/provenance-runtime-manifest" "${scratch_dir}/provenance-ubuntu-debian-headless" \
  >"${scratch_dir}/missing.out" 2>"${scratch_dir}/missing.err"; then
  die "assembler unexpectedly passed with missing idea provenance"
fi
grep -Fq "missing=['idea']" "${scratch_dir}/missing.err" || die "missing provenance failure did not name idea"

printf '%s\n' "Release provenance assembler test passed"
