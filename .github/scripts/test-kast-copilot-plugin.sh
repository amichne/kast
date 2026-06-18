#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
plugin_root="${repo_root}/cli-rs/resources/plugin"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-plugin-test.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT

node --input-type=module - "$plugin_root" <<'NODE'
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function readJson(relativePath) {
  return JSON.parse(readFileSync(join(root, relativePath), "utf8"));
}

function readText(relativePath) {
  return readFileSync(join(root, relativePath), "utf8");
}

function assertSameArray(actual, expected, label) {
  assert(JSON.stringify(actual) === JSON.stringify(expected), `${label} mismatch`);
}

const manifest = readJson("plugin.json");
assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
assert(manifest.name === "kast-copilot-lsp", "unexpected plugin name");

const entrypoints = manifest.entrypoints;
assert(entrypoints.lsp === "lsp.json", "unexpected LSP entrypoint");
assertSameArray(
  entrypoints.instructions,
  ["instructions/kast-kotlin.instructions.md"],
  "instructions entrypoint",
);
assertSameArray(
  entrypoints.agents,
  ["agents/kast-reader.agent.md", "agents/kast-writer.agent.md"],
  "agents entrypoint",
);
assertSameArray(
  entrypoints.extensions,
  ["extensions/kast/extension.mjs"],
  "extensions entrypoint",
);
assert(entrypoints.manifest === "primitive-manifest.json", "unexpected primitive manifest");
assert(existsSync(join(root, entrypoints.lsp)), "missing LSP entrypoint file");
assert(existsSync(join(root, entrypoints.instructions[0])), "missing instructions file");
assert(existsSync(join(root, entrypoints.agents[0])), "missing reader agent file");
assert(existsSync(join(root, entrypoints.agents[1])), "missing writer agent file");
assert(existsSync(join(root, entrypoints.extensions[0])), "missing extension file");

const primitive = readJson("primitive-manifest.json");
assert(
  primitive.type === "KAST_COPILOT_PRIMITIVE_MANIFEST",
  "unexpected primitive manifest type",
);
const targets = new Set(primitive.outputs.map((output) => output.target));
const expectedTargets = new Set([
  "agents/kast-reader.agent.md",
  "agents/kast-writer.agent.md",
  "lsp.json",
  "instructions/kast-kotlin.instructions.md",
  "extensions/kast/extension.mjs",
  "extensions/kast/_shared/kast-agents.mjs",
  "extensions/kast/_shared/kast-tools.mjs",
  "extensions/kast/_shared/commands.json",
]);
assert(
  targets.size === expectedTargets.size &&
    [...expectedTargets].every((target) => targets.has(target)),
  "primitive manifest outputs mismatch",
);

const lsp = readJson("lsp.json");
const server = lsp.lspServers["kotlin"];
assertSameArray(server.args, ["lsp", "--stdio"], "LSP args");
assert(server.initializationTimeoutMs >= 120000, "LSP timeout must allow startup");
assert(server.initializationOptions.failOnStaleIndex === true, "LSP must fail on stale indexes");

const instruction = readText("instructions/kast-kotlin.instructions.md");
assert(
  instruction.includes("start with the `kotlin` LSP server"),
  "instructions must route through the LSP",
);
assert(
  instruction.includes("`kast-reader` for read-only analysis and `kast-writer` for"),
  "instructions must route delegation through the two Kast agents",
);
assert(
  instruction.includes(
    "Treat stale, not-ready, missing, ambiguous, partial, or truncated compiler facts",
  ),
  "instructions must identify blocked compiler facts",
);
assert(instruction.includes("as blockers"), "instructions must fail closed on blockers");

const tools = readText("extensions/kast/_shared/kast-tools.mjs");
assert(tools.includes("Preferred Kotlin funnel tool"), "tool guidance must prefer funnel tools");
assert(tools.includes("Bounded raw escape hatch"), "tool guidance must bound raw escape hatches");

const reader = readText("agents/kast-reader.agent.md");
const writer = readText("agents/kast-writer.agent.md");
assert(reader.includes("name: Kast Reader"), "reader agent must be named");
assert(writer.includes("name: Kast Writer"), "writer agent must be named");
assert(!reader.includes("kast_write_and_validate"), "reader must not expose write-and-validate");
assert(!reader.includes("kast_rename"), "reader must not expose rename");
assert(!reader.includes("  - edit"), "reader must not expose edit");
assert(!reader.includes("  - execute"), "reader must not expose execute");
assert(writer.includes("kast_write_and_validate"), "writer must expose write-and-validate");
assert(writer.includes("kast_rename"), "writer must expose rename");
assert(writer.includes("  - edit"), "writer must expose edit");
assert(writer.includes("  - execute"), "writer must expose execute");
NODE

