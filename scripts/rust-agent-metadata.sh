#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 0 ]]; then
  printf '%s\n' 'usage: scripts/rust-agent-metadata.sh' >&2
  exit 2
fi

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

exec cargo metadata \
  --manifest-path "${repo_root}/cli-rs/Cargo.toml" \
  --format-version 1 \
  --locked \
  --offline \
  --no-deps
