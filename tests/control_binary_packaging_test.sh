#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kast-control-packaging-test.XXXXXX")"
trap 'find "$scratch" -depth -delete' EXIT

mkdir -p \
  "$scratch/cli" \
  "$scratch/backend/backend-headless/runtime-libs" \
  "$scratch/backend/backend-headless/idea-home/lib" \
  "$scratch/backend/backend-headless/idea-home/modules" \
  "$scratch/backend/backend-headless/idea-home/plugins/kast-headless" \
  "$scratch/output" \
  "$scratch/extracted"

printf '%s\n' '#!/bin/sh' 'exit 0' >"$scratch/cli/_kastctl"
cp "$scratch/cli/_kastctl" "$scratch/cli/kast"
chmod 755 "$scratch/cli/_kastctl" "$scratch/cli/kast"
printf '%s\n' 'fixture' >"$scratch/backend/backend-headless/runtime-libs/classpath.txt"
: >"$scratch/backend/backend-headless/idea-home/lib/nio-fs.jar"
: >"$scratch/backend/backend-headless/idea-home/modules/module-descriptors.dat"

(cd "$scratch/cli" && zip -X -q "$scratch/cli.zip" _kastctl kast)
(cd "$scratch/backend" && zip -X -q -r "$scratch/backend.zip" backend-headless)

"$repo_root/scripts/packaging/package-headless-runtime.sh" \
  --cli-archive "$scratch/cli.zip" \
  --backend-archive "$scratch/backend.zip" \
  --version v0.0.0-test \
  --output "$scratch/output/runtime.tar.zst" \
  --manifest-output "$scratch/output/manifest.json"

tar --zstd -xf "$scratch/output/runtime.tar.zst" -C "$scratch/extracted"
[[ -x "$scratch/extracted/bin/_kastctl" ]] || {
  printf '%s\n' 'runtime is missing executable bin/_kastctl' >&2
  exit 1
}
[[ -x "$scratch/extracted/bin/kast" ]] || {
  printf '%s\n' 'runtime is missing executable bin/kast' >&2
  exit 1
}
cmp -s "$scratch/extracted/bin/_kastctl" "$scratch/extracted/bin/kast" || {
  printf '%s\n' 'runtime control and agent entrypoints are not byte-identical' >&2
  exit 1
}

printf '%s\n' 'control binary packaging contract passed'
