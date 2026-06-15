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
assert entrypoints["lsp"] == "lsp.json"
assert entrypoints["instructions"] == ["instructions/kast-kotlin.instructions.md"]
assert entrypoints["extensions"] == ["extensions/kast/extension.mjs"]
assert entrypoints["manifest"] == "primitive-manifest.json"
assert (root / entrypoints["lsp"]).is_file()
assert (root / entrypoints["instructions"][0]).is_file()
assert (root / entrypoints["extensions"][0]).is_file()

primitive = json.loads((root / "primitive-manifest.json").read_text())
assert primitive["type"] == "KAST_COPILOT_PRIMITIVE_MANIFEST"
targets = {output["target"] for output in primitive["outputs"]}
assert targets == {
    "lsp.json",
    "instructions/kast-kotlin.instructions.md",
    "extensions/kast/extension.mjs",
    "extensions/kast/_shared/kast-tools.mjs",
    "extensions/kast/_shared/commands.json",
}

lsp = json.loads((root / "lsp.json").read_text())
server = lsp["lspServers"]["kast-kotlin"]
assert server["args"] == ["lsp", "--stdio"]
assert server["initializationTimeoutMs"] >= 120000
assert server["initializationOptions"]["failOnStaleIndex"] is True

instruction = (root / "instructions/kast-kotlin.instructions.md").read_text()
assert "start with the `kast-kotlin` LSP server" in instruction
assert "Treat stale, not-ready, missing, ambiguous, partial, or truncated compiler facts" in instruction
assert "as blockers" in instruction

tools = (root / "extensions/kast/_shared/kast-tools.mjs").read_text()
assert "Preferred Kotlin funnel tool" in tools
assert "Bounded raw escape hatch" in tools
PY

"${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"

test -f "$tmp_dir/.github/lsp.json"
test -f "$tmp_dir/.github/instructions/kast-kotlin.instructions.md"
test -f "$tmp_dir/.github/extensions/kast/extension.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/kast-tools.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/commands.json"

python3 - "$repo_root" "$tmp_dir" <<'PY'
import pathlib
import sys

repo = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
assert (
    target / ".github/extensions/kast/_shared/commands.json"
).read_text() == (
    repo / "cli-rs/resources/kast-skill/references/commands.json"
).read_text()
PY

node --input-type=module - "$tmp_dir" <<'NODE'
const target = process.argv[2];
const toolsModule = await import(`file://${target}/.github/extensions/kast/_shared/kast-tools.mjs`);
const tools = toolsModule.makeKastTools((method, args) =>
  Promise.resolve(JSON.stringify({ ok: true, method, args })),
);
const names = new Set(tools.map((tool) => tool.name));
for (const required of ["kast_resolve", "kast_references", "kast_workspace_files", "kast_metrics"]) {
  if (!names.has(required)) throw new Error(`missing ${required}`);
}
const resolveTool = tools.find((tool) => tool.name === "kast_resolve");
if (!resolveTool.description.includes("Preferred Kotlin funnel tool")) {
  throw new Error("symbol tools must include funnel guidance");
}
const workspaceFiles = tools.find((tool) => tool.name === "kast_workspace_files");
if (!workspaceFiles.description.includes("Secondary workspace inspection tool")) {
  throw new Error("workspace files must be secondary");
}
NODE

printf 'Kast Copilot plugin tests passed\n'
