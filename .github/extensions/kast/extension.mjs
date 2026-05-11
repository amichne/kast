// Kast extension for Copilot CLI.
//
// Goals:
//   1. Resolve the kast binary once at
//      session start, cache, and use that path for every kast_* tool call.
//   2. Expose direct `kast <wrapper>` commands as first-class native tools so the agent
//      sees them in its tool list (discoverability) and the CLI runtime
//      validates arguments against the schema (no JSON-in-bash brittleness).
//   3. Soft-warn when the agent reaches for generic view/grep/edit/create on
//      Kotlin source — the kast equivalent is almost always cheaper in tokens
//      and produces structured results. Soft (not deny) so genuinely
//      non-semantic work (comments, formatting, generated files) still flows.

import {execFile} from "node:child_process";
import {existsSync, readFileSync} from "node:fs";
import {homedir} from "node:os";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {joinSession} from "@github/copilot-sdk/extension";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "..", "..", "..");
const RESOLVE_SCRIPT = join(HERE, "scripts", "resolve-kast.sh");
const COPILOT_VERSION_MARKER = join(HERE, "..", "..", ".kast-copilot-version");

let kastBinary = null;
let kastVersion = null;
let resolveError = null;

// Minimal TOML reader — handles only the subset written by the kast installer.
function readTomlKey(filePath, section, key) {
  try {
    let inSection = false;
    for (const line of readFileSync(filePath, "utf8").split("\n")) {
      const t = line.trim();
      if (t === `[${section}]`) {
        inSection = true;
        continue;
      }
      if (inSection && t.startsWith("[")) {
        break;
      }
      if (inSection) {
        const m = t.match(/^(\w+)\s*=\s*"(.*)"/);
        if (m && m[1] === key) {
          return m[2];
        }
      }
    }
  } catch { /* file absent or unreadable */
  }
  return null;
}

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

function cliVersionFromStdout(stdout) {
  const text = String(stdout ?? "").trim();
  const prefixed = text.match(/^Kast CLI\s+(.+)$/i);
  return (prefixed ? prefixed[1] : text).trim();
}

function looksLikeKastCliVersion(stdout) {
  const text = String(stdout ?? "").trim();
  if (/^Kast CLI\s+\S+/i.test(text)) return true;
  const version = cliVersionFromStdout(text);
  return version === "dev" || /\d+\.\d+/.test(version) || /^[0-9a-f]{7,40}(?:[+-].*)?$/i.test(version);
}

async function readCliVersion(path) {
  const {ok, stdout} = await execBash(`${JSON.stringify(path)} --version`);
  if (!ok) return null;
  if (!looksLikeKastCliVersion(stdout)) return null;
  const version = cliVersionFromStdout(stdout);
  return version || null;
}

function readInstalledExtensionVersion() {
  try {
    return readFileSync(COPILOT_VERSION_MARKER, "utf8").trim() || null;
  } catch {
    return null;
  }
}

async function resolveKastBinary() {
  if (kastBinary) return kastBinary;

  const candidates = [];
  const addCandidate = (path) => {
    if (path && existsSync(path) && !candidates.includes(path)) {
      candidates.push(path);
    }
  };

  const configDir = process.env.KAST_CONFIG_HOME ?? join(homedir(), ".config", "kast");
  addCandidate(readTomlKey(join(configDir, "config.toml"), "cli", "binaryPath"));
  addCandidate(join(homedir(), ".kast", "bin", "kast"));

  const pathResult = await execBash("command -v kast 2>/dev/null || command -v kast-cli 2>/dev/null || true");
  if (pathResult.ok) addCandidate(pathResult.stdout.trim());

  addCandidate(join(REPO_ROOT, "kast-cli", "build", "scripts", "kast-cli"));
  addCandidate(join(REPO_ROOT, "dist", "cli", "kast-cli"));

  if (existsSync(RESOLVE_SCRIPT)) {
    const {ok, stdout} = await execBash(`bash ${JSON.stringify(RESOLVE_SCRIPT)}`);
    if (ok) addCandidate(stdout.trim());
  }

  const rejected = [];
  for (const candidate of candidates) {
    if (await supportsWrapperCommands(candidate)) {
      kastBinary = candidate;
      return candidate;
    }
    rejected.push(candidate);
  }

  resolveError = rejected.length
    ? `no resolved Kast CLI supports direct wrapper commands; rejected: ${rejected.join(", ")}`
    : "no Kast CLI candidate found; build the repo-local CLI or install a matching Kast release";
  return null;
}

