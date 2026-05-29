// Kast extension for Copilot CLI.
//
// Goals:
//   1. Resolve the kast binary once at
//      session start, cache, and use that path for every kast_* tool call.
//   2. Expose native kast_* tools backed by `kast rpc` where the daemon method
//      maps 1:1, while keeping wrapper-backed orchestration for the richer flows.
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
import {KAST_TOOL_NAMES, makeKastTools} from "../_shared/kast-tools.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT_MARKER = "workspace.repos.toml";

function hasRepoMarker(path) {
  const marker = resolve(path, ROOT_MARKER);
  const githubDir = resolve(path, ".github");
  const analysisApi = resolve(path, "analysis-api");
  return existsSync(marker) && existsSync(githubDir) && existsSync(analysisApi);
}

function findRepoRoot(start) {
  let cursor = resolve(start);
  while (cursor && cursor !== dirname(cursor)) {
    if (hasRepoMarker(cursor)) {
      return cursor;
    }
    cursor = dirname(cursor);
  }
  return null;
}

const REPO_ROOT = (() => {
  if (process.env.KAST_EXTENSION_REPO_ROOT) {
    return resolve(process.env.KAST_EXTENSION_REPO_ROOT);
  }
  const cwdCandidate = findRepoRoot(process.cwd());
  if (cwdCandidate) {
    return cwdCandidate;
  }
  // Canonical extension location pattern is:
  // <repo>/resources/copilot-extension/extensions/kast/extension.mjs
  // so this fallback keeps this root valid.
  const resourcesRoot = resolve(HERE, "..", "..", "..", "..");
  if (hasRepoMarker(resourcesRoot)) {
    return resourcesRoot;
  }
  return resolve(HERE, "..", "..", "..");
})();
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
  const extensionRepoRoot = process.env.KAST_EXTENSION_REPO_ROOT
    ? resolve(process.env.KAST_EXTENSION_REPO_ROOT, ".github", ".kast-copilot-version")
    : null;
  const candidateMarkers = [
    extensionRepoRoot,
    COPILOT_VERSION_MARKER,
  ];
  for (const markerPath of candidateMarkers) {
    if (!markerPath) continue;
    try {
      return readFileSync(markerPath, "utf8").trim() || null;
    } catch {
      // fallback to next candidate
    }
  }
  return null;
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

  const pathResult = await execBash("command -v kast 2>/dev/null || true");
  if (pathResult.ok) addCandidate(pathResult.stdout.trim());

  addCandidate(join(REPO_ROOT, "target", "debug", "kast"));
  addCandidate(join(REPO_ROOT, "target", "release", "kast"));

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
    ? `no resolved Rust kast CLI supports kast rpc; rejected: ${rejected.join(", ")}`
    : "no Rust kast CLI candidate found; build cli-rs or install a matching Kast release";
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
  const cmd = `${JSON.stringify(path)} rpc '{"jsonrpc":"2.0","method":"health","id":1}' --workspace-root=${JSON.stringify(REPO_ROOT)}`;
  const { ok, stdout } = await execBash(cmd);
  if (!ok) return false;
  try {
    const parsed = JSON.parse(stdout.trim());
    return parsed?.jsonrpc === "2.0" && Object.prototype.hasOwnProperty.call(parsed, "result");
  } catch {
    return false;
  }
}

async function callKast(method, params) {
  const bin = await resolveKastBinary();
  if (!bin) {
    return JSON.stringify({
      ok: false,
      stage: "extension.resolve",
      message: `kast binary not resolved: ${resolveError ?? "unknown"}`,
    });
  }
  const request = JSON.stringify({ jsonrpc: "2.0", method, params: params ?? {}, id: 1 });
  const cmd = `${JSON.stringify(bin)} rpc ${JSON.stringify(request)} --workspace-root=${JSON.stringify(REPO_ROOT)}`;
  const { ok, stdout, stderr, code } = await execBash(cmd);
  const out = stdout.trim();
  if (!out) {
    return JSON.stringify({
      ok: false,
      stage: "extension.exec",
      message: `kast rpc ${method} produced no output (exit ${code})`,
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
      message: `kast rpc ${method} returned non-JSON (exit ${code})`,
      raw: out,
      errorText: stderr.trim() || null,
    });
  }
}

// ---------------------------------------------------------------------------
const tools = makeKastTools((method, args) => callKast(method, args));
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
        `${JSON.stringify(bin)} up --workspace-root=${JSON.stringify(REPO_ROOT)} --accept-indexing=true`,
      ).then(({ok, stderr}) => {
        if (!ok) {
          session.log(
            `kast extension: up failed for ${REPO_ROOT}. stderr: ${stderr.trim().slice(0, 200)}`,
            { level: "warning" },
          );
        } else {
          session.log(`kast extension: backend ready for ${REPO_ROOT}`, { ephemeral: true });
        }
      }).catch(() => {});
      const toolNames = Array.from(KAST_TOOL_NAMES).join(", ");
      const toolContext =
        `Kast tools available natively: ${toolNames}. ` +
        `Use these for ALL Kotlin semantic work and Kotlin source search — they are far cheaper than view/grep/rg/edit on .kt source. ` +
        `If a bash fallback is genuinely necessary, run ${bin} rpc '<jsonrpc-request>' directly; do not rely on exported shell state across tool calls.`;
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
