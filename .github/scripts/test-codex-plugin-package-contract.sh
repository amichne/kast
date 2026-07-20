#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
version="$(sed -n 's/^version = "\(.*\)"/\1/p' "$repo_root/cli-rs/Cargo.toml" | head -n 1)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-codex-package.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
sh -n "$repo_root/cli-rs/resources/codex-plugin/plugins/kast/scripts/kast-codex-hook"
(
  cd "$repo_root/cli-rs/resources/codex-plugin"
  zip -q -r "$tmp_dir/plugin.zip" marketplace.json .agents plugins
)
python3 "$repo_root/.github/scripts/verify-codex-plugin-package.py" "$tmp_dir/plugin.zip" "$version"
