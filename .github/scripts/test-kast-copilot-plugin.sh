#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
plugin_root="${repo_root}/cli-rs/resources/plugin"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-plugin-test.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT
host_home="${HOME:-}"
if [[ -n "$host_home" ]]; then
  export CARGO_HOME="${CARGO_HOME:-${host_home}/.cargo}"
  export RUSTUP_HOME="${RUSTUP_HOME:-${host_home}/.rustup}"
fi
if [[ -n "${CARGO_HOME:-}" && -d "${CARGO_HOME}/bin" ]]; then
  export PATH="${CARGO_HOME}/bin:${PATH}"
fi
export HOME="${tmp_dir}/home"
export KAST_CONFIG_HOME="${tmp_dir}/kast-config"
mkdir -p "$HOME"
git -c init.defaultBranch=main -C "$tmp_dir" init >/dev/null

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
assert(!("instructions" in entrypoints), "static instruction entrypoints must not be exposed");
assert(!("agents" in entrypoints), "custom agent entrypoints must not be exposed");
assertSameArray(
  entrypoints.extensions,
  ["extensions/kast/extension.mjs"],
  "extensions entrypoint",
);
assert(entrypoints.manifest === "primitive-manifest.json", "unexpected primitive manifest");
assert(existsSync(join(root, entrypoints.lsp)), "missing LSP entrypoint file");
assert(existsSync(join(root, entrypoints.extensions[0])), "missing extension file");

const primitive = readJson("primitive-manifest.json");
assert(
  primitive.type === "KAST_COPILOT_PRIMITIVE_MANIFEST",
  "unexpected primitive manifest type",
);
const targets = new Set(primitive.outputs.map((output) => output.target));
const expectedTargets = new Set([
  "lsp.json",
  "extensions/kast/extension.mjs",
  "extensions/kast/_shared/kast-trace.mjs",
  "extensions/kast/_shared/kast-tools.mjs",
]);
assert(
  targets.size === expectedTargets.size &&
    [...expectedTargets].every((target) => targets.has(target)),
  "primitive manifest outputs mismatch",
);

const lsp = readJson("lsp.json");
const server = lsp.lspServers["kotlin"];
assertSameArray(server.args, ["agent", "lsp", "--stdio"], "LSP args");
assert(server.initializationTimeoutMs >= 120000, "LSP timeout must allow startup");
assert(server.initializationOptions.failOnStaleIndex === true, "LSP must fail on stale indexes");

const tools = readText("extensions/kast/_shared/kast-tools.mjs");
assert(!tools.includes("buildBundledToolSpecs"), "extension must not reconstruct tool specs from a bundled catalog");
const extension = readText("extensions/kast/extension.mjs");
assert(extension.includes("RECOVERABLE_WARMUP_CODES"), "extension must classify warmup errors");
assert(extension.includes('"INDEX_UNAVAILABLE"'), "extension must recover missing source indexes");
assert(extension.includes('"agent"') && extension.includes('"up"') && extension.includes('"--no-onboard"'), "extension must invoke kast agent up for warmup");
assert(extension.includes("createTraceEmitter"), "extension must wire structured tracing");
assert(extension.includes('"agent"') && extension.includes('"call"'), "extension must use kast agent call");
assert(extension.includes('"agent"') && extension.includes('"tools"'), "extension must load tool specs from kast agent tools");
assert(extension.includes("isKastAgentToolsEnvelope"), "extension must validate the full KAST_AGENT_TOOLS envelope");
assert(!extension.includes("bundled-catalog-fallback"), "extension must not fall back to reconstructed tool specs");
assert(!extension.includes("bundledKastToolSpecs"), "extension must not import reconstructed tool specs");
assert(!extension.includes("rpcArgs("), "extension must not route tools through raw kast rpc");
assert(extension.includes("KAST_TOOLING_CONTEXT"), "extension must own runtime tooling guidance");
assert(extension.includes("onUserPromptSubmitted"), "extension must inject prompt-time tooling guidance");
assert(extension.includes("additionalContext"), "extension hooks must pass tooling guidance as context");
assert(
  !extension.includes("customAgents") && !extension.includes("makeKastCustomAgents"),
  "extension must register tools without custom agents",
);
NODE

ensure_kast_bin() {
  if [[ -n "${KAST_BIN:-}" ]]; then
    export KAST_BIN
    return
  fi
  cargo build --manifest-path "${repo_root}/cli-rs/Cargo.toml" --bin kast --locked
  KAST_BIN="${repo_root}/cli-rs/target/debug/kast"
  export KAST_BIN
}

ensure_kast_bin

