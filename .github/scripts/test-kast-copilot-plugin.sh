#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
plugin_root="${repo_root}/cli-rs/resources/plugin"

cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --locked

test -f "${plugin_root}/lsp.json"
test -f "${plugin_root}/primitive-manifest.json"
test ! -e "${plugin_root}/extensions"
rg -q '"name": "kast-copilot-lsp"' "${plugin_root}/plugin.json"
rg -q '"lsp": "lsp.json"' "${plugin_root}/plugin.json"
test "$(rg -c '"target":' "${plugin_root}/primitive-manifest.json")" -eq 1
rg -q '"target": "lsp.json"' "${plugin_root}/primitive-manifest.json"
