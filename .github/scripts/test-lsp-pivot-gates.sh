#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-lsp-gates.XXXXXX")"
trap 'rm -rf -- "$tmp_dir"' EXIT

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

node --input-type=module - "$repo_root" <<'NODE'
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];

function fail(message) {
  throw new Error(message);
}

function readText(path) {
  return readFileSync(join(root, path), "utf8");
}

function readJson(path) {
  return JSON.parse(readText(path));
}

function requireText(path, needles) {
  const text = readText(path);
  for (const [label, needle] of Object.entries(needles)) {
    if (!text.includes(needle)) fail(`${path} missing ${label}: ${needle}`);
  }
  return text;
}

function requireCombinedText(label, paths, needles) {
  const text = paths.map((path) => readText(path)).join("\n");
  for (const [needleLabel, needle] of Object.entries(needles)) {
    if (!text.includes(needle)) fail(`${label} missing ${needleLabel}: ${needle}`);
  }
  return text;
}

function assertSameJson(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} mismatch`);
  }
}

const pluginLsp = readJson("cli-rs/resources/plugin/lsp.json");
const pluginServer = pluginLsp.lspServers["kotlin"];
if (pluginServer.command !== "kast" || JSON.stringify(pluginServer.args) !== JSON.stringify(["agent", "lsp", "--stdio"])) {
  fail("kotlin must launch kast agent lsp --stdio");
}
if (pluginServer.initializationOptions.failOnStaleIndex !== true) {
  fail("kotlin must fail closed on stale indexes");
}

const manifest = readJson("cli-rs/resources/plugin/plugin.json");
assertSameJson(
  manifest.entrypoints,
  {
    lsp: "lsp.json",
    manifest: "primitive-manifest.json",
  },
  "plugin manifest entrypoints",
);

const lspSourcePaths = [
  "cli-rs/src/lsp.rs",
  "cli-rs/src/lsp/capabilities_and_routes.rs",
  "cli-rs/src/lsp/conversions.rs",
  "cli-rs/src/lsp/entrypoint_and_client.rs",
  "cli-rs/src/lsp/protocol.rs",
  "cli-rs/src/lsp/route_model.rs",
  "cli-rs/src/lsp/server.rs",
  "cli-rs/src/lsp/symbol_mapping.rs",
  "cli-rs/src/lsp/tests.rs",
  "cli-rs/src/lsp/tests/failure_modes.rs",
  "cli-rs/src/lsp/tests/hierarchy.rs",
  "cli-rs/src/lsp/tests/initialize_and_routes.rs",
  "cli-rs/src/lsp/tests/protocol.rs",
  "cli-rs/src/lsp/tests/read_operations.rs",
  "cli-rs/src/lsp/tests/rename.rs",
  "cli-rs/src/lsp/tests/support.rs",
];
const lsp = requireCombinedText("cli-rs/src/lsp split sources", lspSourcePaths, {
  "bounded result cap": "const MAX_LSP_RESULTS",
  "bounded result application": ".take(MAX_LSP_RESULTS)",
  "generated custom route include": "lsp_custom_routes.rs",
  "custom route lookup": "custom_lsp_route",
  "custom route table": "KAST_CUSTOM_LSP_ROUTES",
  "read methods": '"textDocument/references"',
  "prepare rename method": '"textDocument/prepareRename"',
  "rename method": '"textDocument/rename"',
  "rename capability gate": "mutationCapabilities",
  "partial reference rejection": "LSP_RENAME_PARTIAL_REFERENCE_SET",
  "generated rename rejection": "rename edit would modify generated or build output",
  "initialization options parser": "initializationOptions.failOnStaleIndex",
  "stale index failure": "LSP_STALE_INDEX",
  "runtime status check": '"runtime/status"',
  "backend error data propagation": "backendCode",
  "ambiguous backend test": "AMBIGUOUS_ANCHOR",
  "runtime timeout backend test": "RUNTIME_TIMEOUT",
});
if (lsp.includes("contents") && !lsp.includes("document_symbols_map_nested_outline_without_file_contents")) {
  fail("document symbol tests must prove outlines do not expose file contents");
}
if (lsp.includes('"kast/symbolResolve" =>') || lsp.includes('"kast/databaseMetrics" =>')) {
  fail("custom kast/* dispatch must be generated from the RPC catalog, not hand-written match arms");
}

const buildRs = requireText("cli-rs/build.rs", {
  "LSP route generator": "lsp_custom_routes.rs",
  "RPC catalog input": "protocol/source/commands.json",
});
if (buildRs.includes("symbol/resolve") || buildRs.includes("database/metrics")) {
  fail("LSP route generation must read method names from the catalog instead of hard-coding routes");
}

requireText("cli-rs/src/rpc.rs", {
  "backend error code preservation": '"backendCode"',
  "backend code test": "preserves_backend_error_code",
});

const skillShadowing = readJson(".github/skill-shadowing.json");
const skillIds = new Set(skillShadowing.skills.map((entry) => entry.id));
if (skillIds.size !== 1 || !skillIds.has("kast")) {
  fail(".github/skill-shadowing.json must route only the repo-local kast skill");
}

console.log("LSP pivot static gates passed");
NODE

"${repo_root}/.github/scripts/test-kast-copilot-plugin.sh" >/dev/null

if [[ -z "${KAST_LSP_TEST_COMMAND:-}" ]]; then
  if [[ -x "${repo_root}/cli-rs/target/debug/kast" ]]; then
    export KAST_LSP_TEST_COMMAND="${repo_root}/cli-rs/target/debug/kast"
  elif [[ -x "${repo_root}/cli-rs/target/release/kast" ]]; then
    export KAST_LSP_TEST_COMMAND="${repo_root}/cli-rs/target/release/kast"
  else
    die "KAST_LSP_TEST_COMMAND is required when no local kast binary has been built"
  fi
fi
export KAST_LSP_REQUEST_TIMEOUT_MS="${KAST_LSP_REQUEST_TIMEOUT_MS:-1000}"
node "${repo_root}/.github/scripts/test-lsp-config.mjs" >"${tmp_dir}/lsp-smoke.json"

node --input-type=module - "${tmp_dir}/lsp-smoke.json" <<'NODE'
import { readFileSync } from "node:fs";

const payload = JSON.parse(readFileSync(process.argv[2], "utf8"));
const allowedFailClosed = new Set([
  "DAEMON_START_ERROR",
  "HEADLESS_BACKEND_NOT_INSTALLED",
  "IDEA_NOT_RUNNING",
  "MACOS_HOMEBREW_RECEIPT_INVALID",
  "MACOS_PLUGIN_WORKSPACE_REQUIRED",
  "NO_BACKEND_AVAILABLE",
  "RUNTIME_TIMEOUT",
]);
const code = payload.initializeErrorCode ?? null;
if (code !== null && !allowedFailClosed.has(code)) {
  throw new Error(`unexpected LSP initialize failure code: ${code}`);
}
console.log(
  JSON.stringify({
    command: payload.command ?? null,
    initializeErrorCode: code,
    ok: true,
  }),
);
NODE

printf 'LSP pivot gates passed\n'
