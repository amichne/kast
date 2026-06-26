function collectNamedSchemas(schema, name, out = []) {
  if (!schema || typeof schema !== "object") return out;
  if (schema.properties?.[name]) out.push(schema.properties[name]);
  for (const value of Object.values(schema.properties ?? {})) collectNamedSchemas(value, name, out);
  if (schema.items && typeof schema.items === "object") collectNamedSchemas(schema.items, name, out);
  for (const key of ["oneOf", "anyOf", "allOf"]) {
    for (const candidate of schema[key] ?? []) collectNamedSchemas(candidate, name, out);
  }
  return out;
}

function usesLowercaseKindFromParameters(parameters) {
  return collectNamedSchemas(parameters, "kind").some((field) =>
    Array.isArray(field.enum) && field.enum.some((value) => typeof value === "string" && value === value.toLowerCase()),
  );
}

function normalizeToolSpec(spec) {
  if (!spec || typeof spec !== "object") {
    throw new Error("Kast tool spec must be an object");
  }
  for (const field of ["name", "method", "description"]) {
    if (typeof spec[field] !== "string" || spec[field].trim() === "") {
      throw new Error(`Kast tool spec is missing string field ${field}`);
    }
  }
  return {
    name: spec.name,
    method: spec.method,
    description: spec.description,
    defaultArgs: spec.defaultArgs,
    parameters: spec.parameters && typeof spec.parameters === "object"
      ? spec.parameters
      : { type: "object", additionalProperties: true },
    lowercaseKind: spec.lowercaseKind ?? usesLowercaseKindFromParameters(spec.parameters),
  };
}

export function toolSpecsFromAgentToolsResult(value) {
  const result = value?.result ?? value;
  if (result?.type !== "KAST_AGENT_TOOLS") {
    throw new Error("Kast agent tools result must have type KAST_AGENT_TOOLS");
  }
  if (!Array.isArray(result.tools)) {
    throw new Error("Kast agent tools result must include a tools array");
  }
  return result.tools.map(normalizeToolSpec);
}

function isKastAgentToolInvocation(value) {
  const argv = value?.argv;
  return typeof value?.command === "string" &&
    value.command.trim() !== "" &&
    Array.isArray(argv) &&
    argv.length === 4 &&
    typeof argv[0] === "string" &&
    argv[0].trim() !== "" &&
    argv[1] === "agent" &&
    argv[2] === "call" &&
    argv[3] === "<method>" &&
    value.methodArgument === "<method>" &&
    value.paramsFileFlag === "--params-file" &&
    value.workspaceRootFlag === "--workspace-root";
}

export function isKastAgentToolsEnvelope(value) {
  return value?.ok === true &&
    value?.method === "agent/tools" &&
    value?.result?.type === "KAST_AGENT_TOOLS" &&
    isKastAgentToolInvocation(value.result.invocation) &&
    Array.isArray(value.result.tools);
}

function normalizeArgs(spec, args) {
  const normalized = { ...(args ?? {}) };
  if (spec.lowercaseKind && typeof normalized.kind === "string") {
    normalized.kind = normalized.kind.toLowerCase();
  }
  return normalized;
}

export function makeKastTools(toolSpecs, callFn) {
  return toolSpecs.map((spec) => normalizeToolSpec(spec)).map((spec) => ({
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
