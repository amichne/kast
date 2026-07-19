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
assert(extension.includes("createTraceEmitter"), "extension must wire structured tracing");
for (const hook of [
  "onSessionStart",
  "onPreToolUse",
  "onPostToolUse",
  "onPostToolUseFailure",
  "onSessionEnd",
]) {
  assert(extension.includes(hook), `extension must register ${hook}`);
}
assert(!extension.includes("onUserPromptSubmitted"), "static prompt tutorials must be retired");
for (const operation of ['runLifecycle("begin"', 'runLifecycle("status"', 'runLifecycle("finish"']) {
  assert(extension.includes(operation), `extension must route lifecycle operation ${operation}`);
}
assert(extension.includes("KAST_AGENT_TASK_LAUNCHER"), "extension must accept an absolute launcher override");
assert(extension.includes("entrypoints?.taskLauncher"), "extension must consume the attested install entrypoint");
assert(extension.includes('join(homedir(), ".local", "bin", "kast-agent-task")'), "extension must use the stable launcher path");
assert(extension.includes("isExecutable(candidate)"), "extension must require an executable launcher");
assert(extension.includes('join(dirname(candidate), "kast")'), "extension must require the launcher's sibling kast");
assert(extension.includes("KAST_AGENT_SESSION_ID: sessionId"), "extension must bind task ownership to the Copilot session");
assert(extension.includes("invocation?.sessionId"), "extension must use the SDK hook invocation identity");
assert(extension.includes("permissionDecision: \"deny\""), "pre-tool status failure must remain a guardrail");
assert(extension.includes("kast extension audit:"), "session end must record its non-blocking finish audit");
for (const forbidden of [
  "findOnPath",
  "process.env.PATH",
  "target/debug",
  "target/release",
  "KAST_TOOLING_CONTEXT",
  "RECOVERABLE_WARMUP_CODES",
  "kast agent symbol",
  "kast agent diagnostics",
  "--output json",
  "--workspace-root",
]) {
  assert(!extension.includes(forbidden), `extension contains forbidden fallback or tutorial ${forbidden}`);
}
assert(extension.includes("tools: []"), "extension must not register dynamic Copilot tools");
assert(
  !extension.includes("customAgents") && !extension.includes("makeKastCustomAgents"),
  "extension must register tools without custom agents",
);
NODE

