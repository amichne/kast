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
  "${scratch_dir}/provenance-artifacts/release-asset-linux-x64-1/dist/build-provenance-linux-x64.json" \
  "linux-x64" \
  "kast-${tag}-linux-x64.zip"
write_provenance \
  "${scratch_dir}/provenance-artifacts/release-asset-linux-x64-1/dist/build-provenance-headless-agent-linux-x64.json" \
  "headless-agent-linux-x64" \
  "kast-headless-agent-${tag}-linux-x64.zip"
write_provenance \
  "${scratch_dir}/provenance-artifacts/release-asset-macos-arm64-1/dist/build-provenance-macos-arm64.json" \
  "macos-arm64" \
  "kast-${tag}-macos-arm64.zip"
write_provenance \
  "${scratch_dir}/provenance-intellij/dist/build-provenance-intellij.json" \
  "intellij" \
  "kast-intellij-${tag}.zip"
write_provenance \
  "${scratch_dir}/provenance-standalone/dist/build-provenance-standalone.json" \
  "standalone" \
  "kast-standalone-${tag}.zip"

output="${scratch_dir}/dist/build-provenance.json"
"$assembler" \
  --output "$output" \
  "${scratch_dir}/provenance-artifacts" \
  "${scratch_dir}/provenance-intellij" \
  "${scratch_dir}/provenance-standalone"

python3 - "$output" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
platforms = [entry.get("platformId") for entry in payload.get("builds", [])]
expected = [
    "headless-agent-linux-x64",
    "intellij",
    "linux-x64",
    "macos-arm64",
    "standalone",
]
if platforms != expected:
    raise SystemExit(f"unexpected platform order: {platforms!r}")
PY

rm "${scratch_dir}/provenance-intellij/dist/build-provenance-intellij.json"
if "$assembler" --output "$output" "${scratch_dir}/provenance-artifacts" "${scratch_dir}/provenance-intellij" "${scratch_dir}/provenance-standalone" \
  >"${scratch_dir}/missing.out" 2>"${scratch_dir}/missing.err"; then
  die "assembler unexpectedly passed with missing intellij provenance"
fi
grep -Fq "missing=['intellij']" "${scratch_dir}/missing.err" || die "missing provenance failure did not name intellij"

printf '%s\n' "Release provenance assembler test passed"
