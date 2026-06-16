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
  entrypoints.extensions,
  ["extensions/kast/extension.mjs"],
  "extensions entrypoint",
);
assert(entrypoints.manifest === "primitive-manifest.json", "unexpected primitive manifest");
assert(existsSync(join(root, entrypoints.lsp)), "missing LSP entrypoint file");
assert(existsSync(join(root, entrypoints.instructions[0])), "missing instructions file");
assert(existsSync(join(root, entrypoints.extensions[0])), "missing extension file");

const primitive = readJson("primitive-manifest.json");
assert(
  primitive.type === "KAST_COPILOT_PRIMITIVE_MANIFEST",
  "unexpected primitive manifest type",
);
const targets = new Set(primitive.outputs.map((output) => output.target));
const expectedTargets = new Set([
  "lsp.json",
  "instructions/kast-kotlin.instructions.md",
  "extensions/kast/extension.mjs",
  "extensions/kast/_shared/kast-tools.mjs",
  "extensions/kast/_shared/commands.json",
]);
assert(
  targets.size === expectedTargets.size &&
    [...expectedTargets].every((target) => targets.has(target)),
  "primitive manifest outputs mismatch",
);

const lsp = readJson("lsp.json");
const server = lsp.lspServers["kast-kotlin"];
assertSameArray(server.args, ["lsp", "--stdio"], "LSP args");
assert(server.initializationTimeoutMs >= 120000, "LSP timeout must allow startup");
assert(server.initializationOptions.failOnStaleIndex === true, "LSP must fail on stale indexes");

const instruction = readText("instructions/kast-kotlin.instructions.md");
assert(
  instruction.includes("start with the `kast-kotlin` LSP server"),
  "instructions must route through the LSP",
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

"${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"

test -f "$tmp_dir/.github/lsp.json"
test -f "$tmp_dir/.github/instructions/kast-kotlin.instructions.md"
test -f "$tmp_dir/.github/extensions/kast/extension.mjs"
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
