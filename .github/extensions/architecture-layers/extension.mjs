/**
 * architecture-layers extension
 *
 * The repository's L0-L6 architecture model is statically checkable through
 * `.github/architecture-layers.json` and the companion Python checker. This
 * extension keeps that contract visible to Copilot agents and exposes a native
 * `check_architecture_layers` tool without duplicating checker logic in JS.
 */

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";

const HERE = dirname(fileURLToPath(import.meta.url));
const MANIFEST_RELATIVE = join(".github", "architecture-layers.json");
const CHECKER_RELATIVE = join(
  ".github",
  "extensions",
  "architecture-layers",
  "check-architecture-layers.py",
);
const MAX_BUFFER = 16 * 1024 * 1024;
const MUTATING_TOOLS = new Set([
  "apply_patch",
  "create",
  "edit",
  "idea-create_new_file",
  "idea-reformat_file",
  "idea-rename_refactoring",
  "idea-replace_text_in_file",
  "kast_rename",
  "kast_write_and_validate",
]);
const LAYER_AFFECTING_PATH = /(^|\/)(settings\.gradle\.kts|build\.gradle\.kts|AGENTS\.md|zensical\.toml)$|^\.github\/architecture-layers\.json$|^\.github\/extensions\/architecture-layers\//;

let cachedWorkspaceRoot = null;
let resolveError = null;
let warnedMissingSupport = false;
let lastFailureFingerprint = null;

function findWorkspaceRoot(startPath = HERE) {
  let current = resolve(startPath);
  while (true) {
    if (
      existsSync(join(current, MANIFEST_RELATIVE)) &&
      existsSync(join(current, CHECKER_RELATIVE))
    ) {
      return current;
    }
    const parent = dirname(current);
    if (parent === current) return null;
    current = parent;
  }
}

function supportPaths() {
  if (cachedWorkspaceRoot) {
    return {
      workspaceRoot: cachedWorkspaceRoot,
      manifestPath: join(cachedWorkspaceRoot, MANIFEST_RELATIVE),
      checkerPath: join(cachedWorkspaceRoot, CHECKER_RELATIVE),
    };
  }
  const workspaceRoot = findWorkspaceRoot();
  if (!workspaceRoot) {
    resolveError =
      "could not locate architecture layer manifest and checker from extension path";
    return null;
  }
  cachedWorkspaceRoot = workspaceRoot;
  return {
    workspaceRoot,
    manifestPath: join(workspaceRoot, MANIFEST_RELATIVE),
    checkerPath: join(workspaceRoot, CHECKER_RELATIVE),
  };
}

function normalizeRepoPath(repo, cwd) {
  const base = cwd || process.cwd();
  if (!repo) return base;
  return isAbsolute(repo) ? repo : resolve(base, repo);
}

function execFileAsync(file, args, options = {}) {
  return new Promise((resolvePromise) => {
    execFile(
      file,
      args,
      {
        ...options,
        maxBuffer: MAX_BUFFER,
      },
      (error, stdout, stderr) => {
        resolvePromise({
          ok: !error,
          code: error?.code ?? 0,
          error,
          stdout: String(stdout ?? ""),
          stderr: String(stderr ?? ""),
        });
      },
    );
  });
}

async function runPythonScript(scriptPath, args, options = {}) {
  const candidates =
    process.platform === "win32" ? ["py", "python", "python3"] : ["python3", "python"];
  let missingInterpreterResult = null;

  for (const candidate of candidates) {
    const result = await execFileAsync(candidate, [scriptPath, ...args], options);
    if (result.ok || result.error?.code !== "ENOENT") {
      return result;
    }
    missingInterpreterResult = result;
  }

  return (
    missingInterpreterResult ?? {
      ok: false,
      code: "ENOENT",
      stdout: "",
      stderr: "python interpreter not found",
    }
  );
}