node --input-type=module - "$plugin_root" <<'NODE'
import { execFileSync } from "node:child_process";
const pluginRoot = process.argv[2];
const toolsModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-tools.mjs`);
const traceModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-trace.mjs`);
const agentTools = JSON.parse(execFileSync(process.env.KAST_BIN, ["--output", "json", "agent", "tools", "--full"], { encoding: "utf8" }));
if (!toolsModule.isKastAgentToolsEnvelope(agentTools)) {
  throw new Error("source plugin must accept the current KAST_AGENT_TOOLS envelope");
}
if (toolsModule.isKastAgentToolsEnvelope({
  ok: true,
  method: "agent/tools",
  result: { type: "WRONG", tools: [] },
})) {
  throw new Error("source plugin must reject malformed agent tools envelopes");
}
const sourceValidInvocation = {
  command: "kast agent call",
  argv: ["kast", "agent", "call", "<method>"],
  methodArgument: "<method>",
  paramsFileFlag: "--params-file",
  workspaceRootFlag: "--workspace-root",
};
function sourceEnvelopeWith(result) {
  return {
    ok: true,
    method: "agent/tools",
    result: {
      type: "KAST_AGENT_TOOLS",
      schemaVersion: 3,
      catalogSha256: "0".repeat(64),
      toolCount: 0,
      invocation: sourceValidInvocation,
      tools: [],
      ...result,
    },
  };
}
if (toolsModule.isKastAgentToolsEnvelope(sourceEnvelopeWith({ invocation: undefined }))) {
  throw new Error("source plugin must reject missing agent tool invocation");
}
if (toolsModule.isKastAgentToolsEnvelope(sourceEnvelopeWith({
  invocation: { ...sourceValidInvocation, argv: ["kast", "rpc", "<method>"] },
}))) {
  throw new Error("source plugin must reject malformed agent tool invocation");
}
if (toolsModule.isKastAgentToolsEnvelope(sourceEnvelopeWith({ schemaVersion: 2 }))) {
  throw new Error("source plugin must reject stale agent tool schema versions");
}
if (toolsModule.isKastAgentToolsEnvelope(sourceEnvelopeWith({ catalogSha256: "not-a-checksum" }))) {
  throw new Error("source plugin must reject malformed agent tool catalog checksums");
}
if (toolsModule.isKastAgentToolsEnvelope(sourceEnvelopeWith({ toolCount: 1 }))) {
  throw new Error("source plugin must reject mismatched agent tool counts");
}
const specs = toolsModule.toolSpecsFromAgentToolsResult(agentTools);
const tools = toolsModule.makeKastTools(specs, (method, args) =>
  Promise.resolve(JSON.stringify({ ok: true, method, args })),
);
const names = new Set(tools.map((tool) => tool.name));
for (const required of ["kast_symbol_query", "kast_resolve", "kast_references", "kast_workspace_search", "kast_metrics"]) {
  if (!names.has(required)) throw new Error(`source plugin import missing ${required}`);
}
const sourceResolveTool = tools.find((tool) => tool.name === "kast_resolve");
if (!sourceResolveTool.description.includes("Preferred Kotlin funnel tool")) {
  throw new Error("source symbol tools must use CLI-provided funnel guidance");
}
const sourceScaffoldTool = tools.find((tool) => tool.name === "kast_scaffold");
if (!sourceScaffoldTool.parameters.properties.kind.type.includes("null")) {
  throw new Error("source tool schemas must preserve nullable fields from kast agent tools");
}
const sourceRenameTool = tools.find((tool) => tool.name === "kast_rename");
if (!Array.isArray(sourceRenameTool.parameters.oneOf) || sourceRenameTool.parameters.oneOf.length < 2) {
  throw new Error("source tool schemas must preserve variant oneOf schemas from kast agent tools");
}
const trace = traceModule.createTraceEmitter({
  env: { KAST_COPILOT_TRACE: "1" },
  repoRoot: pluginRoot,
  processId: 123,
  now: () => "2026-06-20T00:00:00.000Z",
  idFactory: () => "agent-instance-test",
});
const record = trace.emit("copilot.test", {
  invocationId: "invocation-test",
  agentRole: "kast-tool",
  sdkRegistrationScope: "extension-session",
  ...traceModule.traceFieldsFromParams({ filePath: `${pluginRoot}/extensions/kast/_shared/kast-tools.mjs`, moduleName: "plugin" }),
  detail: { method: "raw/file-outline" },
});
if (record.type !== "kast.copilot.trace") throw new Error("trace record type mismatch");
if (record.schemaVersion !== 1) throw new Error("trace schema version mismatch");
if (record.invocationId !== "invocation-test") throw new Error("trace invocation id missing");
if (record.agentRole !== "kast-tool") throw new Error("trace agent role missing");
if (record.sdkRegistrationScope !== "extension-session") throw new Error("trace registration scope missing");
if (!record.canonicalWorkspaceRoot) throw new Error("trace canonical workspace root missing");
if (!record.canonicalTargetFilePath?.endsWith("extensions/kast/_shared/kast-tools.mjs")) {
  throw new Error("trace canonical target file path missing");
}
NODE

"${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"

test -f "$tmp_dir/.github/lsp.json"
test -f "$tmp_dir/.github/extensions/kast/extension.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/kast-trace.mjs"
test -f "$tmp_dir/.github/extensions/kast/_shared/kast-tools.mjs"
test ! -e "$tmp_dir/.github/.kast-copilot-version"

