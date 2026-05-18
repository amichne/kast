#!/usr/bin/env node
// Asserts the shared kast-tools module exports the correct tool name set.
import assert from "node:assert/strict";
import { makeKastTools, KAST_TOOL_NAMES } from "../../../../.github/extensions/_shared/kast-tools.mjs";

const EXPECTED_NAMES = [
  "kast_workspace_files",
  "kast_workspace_symbol",
  "kast_workspace_search",
  "kast_file_outline",
  "kast_scaffold",
  "kast_resolve",
  "kast_references",
  "kast_callers",
  "kast_metrics",
  "kast_diagnostics",
  "kast_rename",
  "kast_write_and_validate",
];

// KAST_TOOL_NAMES export
assert.deepStrictEqual(
  [...KAST_TOOL_NAMES].sort(),
  [...EXPECTED_NAMES].sort(),
  "KAST_TOOL_NAMES must match expected set",
);

// makeKastTools factory
assert.equal(typeof makeKastTools, "function", "makeKastTools must be a function");

const noop = () => Promise.resolve("{}");
const tools = makeKastTools(noop);

assert.equal(tools.length, EXPECTED_NAMES.length, "makeKastTools must return one tool per name");

const actualNames = tools.map((t) => t.name).sort();
assert.deepStrictEqual(actualNames, [...EXPECTED_NAMES].sort(), "tool names must match");

for (const tool of tools) {
  assert.equal(typeof tool.name, "string", "tool.name must be string");
  assert.equal(typeof tool.description, "string", "tool.description must be string");
  assert.ok(tool.description.length > 0, `tool ${tool.name} must have non-empty description`);
  assert.ok(tool.parameters && typeof tool.parameters === "object", `tool ${tool.name} must have parameters`);
  assert.equal(typeof tool.handler, "function", `tool ${tool.name} must have handler function`);
}

// Handler delegates to callFn
let capturedMethod = null;
let capturedParams = null;
const capturingTools = makeKastTools((method, params) => {
  capturedMethod = method;
  capturedParams = params;
  return Promise.resolve("{}");
});
const filesToolByCall = capturingTools.find((t) => t.name === "kast_workspace_files");
await filesToolByCall.handler({ includeFiles: true });
assert.equal(capturedMethod, "workspace/files", "kast_workspace_files handler must call workspace/files");
assert.deepStrictEqual(capturedParams, { includeFiles: true });

const resolveToolByCall = capturingTools.find((t) => t.name === "kast_resolve");
await resolveToolByCall.handler({
  symbol: "filePath",
  kind: "PROPERTY",
  containingType: "io.github.amichne.kast.api.contract.FileOperation",
});
assert.equal(capturedMethod, "skill/resolve", "kast_resolve handler must call skill/resolve");
assert.deepStrictEqual(
  capturedParams,
  {
    symbol: "filePath",
    kind: "property",
    containingType: "io.github.amichne.kast.api.contract.FileOperation",
  },
  "skill wrappers must normalize uppercase kind values for the RPC contract",
);

console.log("All kast-tools tests passed.");