node --input-type=module - "$plugin_root" "$tmp_dir" <<'NODE'
import {
  chmodSync,
  copyFileSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const pluginRoot = process.argv[2];
const testRoot = resolve(process.argv[3], "copilot callback proof");
const extensionRoot = join(testRoot, "extension");
const workspaceRoot = join(testRoot, "workspace");
const pairRoot = join(testRoot, "attested pair");
const sdkRoot = join(extensionRoot, "node_modules/@github/copilot-sdk");
const copiedExtension = join(extensionRoot, "extensions/kast/extension.mjs");
const copiedTrace = join(extensionRoot, "extensions/kast/_shared/kast-trace.mjs");
const taskLauncher = join(pairRoot, "kast-agent-task");
const siblingKast = join(pairRoot, "kast");
const callsPath = join(testRoot, "calls.tsv");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

mkdirSync(join(extensionRoot, "extensions/kast/_shared"), { recursive: true });
mkdirSync(workspaceRoot, { recursive: true });
const canonicalWorkspaceRoot = realpathSync(workspaceRoot);
mkdirSync(pairRoot, { recursive: true });
mkdirSync(sdkRoot, { recursive: true });
copyFileSync(join(pluginRoot, "extensions/kast/extension.mjs"), copiedExtension);
copyFileSync(join(pluginRoot, "extensions/kast/_shared/kast-trace.mjs"), copiedTrace);
writeFileSync(
  join(sdkRoot, "package.json"),
  JSON.stringify({
    name: "@github/copilot-sdk",
    type: "module",
    exports: { "./extension": "./extension.mjs" },
  }),
);
writeFileSync(
  join(sdkRoot, "extension.mjs"),
  [
    "export const testState = { options: null, logs: [] };",
    "export async function joinSession(options) {",
    "  testState.options = options;",
    "  return {",
    "    async log(message, options = {}) {",
    "      testState.logs.push({ message, options });",
    "    },",
    "  };",
    "}",
  ].join("\n"),
);
writeFileSync(
  taskLauncher,
  [
    "#!/bin/sh",
    "set -eu",
    "printf '%s\\t%s\\t%s\\n' \"${KAST_AGENT_SESSION_ID:-}\" \"$1\" \"$PWD\" >> \"$KAST_TEST_CALLS\"",
    "if [ \"${KAST_TEST_FAIL_OPERATION:-}\" = \"$1\" ]; then",
    "  printf 'typed-%s-blocker\\n' \"$1\" >&2",
    "  exit 42",
    "fi",
    "printf 'operation: %s\\nstate: ACTIVE\\n' \"$1\"",
  ].join("\n"),
);
writeFileSync(siblingKast, "#!/bin/sh\nexit 0\n");
chmodSync(taskLauncher, 0o755);
chmodSync(siblingKast, 0o755);

process.env.KAST_AGENT_TASK_LAUNCHER = taskLauncher;
process.env.KAST_EXTENSION_REPO_ROOT = workspaceRoot;
process.env.KAST_TEST_CALLS = callsPath;

const sdk = await import(pathToFileURL(join(sdkRoot, "extension.mjs")));
await import(pathToFileURL(copiedExtension));
const hooks = sdk.testState.options?.hooks;
assert(hooks, "extension did not register hooks");
assert(
  JSON.stringify(Object.keys(hooks).sort()) === JSON.stringify([
    "onPostToolUse",
    "onPostToolUseFailure",
    "onPreToolUse",
    "onSessionEnd",
    "onSessionStart",
  ]),
  `unexpected hook set: ${Object.keys(hooks)}`,
);
assert(sdk.testState.options.tools.length === 0, "extension must not register tools");
assert(
  JSON.stringify(sdk.testState.options.disabledSkills) === JSON.stringify(["kast"]),
  "extension must disable the copied Kast skill",
);
sdk.testState.logs.length = 0;

const started = await hooks.onSessionStart(
  { source: "startup" },
  { sessionId: "session-start" },
);
assert(started.additionalContext.includes("operation: begin"), `begin context: ${JSON.stringify(started)}`);

const allowed = await hooks.onPreToolUse(
  { toolName: "read" },
  { sessionId: "pre-success" },
);
assert(allowed.additionalContext.includes("operation: status"), `pre context: ${JSON.stringify(allowed)}`);
assert(!("permissionDecision" in allowed), `successful pre hook denied: ${JSON.stringify(allowed)}`);

const postSuccess = await hooks.onPostToolUse(
  { toolName: "edit" },
  { sessionId: "post-success" },
);
assert(postSuccess.additionalContext.includes("operation: status"), `post context: ${JSON.stringify(postSuccess)}`);

const postFailure = await hooks.onPostToolUseFailure(
  { toolName: "edit" },
  { sessionId: "post-failure" },
);
assert(postFailure.additionalContext.includes("operation: status"), `failed post context: ${JSON.stringify(postFailure)}`);

await hooks.onSessionEnd({ reason: "complete" }, { sessionId: "end-success" });
let audit = sdk.testState.logs.at(-1);
assert(audit.message.includes("kast extension audit: operation: finish"), `success audit: ${JSON.stringify(audit)}`);
assert(audit.options.level === "info" && audit.options.ephemeral === true, `success audit options: ${JSON.stringify(audit)}`);

process.env.KAST_TEST_FAIL_OPERATION = "status";
const denied = await hooks.onPreToolUse(
  { toolName: "edit" },
  { sessionId: "pre-failure" },
);
assert(denied.permissionDecision === "deny", `failed status was not denied: ${JSON.stringify(denied)}`);
assert(denied.permissionDecisionReason.includes("typed-status-blocker"), `denial evidence: ${JSON.stringify(denied)}`);

process.env.KAST_TEST_FAIL_OPERATION = "finish";
await hooks.onSessionEnd({ reason: "blocked" }, { sessionId: "end-failure" });
audit = sdk.testState.logs.at(-1);
assert(audit.message.includes("Kast agent task finish failed"), `failure audit: ${JSON.stringify(audit)}`);
assert(audit.message.includes("typed-finish-blocker"), `failure evidence: ${JSON.stringify(audit)}`);
assert(audit.options.level === "warning" && audit.options.ephemeral === false, `failure audit options: ${JSON.stringify(audit)}`);
delete process.env.KAST_TEST_FAIL_OPERATION;

const calls = readFileSync(callsPath, "utf8")
  .trim()
  .split("\n")
  .map((line) => line.split("\t"));
const expectedCalls = [
  ["session-start", "begin"],
  ["pre-success", "status"],
  ["post-success", "status"],
  ["post-failure", "status"],
  ["end-success", "finish"],
  ["pre-failure", "status"],
  ["end-failure", "finish"],
];
assert(calls.length === expectedCalls.length, `unexpected calls: ${JSON.stringify(calls)}`);
for (const [index, [sessionId, operation]] of expectedCalls.entries()) {
  assert(
    JSON.stringify(calls[index]) === JSON.stringify([
      sessionId,
      operation,
      canonicalWorkspaceRoot,
    ]),
    `call ${index} did not preserve session identity and argv: ${JSON.stringify(calls[index])}`,
  );
}
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

printf 'Kast Copilot plugin tests passed\n'
