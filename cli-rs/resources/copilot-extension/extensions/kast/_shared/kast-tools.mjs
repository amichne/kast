// Shared kast_* tool definitions for both the Copilot extension and the SDK runner.
//
// Usage:
//   import { makeKastTools, KAST_TOOL_NAMES } from "./_shared/kast-tools.mjs";
//   const tools = makeKastTools((method, params) => callKast(method, params));
//
// callFn(method, params) must return a Promise<string> with the JSON-RPC response.

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

const COMMAND_CATALOG_CANDIDATES = [
  // Installed extension path. The Rust installer writes this from the canonical
  // packaged skill catalog so installed tools do not carry a second catalog.
  join(HERE, "commands.json"),
  // Repository development path.
  resolve(HERE, "..", "..", "..", "..", "kast-skill", "references", "commands.json"),
];

function loadCommandCatalog() {
  for (const candidate of COMMAND_CATALOG_CANDIDATES) {
    if (!existsSync(candidate)) continue;
    return JSON.parse(readFileSync(candidate, "utf8"));
  }
  throw new Error(
    `Kast JSON-RPC command catalog not found. Checked: ${COMMAND_CATALOG_CANDIDATES.join(", ")}`,
  );
}

const COMMAND_CATALOG = loadCommandCatalog();

function orderedCommands(catalog) {
  const seen = new Set();
  const commands = [];
  for (const methods of Object.values(catalog.categories ?? {})) {
    for (const method of methods) {
      const command = catalog.commands?.[method];
      if (command && !seen.has(method)) {
        seen.add(method);
        commands.push(command);
      }
    }
  }
  for (const [method, command] of Object.entries(catalog.commands ?? {})) {
    if (!seen.has(method)) commands.push(command);
  }
  return commands;
}

function itemSchema(items) {
  if (!items) return { type: "object", additionalProperties: true };
  if (typeof items === "string") {
    return items === "object"
      ? { type: "object", additionalProperties: true }
      : { type: items };
  }
  return fieldSchema(items);
}

function requestRequired(request) {
  if (Array.isArray(request?.required)) return request.required;
  return Object.entries(request?.fields ?? {})
    .filter(([, field]) => field?.optional !== true)
    .map(([name]) => name);
}

function fieldsToProperties(fields) {
  return Object.fromEntries(
    Object.entries(fields ?? {}).map(([name, field]) => [name, fieldSchema(field)]),
  );
}

function fieldSchema(field) {
  const schema = {};
  switch (field?.type) {
    case "array":
      schema.type = "array";
      schema.items = itemSchema(field.items);
      break;
    case "object":
      schema.type = "object";
      if (field.fields) {
        schema.properties = fieldsToProperties(field.fields);
        const required = requestRequired(field);
        if (required.length > 0) schema.required = required;
        schema.additionalProperties = false;
      } else {
        schema.additionalProperties = true;
      }
      break;
    case "boolean":
    case "integer":
    case "string":
      schema.type = field.type;
      break;
    default:
      schema.type = "object";
      schema.additionalProperties = true;
      break;
  }
  if (Array.isArray(field?.enum)) {
    schema.enum = field.enum.filter((value) => value !== null);
  }
  return schema;
}

function requestSchema(request) {
  const schema = {
    type: "object",
    properties: fieldsToProperties(request?.fields ?? {}),
    additionalProperties: false,
  };
  const required = requestRequired(request);
  if (required.length > 0) schema.required = required;
  return schema;
}

function variantRequirementSummary(variantName, request) {
  const required = requestRequired(request)
    .filter((name) => name !== "type")
    .join(", ");
  return `${variantName}: ${required || "no additional required fields"}`;
}

function variantDescription(command) {
  const variants = command.variants ? Object.entries(command.variants) : [];
  if (variants.length === 0) return command.tool.description;
  const summary = variants
    .map(([variantName, request]) => variantRequirementSummary(variantName, request))
    .join("; ");
  return `${command.tool.description} Set type to one of: ${summary}.`;
}

function mergeCompatibleProperties(target, source) {
  for (const [name, schema] of Object.entries(source ?? {})) {
    if (name === "type") continue;
    if (!target[name]) {
      target[name] = schema;
    }
  }
}

// Keep the model-facing schema in the common provider subset. The Kast RPC
// layer still validates the precise discriminated request after the tool call.
function compatibleVariantParameters(variants) {
  const properties = {};
  const variantNames = [];
  for (const [variantName, request] of variants) {
    variantNames.push(variantName);
    mergeCompatibleProperties(properties, requestSchema(request).properties);
  }
  return {
    type: "object",
    properties: {
      type: { type: "string", enum: variantNames },
      ...properties,
    },
    additionalProperties: false,
    required: ["type"],
  };
}

function parametersForCommand(command) {
  const variants = command.variants ? Object.entries(command.variants) : [];
  if (variants.length === 0) return requestSchema(command.request);
  return compatibleVariantParameters(variants);
}

function collectNamedFields(request, name, out = []) {
  for (const [fieldName, field] of Object.entries(request?.fields ?? {})) {
    if (fieldName === name) out.push(field);
    if (field?.fields) collectNamedFields(field, name, out);
    if (field?.items && typeof field.items === "object") {
      collectNamedFields({ fields: { item: field.items } }, name, out);
    }
  }
  return out;
}

function usesLowercaseKind(command) {
  const kindFields = collectNamedFields(command.request, "kind");
  for (const variant of Object.values(command.variants ?? {})) {
    collectNamedFields(variant, "kind", kindFields);
  }
  return kindFields.some((field) =>
    Array.isArray(field.enum) && field.enum.some((value) => value === value.toLowerCase()),
  );
}

function buildToolSpecs(catalog) {
  return orderedCommands(catalog)
    .filter((command) => command.tool)
    .map((command) => ({
      name: command.tool.name,
      method: command.method,
      description: variantDescription(command),
      defaultArgs: command.tool.defaultArgs,
      parameters: parametersForCommand(command),
      lowercaseKind: usesLowercaseKind(command),
    }));
}

const TOOL_SPECS = buildToolSpecs(COMMAND_CATALOG);

/** Immutable set of all kast_* tool names exposed via RPC. */
export const KAST_TOOL_NAMES = Object.freeze(new Set(TOOL_SPECS.map((s) => s.name)));

function normalizeArgs(spec, args) {
  const normalized = { ...(args ?? {}) };
  if (spec.lowercaseKind && typeof normalized.kind === "string") {
    normalized.kind = normalized.kind.toLowerCase();
  }
  return normalized;
}

/**
 * Build a kast_* tools array.
 *
 * @param {function(method: string, params: object): Promise<string>} callFn
 *   Called for every tool invocation. Must return the raw JSON-RPC response string.
 * @returns {Array<{name: string, description: string, parameters: object, handler: function}>}
 */
export function makeKastTools(callFn) {
  return TOOL_SPECS.map((spec) => ({
    name: spec.name,
    description: spec.description,
    parameters: spec.parameters,
    handler: (args) => {
      const normalized = normalizeArgs(spec, args);
      const params = spec.defaultArgs ? { ...spec.defaultArgs, ...normalized } : normalized;
      return callFn(spec.method, params);
    },
  }));
}