async function queryCliVersion(path) {
  const {ok, stdout} = await execBash(`${JSON.stringify(path)} --version`);
  if (!ok) return null;
  const match = stdout.trim().replace(/\x1b\[[0-9;]*m/g, "").match(/Kast CLI (.+)/);
  return match ? match[1].trim() : null;
}

function readInstalledVersion() {
  const markerPath = join(REPO_ROOT, ".github", ".kast-copilot-version");
  try {
    return readFileSync(markerPath, "utf8").trim();
  } catch {
    return null;
  }
}

async function supportsWrapperCommands(path) {
  return (await readCliVersion(path)) !== null;
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
  const cmd = `${JSON.stringify(bin)} ${command} ${JSON.stringify(json)}`;
  const { ok, stdout, stderr, code } = await execBash(cmd);
  // kast prints JSON to stdout; surface any stderr if the JSON parse would fail.
  const out = stdout.trim();
  if (!out) {
    return JSON.stringify({
      ok: false,
      stage: "extension.exec",
      message: `kast ${command} produced no output (exit ${code})`,
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
      message: `kast ${command} returned non-JSON (exit ${code})`,
      raw: out,
      errorText: stderr.trim() || null,
    });
  }
}

// ---------------------------------------------------------------------------
// Tool definitions — one per direct `kast <wrapper>` command.
// Schemas mirror references/quickstart.md; required fields enforce contract.

const ABS_PATH = "Absolute filesystem path.";

const tools = [
  {
    name: "kast_workspace_files",
    description:
      "List Kotlin workspace modules and (optionally) their source files via kast workspace-files. Use to discover scope before scaffolding or resolving symbols. Far cheaper than recursive directory listings; truncation is reported per-module.",
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
    name: "kast_workspace_symbol",
    description:
      "Search the workspace for Kotlin symbols by name pattern via kast workspace-symbol. Supports substring matching (default) and regex. Use to find declarations across the codebase — far more precise than grep/rg for symbol names because it understands Kotlin semantics (overloads, inherited members, cross-module references).",
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
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["pattern"],
    },
    handler: (args) => callKastSkill("workspace-symbol", args),
  },
  {
    name: "kast_workspace_search",
    description:
      "Search file contents across the workspace for text patterns via kast workspace-search. Supports substring and regex matching with optional file glob filtering. Use this instead of grep/rg for searching string literals, comments, and arbitrary text in Kotlin source files.",
    parameters: {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Search pattern (substring or regex)." },
        regex: { type: "boolean", description: "When true, treats pattern as a regular expression." },
        maxResults: { type: "integer", description: "Maximum number of matches to return. Default 100." },
        fileGlob: { type: "string", description: "Optional glob to restrict search (e.g., '*.kt')." },
        caseSensitive: { type: "boolean", description: "Case-sensitive matching. Default true." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["pattern"],
    },
    handler: (args) => callKastSkill("workspace-search", args),
  },
  {
    name: "kast_file_outline",
    description:
      "Get a hierarchical symbol outline for a Kotlin file via kast file-outline. Returns nested declarations (classes, functions, properties) with their signatures and locations. Lighter than scaffold — use when you only need the structural overview without references, type hierarchy, or file content.",
    parameters: {
      type: "object",
      properties: {
        filePath: { type: "string", description: ABS_PATH + " Required." },
        workspaceRoot: { type: "string", description: ABS_PATH + " Defaults to cwd." },
      },
      required: ["filePath"],
    },
    handler: (args) => callKastSkill("file-outline", args),
  },
  {
    name: "kast_scaffold",
    description:
      "Summarize a Kotlin file/type structure (declarations, signatures, imports, key call sites) via kast scaffold. Returns the full file content alongside the semantic skeleton — no separate `view` call needed for .kt files. ALWAYS prefer this over `view` for .kt/.kts files.",
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
      "Resolve a Kotlin symbol to its declaration via kast resolve. Use first whenever a name might be overloaded, inherited, or shadowed — disambiguate with kind/containingType/fileHint before tracing references or callers.",
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
      "Find every usage of a Kotlin symbol via kast references. ALWAYS prefer this over `grep` for Kotlin identity — grep cannot disambiguate overloads, inherited members, or imports vs aliases.",
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
      "Trace incoming or outgoing call hierarchy for a Kotlin function via kast callers. Use to understand flow, blast radius, or to find the entry points reaching a target.",
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
      "Query the indexed source metrics via kast metrics: fanIn, fanOut, coupling, lowUsage, cycles, moduleDepth, deadCode, impact. Treat results as advisory if the response indicates the reference index is missing or stale.",
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
    handler: (args) => callKastSkill("metrics", args),
  },
  {
    name: "kast_diagnostics",
    description:
      "Run Kotlin diagnostics on the listed files via kast diagnostics. Run after any mutation that did not already validate; treat dirty results as a failed change.",
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
      "Rename a Kotlin symbol safely (updates every reference) via kast rename. Pass the `type` discriminator (RENAME_BY_SYMBOL_REQUEST or RENAME_BY_OFFSET_REQUEST) plus the request fields. Validation runs automatically — non-clean responses mean the rename did not commit.",
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
      "Apply a Kotlin edit and validate it in one call via kast write-and-validate. Pass the `type` discriminator (CREATE_FILE_REQUEST, INSERT_AT_OFFSET_REQUEST, or REPLACE_RANGE_REQUEST). ALWAYS prefer this over the generic `edit`/`create` tools for .kt/.kts changes — it guards against compile breakage and import drift.",
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
  rg: ["paths", "path"],
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
      return "Prefer `kast_scaffold` over `view` for .kt/.kts files. Scaffold returns the semantic skeleton and full file content, so a separate `view` call is usually unnecessary. If you only need the declaration tree, use `kast_file_outline`. Reserve `view` for non-semantic concerns such as formatting or generated files.";
    case "grep":
    case "rg":
      return "Prefer `kast_workspace_symbol` for Kotlin symbol-name discovery, `kast_workspace_search` for Kotlin content search, and `kast_references` / `kast_resolve` / `kast_callers` for semantic identity work. Reserve grep/rg for non-Kotlin files or simple literal searches outside Kotlin source.";
    case "edit":
    case "create":
      return "Prefer `kast_write_and_validate` over the generic `edit`/`create` tool for .kt/.kts files. write-and-validate runs diagnostics atomically and protects against import drift and compile breakage.";
    default:
      return null;
  }
}

const session = await joinSession({
  tools,
  disabledSkills: ["kast"],
  hooks: {
    onSessionStart: async () => {
      warned.clear();
      const bin = await resolveKastBinary();
      if (!bin) {
        await session.log(
          `kast extension: failed to resolve kast binary (${resolveError}). Native kast_* tools will return errors until the binary is on PATH or built in this workspace.`,
          { level: "warning" },
        );
        return {};
      }

      // Version parity: compare CLI version against the installed extension marker.
      const cliVersion = await readCliVersion(bin);
      const installedVersion = readInstalledExtensionVersion();
      let warningContext = null;
      if (cliVersion && installedVersion && cliVersion !== installedVersion) {
        const syncResult = await execBash(
          `${JSON.stringify(bin)} install copilot-extension --target-dir=${JSON.stringify(join(REPO_ROOT, ".github"))} --yes=true`,
        );
        if (syncResult.ok) {
          await session.log(
            `kast extension: auto-synced copilot extension from ${installedVersion} to ${cliVersion}`,
            { level: "info" },
          );
        } else {
          const msg =
            `kast version mismatch: CLI=${cliVersion}, extension=${installedVersion}. ` +
            "Auto-sync failed. Run `kast install copilot-extension` manually.";
          warningContext = `KAST EXTENSION WARNING — ${msg}`;
          await session.log(`kast extension: ${msg}`, { level: "warning" });
        }
      }
      kastVersion = cliVersion;

      await session.log(`kast extension ready (binary: ${bin}, version: ${cliVersion ?? "unknown"})`, { ephemeral: true });
      execBash(
        `${JSON.stringify(bin)} workspace ensure --workspace-root=${JSON.stringify(REPO_ROOT)} --accept-indexing=true`,
      ).then(({ok, stderr}) => {
        if (!ok) {
          session.log(
            `kast extension: workspace ensure failed for ${REPO_ROOT}. stderr: ${stderr.trim().slice(0, 200)}`,
            { level: "warning" },
          );
        } else {
          session.log(`kast extension: backend ready for ${REPO_ROOT}`, { ephemeral: true });
        }
      }).catch(() => {});
      const toolContext =
        `Kast tools available natively: kast_workspace_files, kast_workspace_symbol, kast_workspace_search, kast_file_outline, kast_scaffold, kast_resolve, kast_references, kast_callers, kast_metrics, kast_diagnostics, kast_rename, kast_write_and_validate. ` +
        `Use these for ALL Kotlin semantic work and Kotlin source search — they are far cheaper than view/grep/rg/edit on .kt source. ` +
        `If a bash fallback is genuinely necessary, run ${bin} <wrapper> '<json>' directly; do not rely on exported shell state across tool calls.`;
      return {
        additionalContext: warningContext ? `${warningContext}\n${toolContext}` : toolContext,
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
