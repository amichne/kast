// Shared kast_* tool definitions for both the Copilot extension and the SDK runner.
//
// Usage:
//   import { makeKastTools, KAST_TOOL_NAMES } from "./_shared/kast-tools.mjs";
//   const tools = makeKastTools((method, params) => callKast(method, params));
//
// callFn(method, params) must return a Promise<string> with the JSON-RPC response.

const ABS_PATH = "Absolute filesystem path.";

const TOOL_SPECS = [
  {
    name: "kast_workspace_files",
    method: "workspace/files",
    description:
      "List Kotlin workspace modules and (optionally) their source files. Use to discover scope before scaffolding or resolving symbols. Far cheaper than recursive directory listings; truncation is reported per-module.",
    parameters: {
      type: "object",
      properties: {
        moduleName: {
          type: "string",
          description: "Optional module name to restrict the listing to one module.",
        },
        includeFiles: {
          type: "boolean",
          description: "If true, return per-module source file lists.",
        },
        maxFilesPerModule: {
          type: "integer",
          description: "Cap per-module file list length. Modules above the cap report filesTruncated:true.",
        },
      },
    },
  },
  {
    name: "kast_workspace_symbol",
    method: "workspace-symbol",
    description:
      "Search the workspace for Kotlin symbols by name pattern. Supports substring matching (default) and regex. Use to find declarations across the codebase — far more precise than grep/rg for symbol names because it understands Kotlin semantics (overloads, inherited members, cross-module references).",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Search pattern to match against symbol names." },
        kind: {
          type: "string",
          description: "Filter to symbols of this kind: CLASS, INTERFACE, OBJECT, FUNCTION, PROPERTY, ENUM_CLASS, ENUM_ENTRY, TYPE_ALIAS.",
        },
        maxResults: { type: "integer", description: "Maximum number of symbols to return. Default 100." },
        regex: { type: "boolean", description: "When true, treats pattern as a regular expression." },
        includeDeclarationScope: {
          type: "boolean",
          description: "When true, includes the declaration body text for each symbol.",
        },
      },
      required: ["pattern"],
    },
  },
  {
    name: "kast_workspace_search",
    method: "workspace/search",
    defaultArgs: { caseSensitive: false },
    description:
      "Search file contents across the workspace for text patterns. Supports substring and regex matching with optional file glob filtering. Use this instead of grep/rg for searching string literals, comments, and arbitrary text in Kotlin source files.",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Search pattern (substring or regex)." },
        regex: { type: "boolean", description: "When true, treats pattern as a regular expression." },
        maxResults: { type: "integer", description: "Maximum number of matches to return. Default 100." },
        fileGlob: { type: "string", description: "Optional glob to restrict search (e.g., '*.kt')." },
        caseSensitive: { type: "boolean", description: "Case-sensitive matching. Default false." },
      },
      required: ["pattern"],
    },
  },
  {
    name: "kast_file_outline",
    method: "file-outline",
    description:
      "Get a hierarchical symbol outline for a Kotlin file. Returns nested declarations (classes, functions, properties) with their signatures and locations. Lighter than scaffold — use when you only need the structural overview without references, type hierarchy, or file content.",
    parameters: {
      type: "object",
      properties: {
        filePath: { type: "string", description: ABS_PATH + " Required." },
      },
      required: ["filePath"],
    },
  },
  {
    name: "kast_scaffold",
    method: "skill/scaffold",
    description:
      "Summarize a Kotlin file/type structure (declarations, signatures, imports, key call sites). Returns the full file content alongside the semantic skeleton — no separate `view` call needed for .kt files. ALWAYS prefer this over `view` for .kt/.kts files.",
    parameters: {
      type: "object",
      properties: {
        targetFile: { type: "string", description: ABS_PATH + " Required. Singular path." },
        targetSymbol: { type: "string", description: "Optional simple symbol name to focus the scaffold within targetFile." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
        mode: {
          type: "string",
          description: "Scaffold mode (e.g. \"implement\", \"summary\"). Omit for default.",
        },
      },
      required: ["targetFile"],
    },
  },
  {
    name: "kast_resolve",
    method: "skill/resolve",
    description:
      "Resolve a Kotlin symbol to its declaration. Use first whenever a name might be overloaded, inherited, or shadowed — disambiguate with kind/containingType/fileHint before tracing references or callers.",
    parameters: {
      type: "object",
      properties: {
        symbol: { type: "string", description: "Simple symbol name." },
        kind: { type: "string", description: "Optional discriminator: class, function, property, etc." },
        containingType: { type: "string", description: "FQ name of the enclosing type for member resolution." },
        fileHint: { type: "string", description: ABS_PATH + " Narrows resolution when the same name lives in multiple files." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["symbol"],
    },
  },
  {
    name: "kast_references",
    method: "skill/references",
    description:
      "Find every usage of a Kotlin symbol. ALWAYS prefer this over `grep` for Kotlin identity — grep cannot disambiguate overloads, inherited members, or imports vs aliases.",
    parameters: {
      type: "object",
      properties: {
        symbol: { type: "string" },
        kind: { type: "string" },
        containingType: { type: "string" },
        fileHint: { type: "string", description: ABS_PATH },
        includeDeclaration: { type: "boolean", description: "Include the declaration site in results." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["symbol"],
    },
  },
  {
    name: "kast_callers",
    method: "skill/callers",
    description:
      "Trace incoming or outgoing call hierarchy for a Kotlin function. Use to understand flow, blast radius, or to find the entry points reaching a target.",
    parameters: {
      type: "object",
      properties: {
        symbol: { type: "string" },
        direction: { type: "string", enum: ["incoming", "outgoing"] },
        depth: { type: "integer", description: "Max levels of recursion." },
        maxTotalCalls: { type: "integer" },
        maxChildrenPerNode: { type: "integer" },
        fileHint: { type: "string", description: ABS_PATH },
        containingType: { type: "string" },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["symbol"],
    },
  },
  {
    name: "kast_metrics",
    method: "skill/metrics",
    description:
      "Query the indexed source metrics: fanIn, fanOut, coupling, lowUsage, cycles, moduleDepth, deadCode, impact. Treat results as advisory if the response indicates the reference index is missing or stale.",
    parameters: {
      type: "object",
      properties: {
        metric: {
          type: "string",
          description: "fanIn | fanOut | coupling | lowUsage | cycles | moduleDepth | deadCode | impact.",
        },
        symbol: { type: "string", description: "FQ name when the metric is symbol-scoped." },
        depth: { type: "integer" },
        limit: { type: "integer" },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["metric"],
    },
  },
  {
    name: "kast_diagnostics",
    method: "diagnostics",
    description:
      "Run Kotlin diagnostics on the listed files. Run after any mutation that did not already validate; treat dirty results as a failed change.",
    parameters: {
      type: "object",
      properties: {
        filePaths: {
          type: "array",
          items: { type: "string", description: ABS_PATH },
          description: "Absolute paths of files to validate.",
        },
      },
      required: ["filePaths"],
    },
  },
  {
    name: "kast_rename",
    method: "skill/rename",
    description:
      "Rename a Kotlin symbol safely (updates every reference). Pass the `type` discriminator (RENAME_BY_SYMBOL_REQUEST or RENAME_BY_OFFSET_REQUEST) plus the request fields. Validation runs automatically — non-clean responses mean the rename did not commit.",
    parameters: {
      type: "object",
      properties: {
        type: {
          type: "string",
          enum: ["RENAME_BY_SYMBOL_REQUEST", "RENAME_BY_OFFSET_REQUEST"],
        },
        symbol: { type: "string" },
        newName: { type: "string" },
        filePath: { type: "string", description: ABS_PATH },
        offset: { type: "integer" },
        containingType: { type: "string" },
        kind: { type: "string" },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["type", "newName"],
      additionalProperties: true,
    },
  },
  {
    name: "kast_write_and_validate",
    method: "skill/write-and-validate",
    description:
      "Apply a Kotlin edit and validate it in one call. Pass the `type` discriminator (CREATE_FILE_REQUEST, INSERT_AT_OFFSET_REQUEST, or REPLACE_RANGE_REQUEST). ALWAYS prefer this over the generic `edit`/`create` tools for .kt/.kts changes — it guards against compile breakage and import drift.",
    parameters: {
      type: "object",
      properties: {
        type: {
          type: "string",
          enum: ["CREATE_FILE_REQUEST", "INSERT_AT_OFFSET_REQUEST", "REPLACE_RANGE_REQUEST"],
        },
        filePath: { type: "string", description: ABS_PATH },
        content: { type: "string" },
        startOffset: { type: "integer" },
        endOffset: { type: "integer" },
        offset: { type: "integer" },
        expectedHash: { type: "string", description: "Optional sha256 of the file before edit; protects against concurrent change." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["type", "filePath"],
      additionalProperties: true,
    },
  },
];

/** Immutable set of all kast_* tool names exposed via RPC. */
export const KAST_TOOL_NAMES = Object.freeze(new Set(TOOL_SPECS.map((s) => s.name)));

const LOWERCASE_KIND_METHODS = new Set([
  "skill/resolve",
  "skill/references",
  "skill/callers",
  "skill/rename",
]);

function normalizeArgs(method, args) {
  const normalized = { ...(args ?? {}) };
  if (LOWERCASE_KIND_METHODS.has(method) && typeof normalized.kind === "string") {
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
    handler: (args) =>
      spec.defaultArgs
        ? callFn(spec.method, { ...spec.defaultArgs, ...normalizeArgs(spec.method, args) })
        : callFn(spec.method, normalizeArgs(spec.method, args)),
  }));
}
