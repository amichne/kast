// Kast extension for Copilot CLI.
//
// Goals (see .agents/skills/kast/SKILL.md and AGENTS.md):
//   1. Eliminate the KAST_CLI_PATH bootstrap round-trip — resolve once at
//      session start, cache, and use that path for every kast_* tool call.
//   2. Expose `kast skill` commands as first-class native tools so the agent
//      sees them in its tool list (discoverability) and the CLI runtime
//      validates arguments against the schema (no JSON-in-bash brittleness).
//   3. Soft-warn when the agent reaches for generic view/grep/edit/create on
//      Kotlin source — the kast equivalent is almost always cheaper in tokens
//      and produces structured results. Soft (not deny) so genuinely
//      non-semantic work (comments, formatting, generated files) still flows.

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "..", "..", "..");
const RESOLVE_SCRIPT = join(
  REPO_ROOT,
  ".agents",
  "skills",
  "kast",
  "scripts",
  "resolve-kast.sh",
);

let kastBinary = null;
let resolveError = null;

function execBash(command, env = process.env) {
  return new Promise((res) => {
    execFile(
      "bash",
      ["-lc", command],
      { env, maxBuffer: 32 * 1024 * 1024 },
      (err, stdout, stderr) => {
        res({
          ok: !err,
          code: err?.code ?? 0,
          stdout: String(stdout ?? ""),
          stderr: String(stderr ?? ""),
        });
      },
    );
  });
}

async function resolveKastBinary() {
  if (kastBinary) return kastBinary;
  if (!existsSync(RESOLVE_SCRIPT)) {
    resolveError = `resolver script missing: ${RESOLVE_SCRIPT}`;
    return null;
  }
  const { ok, stdout, stderr } = await execBash(`bash ${JSON.stringify(RESOLVE_SCRIPT)}`);
  const path = stdout.trim();
  if (!ok || !path) {
    resolveError = stderr.trim() || "resolve-kast.sh produced no output";
    return null;
  }
  kastBinary = path;
  return path;
}

async function callKastSkill(command, args) {
  const bin = await resolveKastBinary();
  if (!bin) {
    return JSON.stringify({
      ok: false,
      stage: "extension.resolve",
      message: `kast binary not resolved: ${resolveError ?? "unknown"}`,
    });
  }
  const json = JSON.stringify(args ?? {});
  const env = { ...process.env, KAST_CLI_PATH: bin };
  const cmd = `${JSON.stringify(bin)} skill ${command} ${JSON.stringify(json)}`;
  const { ok, stdout, stderr, code } = await execBash(cmd, env);
  // kast prints JSON to stdout; surface any stderr if the JSON parse would fail.
  const out = stdout.trim();
  if (!out) {
    return JSON.stringify({
      ok: false,
      stage: "extension.exec",
      message: `kast skill ${command} produced no output (exit ${code})`,
      errorText: stderr.trim() || null,
    });
  }
  try {
    JSON.parse(out);
    return out;
  } catch {
    return JSON.stringify({
      ok: false,
      stage: "extension.parse",
      message: `kast skill ${command} returned non-JSON (exit ${code})`,
      raw: out,
      errorText: stderr.trim() || null,
    });
  }
}

// ---------------------------------------------------------------------------
// Tool definitions — one per `kast skill` command.
// Schemas mirror references/quickstart.md; required fields enforce contract.

const ABS_PATH = "Absolute filesystem path.";

