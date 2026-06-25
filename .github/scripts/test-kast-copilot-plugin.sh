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

const tools = readText("extensions/kast/_shared/kast-tools.mjs");
assert(tools.includes("Preferred Kotlin funnel tool"), "tool guidance must prefer funnel tools");
assert(tools.includes("Bounded raw escape hatch"), "tool guidance must bound raw escape hatches");
const extension = readText("extensions/kast/extension.mjs");
assert(extension.includes("RECOVERABLE_WARMUP_CODES"), "extension must classify warmup errors");
assert(extension.includes('"INDEX_UNAVAILABLE"'), "extension must recover missing source indexes");
assert(extension.includes('"up"'), "extension must invoke kast up for warmup");
assert(extension.includes("createTraceEmitter"), "extension must wire structured tracing");
assert(extension.includes('"agent"') && extension.includes('"call"'), "extension must use kast agent call");
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
const pluginRoot = process.argv[2];
const toolsModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-tools.mjs`);
const traceModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-trace.mjs`);
const tools = toolsModule.makeKastTools((method, args) =>
  Promise.resolve(JSON.stringify({ ok: true, method, args })),
);
const names = new Set(tools.map((tool) => tool.name));
for (const required of ["kast_resolve", "kast_references", "kast_workspace_search", "kast_metrics"]) {
  if (!names.has(required)) throw new Error(`source plugin import missing ${required}`);
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
test -f "$tmp_dir/.github/extensions/kast/_shared/commands.json"
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
  ".github/extensions/kast/_shared/commands.json",
]) {
  const expected = join(target, relative);
  if (!outputs.has(expected)) throw new Error(`missing output path ${expected}`);
}
NODE

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
