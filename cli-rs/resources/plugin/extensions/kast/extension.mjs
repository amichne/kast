import { execFile } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";
import { makeKastCustomAgents } from "./_shared/kast-agents.mjs";
import { KAST_TOOL_NAMES, makeKastTools } from "./_shared/kast-tools.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT_MARKER = "workspace.repos.toml";
const COPILOT_IDEA_AUTOSTART_ENV = "KAST_COPILOT_IDEA_AUTOSTART";
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

function readTomlKey(filePath, section, key) {
  try {
    let inSection = false;
    for (const line of readFileSync(filePath, "utf8").split("\n")) {
      const trimmed = line.trim();
      if (trimmed === `[${section}]`) {
        inSection = true;
        continue;
      }
      if (inSection && trimmed.startsWith("[")) break;
      if (!inSection) continue;
      const match = trimmed.match(/^(\w+)\s*=\s*"(.*)"$/);
      if (match && match[1] === key) return match[2];
    }
  } catch {
    return null;
  }
  return null;
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
  const help = await execCommand(path, ["help", "rpc"]);
  return help.ok && /\brpc\b/i.test(`${help.stdout}\n${help.stderr}`);
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
    if (path && existsSync(path) && !candidates.includes(path)) candidates.push(path);
  };

  const configDir = process.env.KAST_CONFIG_HOME ?? join(homedir(), ".config", "kast");
  addCandidate(readTomlKey(join(configDir, "config.toml"), "cli", "binaryPath"));
  addCandidate(join(homedir(), ".kast", "bin", "kast"));
  addCandidate(findOnPath("kast"));

  addCandidate(join(REPO_ROOT, "cli-rs", "target", "debug", "kast"));
  addCandidate(join(REPO_ROOT, "cli-rs", "target", "release", "kast"));

  const rejected = [];
  for (const candidate of candidates) {
    if (await supportsKastCli(candidate)) {
      kastBinary = candidate;
      return candidate;
    }
    rejected.push(candidate);
  }

  resolveError = rejected.length
    ? `no resolved Rust kast CLI exposes kast rpc; rejected: ${rejected.join(", ")}`
    : "no Rust kast CLI candidate found on PATH, in KAST_CONFIG_HOME, or under cli-rs/target";
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

function resultCode(value) {
  if (!value || typeof value !== "object") return null;
  if (typeof value.code === "string") return value.code;
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

function rpcArgs(request, args = backendArgs()) {
  return [
    "--output",
    "json",
    "rpc",
    request,
    "--workspace-root",
    REPO_ROOT,
    ...args,
  ];
}

async function warmIdeaBackend(bin) {
  return execCommand(bin, [
    "--output",
    "json",
    "up",
    "--workspace-root",
    REPO_ROOT,
    "--backend",
    "idea",
  ]);
}

function formattedRpcResult(method, result, warmup = null) {
  const out = result.stdout.trim();
  if (!out) {
    return JSON.stringify({
      ok: false,
      stage: "extension.exec",
      method,
      message: `kast rpc ${method} produced no output`,
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
    message: `kast rpc ${method} returned non-JSON`,
    exitCode: result.code,
    raw: out,
    errorText: result.stderr.trim() || null,
    ideaWarmup: warmup,
  });
}

async function callKast(method, params) {
  const bin = await resolveKastBinary();
  if (!bin) {
    return JSON.stringify({
      ok: false,
      stage: "extension.resolve",
      method,
      message: `kast binary not resolved: ${resolveError ?? "unknown"}`,
    });
  }

  const request = JSON.stringify({
    jsonrpc: "2.0",
    method,
    params: params ?? {},
    id: 1,
  });
  const first = await execCommand(bin, rpcArgs(request));
  const firstJson = parseJsonOrNull(first.stdout.trim());
  if (needsIdeaWarmup(firstJson)) {
    const warmup = await warmIdeaBackend(bin);
    const warmupJson = parseJsonOrNull(warmup.stdout.trim());
    if (warmup.ok) {
      return formattedRpcResult(method, await execCommand(bin, rpcArgs(request, ["--backend", "idea"])));
    }
    return formattedRpcResult(method, first, {
      attempted: true,
      ok: false,
      exitCode: warmup.code,
      error: warmupJson,
      errorText: warmup.stderr.trim() || null,
    });
  }
  return formattedRpcResult(method, first);
}

const session = await joinSession({
  tools: makeKastTools((method, args) => callKast(method, args)),
  customAgents: makeKastCustomAgents(),
  disabledSkills: ["kast"],
});

const bin = await resolveKastBinary();
if (!bin) {
  await session.log(
    `kast extension: failed to resolve kast binary (${resolveError}). Tools will return structured errors until kast is installed or built.`,
    { level: "warning" },
  );
} else {
  const version = await execCommand(bin, ["--version"]);
  await session.log(
    `kast extension ready (binary: ${bin}, version: ${cliVersionFromStdout(version.stdout) || "unknown"}; tools: ${Array.from(KAST_TOOL_NAMES).join(", ")})`,
    { ephemeral: true },
  );
}