async function checkArchitectureLayers(args = {}, cwd = process.cwd()) {
  const paths = supportPaths();
  if (!paths) {
    return {
      ok: false,
      stage: "extension.resolve",
      message: resolveError ?? "architecture layer support files are unavailable",
    };
  }

  const repoPath = normalizeRepoPath(args.repo, cwd);
  const result = await runPythonScript(
    paths.checkerPath,
    ["--repo", repoPath, "--format", "json"],
    { cwd: repoPath },
  );
  const output = result.stdout.trim();
  if (!output) {
    return {
      ok: false,
      stage: "extension.exec",
      message: "check-architecture-layers.py produced no output",
      exitCode: result.code,
      errorText: result.stderr.trim() || null,
    };
  }

  try {
    const payload = JSON.parse(output);
    return {
      ok: result.ok && payload.ok === true,
      payload: {
        ...payload,
        checker_path: paths.checkerPath,
        manifest_path: paths.manifestPath,
      },
      exitCode: result.code,
      errorText: result.stderr.trim() || null,
    };
  } catch {
    return {
      ok: false,
      stage: "extension.parse",
      message: "check-architecture-layers.py returned non-JSON output",
      raw: output,
      exitCode: result.code,
      errorText: result.stderr.trim() || null,
    };
  }
}

async function changedFiles(cwd = process.cwd()) {
  const result = await execFileAsync("git", ["diff", "--name-only"], { cwd });
  if (!result.ok) return [];
  const staged = await execFileAsync("git", ["diff", "--cached", "--name-only"], { cwd });
  const names = new Set(
    `${result.stdout}\n${staged.stdout}`
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean),
  );
  return [...names].sort();
}

function layerRelevant(paths) {
  return paths.some((path) => LAYER_AFFECTING_PATH.test(path));
}

function failureFingerprint(payload) {
  return JSON.stringify(payload.findings ?? []);
}

async function logMissingSupportWarning() {
  if (warnedMissingSupport) return;
  warnedMissingSupport = true;
  await session.log(
    `architecture-layers extension unavailable: ${resolveError ?? "support files missing"}`,
    { level: "warning" },
  );
}

const session = await joinSession({
  tools: [
    {
      name: "check_architecture_layers",
      description:
        "Validate the repository's L0-L6 architecture layer manifest against Gradle project dependencies and low-layer external dependency allow-lists.",
      parameters: {
        type: "object",
        properties: {
          repo: {
            type: "string",
            description:
              "Optional path inside the target git repository. Defaults to the active working directory.",
          },
        },
      },
      skipPermission: true,
      handler: async (args, invocation) => {
        const result = await checkArchitectureLayers(args, invocation?.cwd || process.cwd());
        return {
          textResultForLlm: JSON.stringify(result.payload ?? result, null, 2),
          resultType: result.ok ? "success" : "failure",
        };
      },
    },
  ],
  hooks: {
    onSessionStart: async () => {
      warnedMissingSupport = false;
      lastFailureFingerprint = null;
      const paths = supportPaths();
      if (!paths) {
        await logMissingSupportWarning();
        return {};
      }
      await session.log("architecture-layers extension ready", { ephemeral: true });
      return {
        additionalContext:
          "Native architecture checker available: `check_architecture_layers`. " +
          `It validates \`${MANIFEST_RELATIVE}\` with \`${CHECKER_RELATIVE}\`. ` +
          "Run it after changing Gradle project dependencies, architecture instructions, or layer policy.",
      };
    },
    onPostToolUse: async (input) => {
      if (!MUTATING_TOOLS.has(input.toolName)) return;
      const resultType = input.toolResult?.resultType;
      if (resultType && resultType !== "success") return;

      const cwd = input.cwd || process.cwd();
      const files = await changedFiles(cwd);
      if (!layerRelevant(files)) return;

      const result = await checkArchitectureLayers({}, cwd);
      if (!result.ok) {
        if (!result.payload) {
          await logMissingSupportWarning();
          return;
        }
        const fingerprint = failureFingerprint(result.payload);
        if (fingerprint === lastFailureFingerprint) return;
        lastFailureFingerprint = fingerprint;
        return {
          additionalContext:
            "Architecture layer check is failing for the current diff. " +
            "Run `check_architecture_layers` and fix the manifest or Gradle dependency direction before finishing.",
        };
      }
      lastFailureFingerprint = null;
    },
  },
});
