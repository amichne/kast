#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"

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

console.log("LSP pivot static gates passed");
NODE

printf 'LSP pivot gates passed\n'
