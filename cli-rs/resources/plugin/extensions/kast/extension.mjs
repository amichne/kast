import { execFile } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";
import { joinSession } from "@github/copilot-sdk/extension";
import { createTraceEmitter, traceFieldsFromParams } from "./_shared/kast-trace.mjs";
import { bundledKastToolSpecs, makeKastTools, toolSpecsFromAgentToolsResult } from "./_shared/kast-tools.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT_MARKER = "workspace.repos.toml";
const COPILOT_IDEA_AUTOSTART_ENV = "KAST_COPILOT_IDEA_AUTOSTART";
const KAST_TOOLING_CONTEXT = [
  "Kast tooling preference:",
  "For Kotlin or Gradle semantic work, use the configured kotlin LSP server first for standard editor operations.",
  "Use catalog-backed kast_* tools for Kast-specific symbol identity, references, callers, hierarchy, diagnostics, workspace discovery, metrics, and safe write flows.",
  "Use `kast agent tools` when a CLI-only host needs to discover the same catalog-backed tools without this Copilot extension.",
  "Use shell only for validation, explicit lifecycle commands, or a `kast agent` fallback when LSP and kast_* tools cannot cover the operation.",
  "If Kast reports a missing backend, missing source index, INDEX_UNAVAILABLE, METRICS_DB_UNAVAILABLE, or NO_BACKEND_AVAILABLE, warm the IDEA backend with `kast runtime up --workspace-root \"$PWD\" --backend idea` before falling back.",
  "Treat stale, missing, ambiguous, partial, or truncated compiler-backed facts as blockers after warmup; do not replace Kotlin identity, references, hierarchy, rename, or edit scope with text search guesses.",
].join(" ");
const RECOVERABLE_WARMUP_CODES = new Set([
  "INDEX_UNAVAILABLE",
  "METRICS_DB_UNAVAILABLE",
  "DEMO_SOURCE_INDEX_MISSING",
  "DEMO_SOURCE_INDEX_STALE",
  "NO_BACKEND_AVAILABLE",
]);

let kastBinary = null;
let resolveError = null;

function hasRepoMarker(path) {
  return existsSync(resolve(path, ROOT_MARKER)) && existsSync(resolve(path, ".github"));
}

function findRepoRoot(start) {
  let cursor = resolve(start);
  while (cursor && cursor !== dirname(cursor)) {
    if (hasRepoMarker(cursor)) return cursor;
    cursor = dirname(cursor);
  }
  return null;
}

const REPO_ROOT = (() => {
  if (process.env.KAST_EXTENSION_REPO_ROOT) {
    return resolve(process.env.KAST_EXTENSION_REPO_ROOT);
  }
  const fromCwd = findRepoRoot(process.cwd());
  if (fromCwd) return fromCwd;
  const installedRoot = resolve(HERE, "..", "..", "..");
  if (hasRepoMarker(installedRoot)) return installedRoot;
  return installedRoot;
})();

const trace = createTraceEmitter({ repoRoot: REPO_ROOT });
trace.emit("copilot.extension.bootstrap", {
  sdkRegistrationScope: "extension-session",
  detail: {
    repoRoot: REPO_ROOT,
    traceEnabled: trace.enabled,
  },
});

function readJsonFile(filePath) {
  try {
    return JSON.parse(readFileSync(filePath, "utf8"));
  } catch {
    return null;
  }
}