ensure_kast_bin() {
  if [[ -n "${KAST_BIN:-}" ]]; then
    export KAST_BIN
    return
  fi
  if [[ -x "${repo_root}/cli-rs/target/debug/kast" ]]; then
    KAST_BIN="${repo_root}/cli-rs/target/debug/kast"
  elif [[ -x "${repo_root}/cli-rs/target/release/kast" ]]; then
    KAST_BIN="${repo_root}/cli-rs/target/release/kast"
  else
    cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --bin kast --locked
    KAST_BIN="${repo_root}/cli-rs/target/debug/kast"
  fi
  export KAST_BIN
}

ensure_kast_bin

node --input-type=module - "$plugin_root" <<'NODE'
const pluginRoot = process.argv[2];
const toolsModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-tools.mjs`);
const agentsModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-agents.mjs`);
const tools = toolsModule.makeKastTools((method, args) =>
  Promise.resolve(JSON.stringify({ ok: true, method, args })),
);
const names = new Set(tools.map((tool) => tool.name));
for (const required of ["kast_resolve", "kast_references", "kast_workspace_search", "kast_metrics"]) {
  if (!names.has(required)) throw new Error(`source plugin import missing ${required}`);
}
const agents = agentsModule.makeKastCustomAgents();
const reader = agents.find((agent) => agent.name === "kast-reader");
const writer = agents.find((agent) => agent.name === "kast-writer");
if (!reader) throw new Error("source plugin import missing kast-reader");
if (!writer) throw new Error("source plugin import missing kast-writer");
for (const writeTool of ["kast_rename", "kast_write_and_validate", "edit", "execute"]) {
  if (reader.tools.includes(writeTool)) throw new Error(`source reader must not include ${writeTool}`);
  if (!writer.tools.includes(writeTool)) throw new Error(`source writer must include ${writeTool}`);
}
NODE

"${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"

test -f "$tmp_dir/.github/lsp.json"
test -f "$tmp_dir/.github/instructions/kast-kotlin.instructions.md"
test -f "$tmp_dir/.github/agents/kast-reader.agent.md"
test -f "$tmp_dir/.github/agents/kast-writer.agent.md"
test -f "$tmp_dir/.github/extensions/kast/extension.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/kast-agents.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/kast-tools.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/commands.json"

node --input-type=module - "$repo_root" "$tmp_dir" <<'NODE'
import { readFileSync } from "node:fs";
import { join } from "node:path";

const repo = process.argv[2];
const target = process.argv[3];
const installed = readFileSync(
  join(target, ".github/extensions/kast/_shared/commands.json"),
  "utf8",
);
const source = readFileSync(
  join(repo, "cli-rs/resources/kast-skill/references/commands.json"),
  "utf8",
);
if (installed !== source) {
  throw new Error("installed commands.json must match the checked-in RPC catalog");
}
NODE

node --input-type=module - "$tmp_dir" <<'NODE'
const target = process.argv[2];
const toolsModule = await import(`file://${target}/.github/extensions/kast/_shared/kast-tools.mjs`);
const agentsModule = await import(`file://${target}/.github/extensions/kast/_shared/kast-agents.mjs`);
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
const agents = agentsModule.makeKastCustomAgents();
const agentNames = new Set(agents.map((agent) => agent.name));
for (const required of ["kast-reader", "kast-writer"]) {
  if (!agentNames.has(required)) throw new Error(`missing ${required}`);
}
const reader = agents.find((agent) => agent.name === "kast-reader");
const writer = agents.find((agent) => agent.name === "kast-writer");
for (const writeTool of ["kast_rename", "kast_write_and_validate", "edit", "execute"]) {
  if (reader.tools.includes(writeTool)) throw new Error(`reader must not include ${writeTool}`);
  if (!writer.tools.includes(writeTool)) throw new Error(`writer must include ${writeTool}`);
}
for (const readTool of ["kast_resolve", "kast_references", "kast_workspace_search", "kast_diagnostics"]) {
  if (!reader.tools.includes(readTool)) throw new Error(`reader missing ${readTool}`);
  if (!writer.tools.includes(readTool)) throw new Error(`writer missing ${readTool}`);
}
NODE

printf 'Kast Copilot plugin tests passed\n'
