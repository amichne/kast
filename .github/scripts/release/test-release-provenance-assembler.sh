#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

write_provenance() {
  local path="$1"
  local platform="$2"
  local asset="$3"
  mkdir -p "$(dirname -- "$path")"
  cat >"$path" <<JSON
{
  "platformId": "${platform}",
  "assetName": "${asset}",
  "assetDigest": "sha256:$(printf '%064d' 1)"
}
JSON
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
assembler="${repo_root}/scripts/release/assemble-release-provenance.py"
[[ -x "$assembler" ]] || die "release provenance assembler is missing or not executable: $assembler"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-release-provenance.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT
tag="v9.8.7"

write_provenance \
  "${scratch_dir}/provenance-openapi/build-provenance-openapi.json" \
  openapi \
  openapi.yaml
for platform in linux-arm64 linux-x64 macos-arm64 macos-x64; do
  write_provenance \
    "${scratch_dir}/provenance-setup/build-provenance-setup-${platform}.json" \
    "setup-${platform}" \
    "kast-${platform}-${tag}.tar.gz"
done

output="${scratch_dir}/dist/build-provenance.json"
provenance_roots=(
  "${scratch_dir}/provenance-openapi"
  "${scratch_dir}/provenance-setup"
)
"$assembler" --output "$output" --tag "$tag" "${provenance_roots[@]}"

python3 - "$output" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
platforms = [entry.get("platformId") for entry in payload.get("builds", [])]
expected = [
    "openapi",
    "setup-linux-arm64",
    "setup-linux-x64",
    "setup-macos-arm64",
    "setup-macos-x64",
]
if platforms != expected:
    raise SystemExit(f"unexpected platform order: {platforms!r}")
PY

first_digest="$(shasum -a 256 "$output" | awk '{print $1}')"
"$assembler" --output "$output" --tag "$tag" "${provenance_roots[@]}"
second_digest="$(shasum -a 256 "$output" | awk '{print $1}')"
[[ "$first_digest" == "$second_digest" ]] \
  || die "provenance assembly is not deterministic"

rm "${scratch_dir}/provenance-openapi/build-provenance-openapi.json"
if "$assembler" --output "$output" --tag "$tag" "${provenance_roots[@]}" \
  >"${scratch_dir}/missing.out" 2>"${scratch_dir}/missing.err"; then
  die "assembler unexpectedly passed with missing OpenAPI provenance"
fi
grep -Fq "missing=['openapi']" "${scratch_dir}/missing.err" \
  || die "missing provenance failure did not name OpenAPI"

printf '%s\n' "Release provenance assembler test passed"