const tools = [
  {
    name: "kast_workspace_files",
    description:
      "List Kotlin workspace modules and (optionally) their source files via kast skill workspace-files. Use to discover scope before scaffolding or resolving symbols. Far cheaper than recursive directory listings; truncation is reported per-module.",
    parameters: {
      type: "object",
      properties: {
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
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
    handler: (args) => callKastSkill("workspace-files", args),
  },
  {
    name: "kast_scaffold",
    description:
      "Summarize a Kotlin file/type structure (declarations, signatures, imports, key call sites) via kast skill scaffold. ALWAYS prefer this over reading a .kt file with `view` — scaffold returns a semantic skeleton at a fraction of the token cost.",
    parameters: {
      type: "object",
      properties: {
        targetFile: { type: "string", description: ABS_PATH + " Required. Singular path." },
        targetSymbol: { type: "string", description: "Optional FQ name or simple name to focus the scaffold." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
        mode: {
          type: "string",
          description: "Scaffold mode (e.g. \"implement\", \"summary\"). Omit for default.",
        },
      },
      required: ["targetFile"],
    },
    handler: (args) => callKastSkill("scaffold", args),
  },
  {
    name: "kast_resolve",
    description:
      "Resolve a Kotlin symbol to its declaration via kast skill resolve. Use first whenever a name might be overloaded, inherited, or shadowed — disambiguate with kind/containingType/fileHint before tracing references or callers.",
    parameters: {
      type: "object",
      properties: {
        symbol: { type: "string", description: "Simple name or FQ name." },
        kind: { type: "string", description: "Optional discriminator: class, function, property, etc." },
        containingType: { type: "string", description: "FQ name of the enclosing type for member resolution." },
        fileHint: { type: "string", description: ABS_PATH + " Narrows resolution when the same name lives in multiple files." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["symbol"],
    },
    handler: (args) => callKastSkill("resolve", args),
  },
  {
    name: "kast_references",
    description:
      "Find every usage of a Kotlin symbol via kast skill references. ALWAYS prefer this over `grep` for Kotlin identity — grep cannot disambiguate overloads, inherited members, or imports vs aliases.",
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
    handler: (args) => callKastSkill("references", args),
  },
  {
    name: "kast_callers",
    description:
      "Trace incoming or outgoing call hierarchy for a Kotlin function via kast skill callers. Use to understand flow, blast radius, or to find the entry points reaching a target.",
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
    handler: (args) => callKastSkill("callers", args),
  },
  {
    name: "kast_metrics",
    description:
      "Query the indexed source metrics via kast skill metrics: fan-in, fan-out, coupling, dead-code, impact. Treat results as advisory if the response indicates the reference index is missing or stale.",
    parameters: {
      type: "object",
      properties: {
        metric: {
          type: "string",
          description: "fan-in | fan-out | coupling | dead-code | impact (or other supported metric).",
        },
        symbol: { type: "string", description: "FQ name when the metric is symbol-scoped." },
        depth: { type: "integer" },
        limit: { type: "integer" },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["metric"],
    },
    handler: (args) => callKastSkill("metrics", args),
  },
  {
    name: "kast_diagnostics",
    description:
      "Run Kotlin diagnostics on the listed files via kast skill diagnostics. Run after any mutation that did not already validate; treat dirty results as a failed change.",
    parameters: {
      type: "object",
      properties: {
        filePaths: {
          type: "array",
          items: { type: "string", description: ABS_PATH },
          description: "Absolute paths of files to validate.",
        },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["filePaths"],
    },
    handler: (args) => callKastSkill("diagnostics", args),
  },
  {
    name: "kast_rename",
    description:
      "Rename a Kotlin symbol safely (updates every reference) via kast skill rename. Pass the `type` discriminator (RENAME_BY_SYMBOL_REQUEST or RENAME_BY_OFFSET_REQUEST) plus the request fields. Validation runs automatically — non-clean responses mean the rename did not commit.",
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
    handler: (args) => callKastSkill("rename", args),
  },
  {
    name: "kast_write_and_validate",
    description:
      "Apply a Kotlin edit and validate it in one call via kast skill write-and-validate. Pass the `type` discriminator (CREATE_FILE_REQUEST, INSERT_AT_OFFSET_REQUEST, or REPLACE_RANGE_REQUEST). ALWAYS prefer this over the generic `edit`/`create` tools for .kt/.kts changes — it guards against compile breakage and import drift.",
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
    handler: (args) => callKastSkill("write-and-validate", args),
  },
];

// ---------------------------------------------------------------------------
// Hooks: bootstrap the binary and warn on generic Kotlin file access.

const KOTLIN_PATH = /\.kts?$/i;
// Tools where args carry a file path we should inspect.
const FILE_TOOL_KEYS = {
  view: ["path", "filePath", "file_path"],
  edit: ["path", "filePath", "file_path"],
  create: ["path", "filePath", "file_path"],
  grep: ["paths", "path"],
};
// Per-tool one-time nag — fire once per session per tool name.
const warned = new Set();

function extractKotlinPath(toolName, toolArgs) {
  const keys = FILE_TOOL_KEYS[toolName];
  if (!keys || !toolArgs || typeof toolArgs !== "object") return null;
  for (const key of keys) {
    const v = toolArgs[key];
    if (typeof v === "string" && KOTLIN_PATH.test(v)) return v;
    if (Array.isArray(v)) {
      for (const entry of v) {
        if (typeof entry === "string" && KOTLIN_PATH.test(entry)) return entry;
      }
    }
  }
  return null;
}

function suggestionFor(toolName) {
  switch (toolName) {
    case "view":
      return "Prefer `kast_scaffold` (semantic skeleton: declarations, signatures, key call sites) over `view` for .kt/.kts files. Scaffold returns a fraction of the tokens with structured meaning. Use `view` only for non-semantic concerns (comments, formatting, generated files).";
    case "grep":
      return "Prefer `kast_references` / `kast_resolve` / `kast_callers` over `grep` for Kotlin identity — grep cannot disambiguate overloads, inherited members, or imports vs aliases. Reserve `grep` for non-semantic searches (string literals, comments, build files).";
    case "edit":
    case "create":
      return "Prefer `kast_write_and_validate` over the generic `edit`/`create` tool for .kt/.kts files. write-and-validate runs diagnostics atomically and protects against import drift and compile breakage.";
    default:
      return null;
  }
}

const session = await joinSession({
  tools,
  hooks: {
    onSessionStart: async () => {
      warned.clear();
      const bin = await resolveKastBinary();
      if (!bin) {
        await session.log(
          `kast extension: failed to resolve kast binary (${resolveError}). Native kast_* tools will return errors until the binary is on PATH or KAST_CLI_PATH is set.`,
          { level: "warning" },
        );
        return {};
      }
      await session.log(`kast extension ready (binary: ${bin})`, { ephemeral: true });
      return {
        additionalContext:
          `Kast tools available natively: kast_workspace_files, kast_scaffold, kast_resolve, kast_references, kast_callers, kast_metrics, kast_diagnostics, kast_rename, kast_write_and_validate. ` +
          `Use these for ALL Kotlin semantic work — they are far cheaper than view/grep/edit on .kt source. ` +
          `If a bash fallback is genuinely necessary, KAST_CLI_PATH=${bin} (set this in the bash command's env, not via export which doesn't persist across calls).`,
      };
    },
    onPreToolUse: async (input) => {
      const toolName = input.toolName;
      const toolArgs = input.toolArgs;
      const offending = extractKotlinPath(toolName, toolArgs);
      if (!offending) return;
      const suggestion = suggestionFor(toolName);
      if (!suggestion) return;
      // Always allow; warn at most once per tool per session to avoid nag spam.
      if (warned.has(toolName)) return;
      warned.add(toolName);
      return {
        permissionDecision: "allow",
        additionalContext: `kast hint (${offending}): ${suggestion}`,
      };
    },
  },
});
