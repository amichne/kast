#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
plugin_root="${repo_root}/cli-rs/resources/plugin"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-plugin-test.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT

python3 - "$plugin_root" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "plugin.json").read_text())
assert manifest["schemaVersion"] == 1
assert manifest["name"] == "kast-copilot-lsp"
entrypoints = manifest["entrypoints"]
assert entrypoints == {"lsp": "lsp.json"}
assert (root / entrypoints["lsp"]).is_file()

lsp = json.loads((root / "lsp.json").read_text())
server = lsp["lspServers"]["kast-kotlin"]
assert server["args"] == ["lsp", "--stdio"]
assert server["initializationTimeoutMs"] >= 120000
assert server["initializationOptions"]["failOnStaleIndex"] is True
PY

"${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"

test -f "$tmp_dir/.github/lsp.json"

printf 'Kast Copilot plugin tests passed\n'
