import { execFile } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";
import { createTraceEmitter } from "./_shared/kast-trace.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT_MARKER = "workspace.repos.toml";
const KAST_TOOLING_CONTEXT = [
  "Kast tooling preference:",
  "For Kotlin or Gradle semantic work, use the configured kotlin LSP server first for standard editor operations.",
  "Use typed shell commands for Kast-specific compiler-backed work: `kast`, `kast help agent`, `kast ready --for agent`, `kast agent symbol`, `kast agent diagnostics`, `kast agent impact`, and `kast agent rename`.",
  "Do not use removed helper surfaces such as `kast agent tools`, `kast agent call`, `kast agent workflow`, generated protocol paths, or raw RPC.",
  "If Kast reports a missing backend, missing source index, INDEX_UNAVAILABLE, METRICS_DB_UNAVAILABLE, or NO_BACKEND_AVAILABLE, run `kast ready --for agent --workspace-root \"$PWD\"` and then the emitted `kast repair --for agent --workspace-root \"$PWD\" --apply` recovery before falling back.",
  "Treat stale, missing, ambiguous, partial, or truncated compiler-backed facts as blockers after recovery; do not replace Kotlin identity, references, hierarchy, rename, or edit scope with text search guesses.",
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
  const helpText = `${help.stdout}\n${help.stderr}`;
  return help.ok &&
    /\bsymbol\b/i.test(helpText) &&
    /\bdiagnostics\b/i.test(helpText) &&
    /\bimpact\b/i.test(helpText) &&
    /\brename\b/i.test(helpText) &&
    !/\btools\b/i.test(helpText) &&
    !/\bcall\b/i.test(helpText) &&
    !/\bworkflow\b/i.test(helpText);
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
    ? `no resolved Rust kast CLI exposes typed kast agent commands; rejected: ${rejected.join(", ")}`
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
    tools: [],
    runtimeGuidance: "kast-tooling-context",
    recoverableWarmupCodes: [...RECOVERABLE_WARMUP_CODES],
  },
});

const session = await joinSession({
  tools: [],
  hooks,
  disabledSkills: ["kast"],
});
trace.attachSession(session);
trace.emit("copilot.session.joined", {
  sdkRegistrationScope: "extension-session",
  outcome: "completed",
  detail: {
    tools: [],
    runtimeGuidance: "kast-tooling-context",
  },
});

const bin = await resolveKastBinary();
if (!bin) {
  await session.log(
    `kast extension: failed to resolve a kast binary with typed agent commands (${resolveError}). LSP configuration and prompt guidance remain active; install, build, or reinstall Kast and reload the Copilot session.`,
    { level: "warning" },
  );
} else {
  const version = await execCommand(bin, ["--version"]);
  await session.log(
    `kast extension ready (binary: ${bin}, version: ${cliVersionFromStdout(version.stdout) || "unknown"}; tools: none; guidance: typed agent commands)`,
    { ephemeral: true },
  );
}
