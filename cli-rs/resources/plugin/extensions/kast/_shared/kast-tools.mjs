import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const COMMAND_CATALOG_PATH = join(HERE, "commands.json");

function loadCommandCatalog() {
  if (!existsSync(COMMAND_CATALOG_PATH)) {
    throw new Error(`Kast command catalog not found at ${COMMAND_CATALOG_PATH}`);
  }
  return JSON.parse(readFileSync(COMMAND_CATALOG_PATH, "utf8"));
}

const COMMAND_CATALOG = loadCommandCatalog();

function orderedCommands(catalog) {
  const seen = new Set();
  const commands = [];
  for (const category of ["symbol", "database", "system", "raw"]) {
    for (const method of catalog.categories?.[category] ?? []) {
      const command = catalog.commands?.[method];
      if (command?.tool && !seen.has(method)) {
        seen.add(method);
        commands.push(command);
      }
    }
  }
  return commands;
}

function requestRequired(request) {
  if (Array.isArray(request?.required)) return request.required;
  return Object.entries(request?.fields ?? {})
    .filter(([, field]) => field?.optional !== true)
    .map(([name]) => name);
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

function mergeCompatibleProperties(target, source) {
  for (const [name, schema] of Object.entries(source ?? {})) {
    if (name !== "type" && !target[name]) target[name] = schema;
  }
}

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

function policyPrefix(command) {
  if (command.method.startsWith("symbol/")) {
    return "Preferred Kotlin funnel tool. Use this before raw file or offset operations when a symbol name, target type, or intended refactor is known.";
  }
  if (command.method.startsWith("database/")) {
    return "Preferred low-cost source-index tool. Use this before backend-wide traversal when index metrics can answer the question.";
  }
  if (command.method.startsWith("raw/workspace-files")) {
    return "Secondary workspace inspection tool. Use only after symbol/query, workspace symbols, or workspace search cannot identify a bounded target.";
  }
  if (command.method.startsWith("raw/")) {
    return "Bounded raw escape hatch. Use only with an exact file, offset, or explicit file list, or after the symbol-first path failed with a concrete blocker.";
  }
  return "Kast system tool.";
}

function variantSummary(command) {
  const variants = command.variants ? Object.entries(command.variants) : [];
  if (variants.length === 0) return "";
  return ` Variant type values: ${variants
    .map(([name, request]) => {
      const required = requestRequired(request).filter((field) => field !== "type");
      return `${name} requires ${required.join(", ") || "no extra fields"}`;
    })
    .join("; ")}.`;
}

function buildToolSpecs(catalog) {
  return orderedCommands(catalog).map((command) => ({
    name: command.tool.name,
    method: command.method,
    description: `${policyPrefix(command)} ${command.tool.description}${variantSummary(command)}`,
    defaultArgs: command.tool.defaultArgs,
    parameters: parametersForCommand(command),
    lowercaseKind: usesLowercaseKind(command),
  }));
}

const TOOL_SPECS = buildToolSpecs(COMMAND_CATALOG);

export const KAST_TOOL_NAMES = Object.freeze(new Set(TOOL_SPECS.map((spec) => spec.name)));

function normalizeArgs(spec, args) {
  const normalized = { ...(args ?? {}) };
  if (spec.lowercaseKind && typeof normalized.kind === "string") {
    normalized.kind = normalized.kind.toLowerCase();
  }
  return normalized;
}

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