function execCommand(command, args, options = {}) {
  return new Promise((resolveResult) => {
    execFile(
      command,
      args,
      { ...options, maxBuffer: 32 * 1024 * 1024 },
      (error, stdout, stderr) => {
        resolveResult({
          ok: !error,
          code: error?.code ?? 0,
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
  const version = cliVersionFromStdout(stdout);
  return version === "dev" || /\d+\.\d+/.test(version) || /^[0-9a-f]{7,40}(?:[+-].*)?$/i.test(version);
}

async function supportsKastCli(path) {
  const version = await execCommand(path, ["--version"]);
  if (!version.ok || !looksLikeKastCliVersion(version.stdout)) return false;
  const help = await execCommand(path, ["agent", "--help"]);
  if (!help.ok || !/\bcall\b/i.test(`${help.stdout}\n${help.stderr}`)) return false;
  const tools = await execCommand(path, ["agent", "tools"]);
  const toolsJson = parseJsonOrNull(tools.stdout.trim());
  return tools.ok &&
    toolsJson?.ok === true &&
    toolsJson?.method === "agent/tools" &&
    Array.isArray(toolsJson?.result?.tools);
}

function findOnPath(commandName) {
  const pathValue = process.env.PATH ?? "";
  const extensions = process.platform === "win32"
    ? (process.env.PATHEXT ?? ".EXE;.CMD;.BAT;.COM").split(";")
    : [""];
  for (const directory of pathValue.split(delimiter)) {
    if (!directory) continue;
    for (const extension of extensions) {
      const candidate = join(directory, `${commandName}${extension}`);
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

async function resolveKastBinary() {
  if (kastBinary) return kastBinary;

  const candidates = [];
  const addCandidate = (path) => {
    if (typeof path === "string" && path && existsSync(path) && !candidates.includes(path)) candidates.push(path);
  };

  const installRoot = process.env.KAST_INSTALL_ROOT ?? join(homedir(), ".local", "share", "kast");
  const installManifest = readJsonFile(join(installRoot, "install.json"));
  addCandidate(installManifest?.entrypoints?.shim);
  addCandidate(installManifest?.entrypoints?.activeBinary);
  addCandidate(join(homedir(), ".local", "bin", "kast"));
  addCandidate(findOnPath("kast"));

  addCandidate(join(REPO_ROOT, "cli-rs", "target", "debug", "kast"));
  addCandidate(join(REPO_ROOT, "cli-rs", "target", "release", "kast"));

  const rejected = [];
  for (const candidate of candidates) {
    if (await supportsKastCli(candidate)) {
      kastBinary = candidate;
      trace.emit("copilot.kast_binary.resolved", {
        sdkRegistrationScope: "extension-session",
        detail: {
          candidate,
          rejected,
        },
      });
      return candidate;
    }
    rejected.push(candidate);
  }

  resolveError = rejected.length
    ? `no resolved Rust kast CLI exposes kast agent; rejected: ${rejected.join(", ")}`
    : "no Rust kast CLI candidate found in install.json, ~/.local/bin, PATH, or under cli-rs/target";
  trace.emit("copilot.kast_binary.resolve_failed", {
    sdkRegistrationScope: "extension-session",
    outcome: "failed",
    detail: {
      rejected,
      resolveError,
    },
  });
  return null;
}

function backendArgs() {
  const value = String(process.env[COPILOT_IDEA_AUTOSTART_ENV] ?? "").trim().toLowerCase();
  return ["1", "true", "yes", "on"].includes(value) ? ["--backend", "idea"] : [];
}

function parseJsonOrNull(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function loadKastToolSpecs() {
  const bin = await resolveKastBinary();
  if (bin) {
    const tools = await execCommand(bin, ["agent", "tools"]);
    const toolsJson = parseJsonOrNull(tools.stdout.trim());
    if (tools.ok && toolsJson?.ok === true) {
      try {
        const specs = toolSpecsFromAgentToolsResult(toolsJson);
        trace.emit("copilot.tool_specs.loaded", {
          sdkRegistrationScope: "extension-session",
          outcome: "completed",
          detail: {
            source: "kast-agent-tools",
            catalogSha256: toolsJson.result?.catalogSha256 ?? null,
            toolCount: specs.length,
          },
        });
        return {
          source: "kast-agent-tools",
          bin,
          catalogSha256: toolsJson.result?.catalogSha256 ?? null,
          specs,
        };
      } catch (error) {
        trace.emit("copilot.tool_specs.invalid", {
          sdkRegistrationScope: "extension-session",
          outcome: "failed",
          detail: {
            source: "kast-agent-tools",
            message: error?.message ?? String(error),
          },
        });
      }
    } else {
      trace.emit("copilot.tool_specs.load_failed", {
        sdkRegistrationScope: "extension-session",
        outcome: "failed",
        detail: {
          source: "kast-agent-tools",
          exitCode: tools.code,
          errorText: tools.stderr.trim() || null,
        },
      });
    }
  }
  const specs = bundledKastToolSpecs();
  trace.emit("copilot.tool_specs.loaded", {
    sdkRegistrationScope: "extension-session",
    outcome: "completed",
    detail: {
      source: "bundled-catalog-fallback",
      toolCount: specs.length,
    },
  });
  return {
    source: "bundled-catalog-fallback",
    bin: null,
    catalogSha256: null,
    specs,
  };
}

function resultCode(value) {
  if (!value || typeof value !== "object") return null;
  if (typeof value.code === "string") return value.code;
  if (typeof value.error?.code === "string") return value.error.code;
  if (typeof value.error?.details?.rpcError?.data?.code === "string") {
    return value.error.details.rpcError.data.code;
  }
  if (typeof value.response?.error?.data?.code === "string") {
    return value.response.error.data.code;
  }
  if (typeof value.error?.data?.code === "string") return value.error.data.code;
  const result = value.result;
  if (result && typeof result === "object") {
    if (typeof result.reason === "string") return result.reason;
    if (typeof result.code === "string") return result.code;
  }
  return null;
}

function needsIdeaWarmup(value) {
  return RECOVERABLE_WARMUP_CODES.has(resultCode(value));
}

function agentArgs(method, params, args = backendArgs()) {
  return [
    "--output",
    "json",
    "agent",
    "call",
    method,
    "--params",
    JSON.stringify(params ?? {}),
    "--workspace-root",
    REPO_ROOT,
    ...args,
  ];
}

async function warmIdeaBackend(bin) {
  return execCommand(bin, [
    "--output",
    "json",
    "runtime",
    "up",
    "--workspace-root",
    REPO_ROOT,
    "--backend",
    "idea",
  ]);
}

function formattedAgentResult(method, result, warmup = null) {
  const out = result.stdout.trim();
  if (!out) {
    return JSON.stringify({
      ok: false,
      stage: "extension.exec",
      method,
      message: `kast agent call ${method} produced no output`,
      exitCode: result.code,
      errorText: result.stderr.trim() || null,
      ideaWarmup: warmup,
    });
  }
  if (parseJsonOrNull(out)) return out;
  return JSON.stringify({
    ok: false,
    stage: "extension.parse",
    method,
    message: `kast agent call ${method} returned non-JSON`,
    exitCode: result.code,
    raw: out,
    errorText: result.stderr.trim() || null,
    ideaWarmup: warmup,
  });
}

async function callKast(method, params) {
  const invocationId = randomUUID();
  const paramTraceFields = traceFieldsFromParams(params ?? {});
  trace.emit("copilot.tool.invocation_started", {
    invocationId,
    agentRole: "kast-tool",
    sdkRegistrationScope: "extension-session",
    ...paramTraceFields,
    detail: {
      method,
    },
  });

  const bin = await resolveKastBinary();
  if (!bin) {
    trace.emit("copilot.tool.invocation_failed", {
      invocationId,
      agentRole: "kast-tool",
      sdkRegistrationScope: "extension-session",
      ...paramTraceFields,
      outcome: "failed",
      detail: {
        method,
        stage: "extension.resolve",
        resolveError,
      },
    });
    return JSON.stringify({
      ok: false,
      stage: "extension.resolve",
      method,
      message: `kast binary not resolved: ${resolveError ?? "unknown"}`,
    });
  }

  const first = await execCommand(bin, agentArgs(method, params));
  const firstJson = parseJsonOrNull(first.stdout.trim());
  trace.emit("copilot.tool.agent_completed", {
    invocationId,
    agentRole: "kast-tool",
    sdkRegistrationScope: "extension-session",
    ...paramTraceFields,
    outcome: first.ok ? "completed" : "failed",
    detail: {
      method,
      exitCode: first.code,
      resultCode: resultCode(firstJson),
    },
  });
  if (needsIdeaWarmup(firstJson)) {
    trace.emit("copilot.tool.idea_warmup_started", {
      invocationId,
      agentRole: "kast-tool",
      sdkRegistrationScope: "extension-session",
      ...paramTraceFields,
      detail: {
        method,
        resultCode: resultCode(firstJson),
      },
    });
    const warmup = await warmIdeaBackend(bin);
    const warmupJson = parseJsonOrNull(warmup.stdout.trim());
    trace.emit("copilot.tool.idea_warmup_completed", {
      invocationId,
      agentRole: "kast-tool",
      sdkRegistrationScope: "extension-session",
      ...paramTraceFields,
      outcome: warmup.ok ? "completed" : "failed",
      detail: {
        method,
        exitCode: warmup.code,
        resultCode: resultCode(warmupJson),
      },
    });
    if (warmup.ok) {
      const retried = await execCommand(bin, agentArgs(method, params, ["--backend", "idea"]));
      const retriedJson = parseJsonOrNull(retried.stdout.trim());
      trace.emit("copilot.tool.idea_retry_completed", {
        invocationId,
        agentRole: "kast-tool",
        sdkRegistrationScope: "extension-session",
        ...paramTraceFields,
        outcome: retried.ok ? "completed" : "failed",
        detail: {
          method,
          exitCode: retried.code,
          resultCode: resultCode(retriedJson),
        },
      });
      return formattedAgentResult(method, retried);
    }
    return formattedAgentResult(method, first, {
      attempted: true,
      ok: false,
      exitCode: warmup.code,
      error: warmupJson,
      errorText: warmup.stderr.trim() || null,
    });
  }
  trace.emit("copilot.tool.invocation_completed", {
    invocationId,
    agentRole: "kast-tool",
    sdkRegistrationScope: "extension-session",
    ...paramTraceFields,
    outcome: "completed",
    detail: {
      method,
    },
  });
  return formattedAgentResult(method, first);
}

const toolSpecLoad = await loadKastToolSpecs();
const tools = makeKastTools(toolSpecLoad.specs, (method, args) => callKast(method, args));
const toolNames = tools.map((tool) => tool.name);
const hooks = {
  onSessionStart: async () => ({
    additionalContext: KAST_TOOLING_CONTEXT,
  }),
  onUserPromptSubmitted: async () => ({
    additionalContext: KAST_TOOLING_CONTEXT,
  }),
};

trace.emit("copilot.session.join_requested", {
  sdkRegistrationScope: "extension-session",
  detail: {
    tools: toolNames,
    toolSpecSource: toolSpecLoad.source,
    catalogSha256: toolSpecLoad.catalogSha256,
    runtimeGuidance: "kast-tooling-context",
  },
});

const session = await joinSession({
  tools,
  hooks,
  disabledSkills: ["kast"],
});
trace.attachSession(session);
trace.emit("copilot.session.joined", {
  sdkRegistrationScope: "extension-session",
  outcome: "completed",
  detail: {
    tools: toolNames,
    toolSpecSource: toolSpecLoad.source,
    catalogSha256: toolSpecLoad.catalogSha256,
    runtimeGuidance: "kast-tooling-context",
  },
});

const bin = toolSpecLoad.bin ?? await resolveKastBinary();
if (!bin) {
  await session.log(
    `kast extension: failed to resolve kast binary (${resolveError}). Tools will return structured errors until kast is installed or built.`,
    { level: "warning" },
  );
} else {
  const version = await execCommand(bin, ["--version"]);
  await session.log(
    `kast extension ready (binary: ${bin}, version: ${cliVersionFromStdout(version.stdout) || "unknown"}; toolSpecSource: ${toolSpecLoad.source}; tools: ${toolNames.join(", ")})`,
    { ephemeral: true },
  );
}