node --input-type=module - "$HOME/.local/share/kast/install.json" "$tmp_dir" <<'NODE'
import { readFileSync, realpathSync } from "node:fs";
import { join } from "node:path";

const manifestPath = process.argv[2];
const target = realpathSync(process.argv[3]);
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const repo = manifest.repos.find((candidate) => candidate.path === target);
if (!repo) throw new Error(`missing managed repo record for ${target}`);
if (repo.copilotPackageVersion) throw new Error("copilotPackageVersion must not be written for new installs");
const resource = repo.resources?.find((candidate) => candidate.kind === "COPILOT_PACKAGE");
if (!resource) throw new Error("missing COPILOT_PACKAGE resource record");
if (!/^[a-f0-9]{64}$/.test(resource.sourceBundleSha256)) {
  throw new Error("source bundle checksum must be a SHA-256 hex string");
}
const outputs = new Set(resource.outputPaths.map((outputPath) => realpathSync(outputPath)));
for (const relative of [
  ".github/lsp.json",
  ".github/extensions/kast/extension.mjs",
  ".github/extensions/kast/_shared/kast-tools.mjs",
  ".github/extensions/kast/_shared/kast-trace.mjs",
]) {
  const expected = join(target, relative);
  if (!outputs.has(expected)) throw new Error(`missing output path ${expected}`);
}
NODE

node --input-type=module - "$tmp_dir" <<'NODE'
import { execFileSync } from "node:child_process";
const target = process.argv[2];
const toolsModule = await import(`file://${target}/.github/extensions/kast/_shared/kast-tools.mjs`);
const agentTools = JSON.parse(execFileSync(process.env.KAST_BIN, ["--output", "json", "agent", "tools", "--full"], { encoding: "utf8" }));
if (!toolsModule.isKastAgentToolsEnvelope(agentTools)) {
  throw new Error("installed plugin must accept the current KAST_AGENT_TOOLS envelope");
}
if (toolsModule.isKastAgentToolsEnvelope({
  ok: true,
  method: "agent/tools",
  result: { type: "WRONG", tools: [] },
})) {
  throw new Error("installed plugin must reject malformed agent tools envelopes");
}
const installedValidInvocation = {
  command: "kast agent call",
  argv: ["kast", "agent", "call", "<method>"],
  methodArgument: "<method>",
  paramsFileFlag: "--params-file",
  workspaceRootFlag: "--workspace-root",
};
function installedEnvelopeWith(result) {
  return {
    ok: true,
    method: "agent/tools",
    result: {
      type: "KAST_AGENT_TOOLS",
      schemaVersion: 3,
      catalogSha256: "0".repeat(64),
      toolCount: 0,
      invocation: installedValidInvocation,
      tools: [],
      ...result,
    },
  };
}
if (toolsModule.isKastAgentToolsEnvelope(installedEnvelopeWith({ invocation: undefined }))) {
  throw new Error("installed plugin must reject missing agent tool invocation");
}
if (toolsModule.isKastAgentToolsEnvelope(installedEnvelopeWith({
  invocation: { ...installedValidInvocation, argv: ["kast", "rpc", "<method>"] },
}))) {
  throw new Error("installed plugin must reject malformed agent tool invocation");
}
if (toolsModule.isKastAgentToolsEnvelope(installedEnvelopeWith({ schemaVersion: 2 }))) {
  throw new Error("installed plugin must reject stale agent tool schema versions");
}
if (toolsModule.isKastAgentToolsEnvelope(installedEnvelopeWith({ catalogSha256: "not-a-checksum" }))) {
  throw new Error("installed plugin must reject malformed agent tool catalog checksums");
}
if (toolsModule.isKastAgentToolsEnvelope(installedEnvelopeWith({ toolCount: 1 }))) {
  throw new Error("installed plugin must reject mismatched agent tool counts");
}
const specs = toolsModule.toolSpecsFromAgentToolsResult(agentTools);
const tools = toolsModule.makeKastTools(specs, (method, args) =>
  Promise.resolve(JSON.stringify({ ok: true, method, args })),
);
const names = new Set(tools.map((tool) => tool.name));
for (const required of ["kast_symbol_query", "kast_resolve", "kast_references", "kast_workspace_files", "kast_metrics"]) {
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
const scaffoldTool = tools.find((tool) => tool.name === "kast_scaffold");
if (!scaffoldTool.parameters.properties.kind.type.includes("null")) {
  throw new Error("installed tool schemas must preserve nullable fields from kast agent tools");
}
const renameTool = tools.find((tool) => tool.name === "kast_rename");
if (!Array.isArray(renameTool.parameters.oneOf) || renameTool.parameters.oneOf.length < 2) {
  throw new Error("installed tool schemas must preserve variant oneOf schemas from kast agent tools");
}
NODE

printf 'Kast Copilot plugin tests passed\n'
