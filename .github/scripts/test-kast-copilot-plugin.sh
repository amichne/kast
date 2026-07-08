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

const extension = readText("extensions/kast/extension.mjs");
assert(extension.includes("RECOVERABLE_WARMUP_CODES"), "extension must classify warmup errors");
assert(extension.includes('"INDEX_UNAVAILABLE"'), "extension must recover missing source indexes");
assert(extension.includes("kast agent symbol") && extension.includes("kast agent diagnostics"), "extension must guide typed agent commands");
assert(extension.includes("kast agent impact") && extension.includes("kast agent rename"), "extension must guide impact and rename commands");
assert(extension.includes("createTraceEmitter"), "extension must wire structured tracing");
assert(!extension.includes('"call"'), "extension must not use removed kast agent call");
assert(!extension.includes('"workflow"'), "extension must not use removed kast agent workflow");
assert(!extension.includes("isKastAgentToolsEnvelope"), "extension must not validate removed KAST_AGENT_TOOLS envelopes");
assert(!extension.includes("bundled-catalog-fallback"), "extension must not fall back to reconstructed tool specs");
assert(!extension.includes("bundledKastToolSpecs"), "extension must not import reconstructed tool specs");
assert(!extension.includes("rpcArgs("), "extension must not route tools through raw kast rpc");
assert(extension.includes("KAST_TOOLING_CONTEXT"), "extension must own runtime tooling guidance");
assert(extension.includes("onUserPromptSubmitted"), "extension must inject prompt-time tooling guidance");
assert(extension.includes("additionalContext"), "extension hooks must pass tooling guidance as context");
assert(extension.includes("tools: []"), "extension must not register dynamic Copilot tools");
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
import { spawnSync } from "node:child_process";
const pluginRoot = process.argv[2];
const traceModule = await import(`file://${pluginRoot}/extensions/kast/_shared/kast-trace.mjs`);
const agentTools = spawnSync(process.env.KAST_BIN, ["--output", "json", "agent", "tools", "--full"], { encoding: "utf8" });
if (agentTools.status === 0) throw new Error("kast agent tools must remain removed");
const removedEnvelope = JSON.parse(agentTools.stdout);
if (removedEnvelope.ok !== false || removedEnvelope.method !== "agent/tools" || removedEnvelope.error?.code !== "AGENT_COMMAND_REMOVED") {
  throw new Error(`unexpected removed agent tools envelope: ${agentTools.stdout}`);
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
  agentRole: "kast-guidance",
  sdkRegistrationScope: "extension-session",
  ...traceModule.traceFieldsFromParams({ filePath: `${pluginRoot}/extensions/kast/extension.mjs`, moduleName: "plugin" }),
  detail: { command: "kast agent symbol" },
});
if (record.type !== "kast.copilot.trace") throw new Error("trace record type mismatch");
if (record.schemaVersion !== 1) throw new Error("trace schema version mismatch");
if (record.invocationId !== "invocation-test") throw new Error("trace invocation id missing");
if (record.agentRole !== "kast-guidance") throw new Error("trace agent role missing");
if (record.sdkRegistrationScope !== "extension-session") throw new Error("trace registration scope missing");
if (!record.canonicalWorkspaceRoot) throw new Error("trace canonical workspace root missing");
if (!record.canonicalTargetFilePath?.endsWith("extensions/kast/extension.mjs")) {
  throw new Error("trace canonical target file path missing");
}
NODE

install_status=0
if "${plugin_root}/scripts/install-local.sh" --target "$tmp_dir" --force >"${tmp_dir}/install.json"; then
  install_status=0
else
  install_status=$?
fi
if [[ "$install_status" -eq 0 ]]; then
  printf '%s\n' "expected removed Copilot installer to fail" >&2
  exit 1
fi

test ! -e "$tmp_dir/.github/lsp.json"
test ! -e "$tmp_dir/.github/extensions/kast/extension.mjs"
test ! -e "$tmp_dir/.github/extensions/kast/_shared/kast-trace.mjs"
test ! -e "$tmp_dir/.github/extensions/kast/_shared/kast-tools.mjs"
test ! -e "$tmp_dir/.github/.kast-copilot-version"

node --input-type=module - "$tmp_dir/install.json" <<'NODE'
import { readFileSync } from "node:fs";

const payload = JSON.parse(readFileSync(process.argv[2], "utf8"));
if (payload.ok !== false || payload.method !== "plugin/install-local" || payload.error?.code !== "PLUGIN_INSTALL_REMOVED") {
  throw new Error(`unexpected removed Copilot installer envelope: ${JSON.stringify(payload)}`);
}
const replacements = new Set(payload.error?.details?.replacements ?? []);
for (const expected of [
  "copilot --plugin-dir cli-rs/resources/plugin",
  "brew install amichne/kast/kast",
  "kast developer machine plugin",
  "kast agent verify --workspace-root <repo>",
]) {
  if (!replacements.has(expected)) {
    throw new Error(`removed Copilot installer missing replacement: ${expected}`);
  }
}
NODE

printf 'Kast Copilot plugin tests passed\n'
