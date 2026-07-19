import { execFile } from "node:child_process";
import { accessSync, constants, existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { joinSession } from "@github/copilot-sdk/extension";
import { createTraceEmitter } from "./_shared/kast-trace.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT_MARKERS = ["settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle"];

function hasRepoMarker(path) {
  return existsSync(resolve(path, ".github")) && ROOT_MARKERS.some((marker) => existsSync(resolve(path, marker)));
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
  if (process.env.KAST_EXTENSION_REPO_ROOT) return resolve(process.env.KAST_EXTENSION_REPO_ROOT);
  return resolve(process.cwd());
})();

const trace = createTraceEmitter({ repoRoot: REPO_ROOT });
trace.emit("copilot.extension.bootstrap", {
  sdkRegistrationScope: "extension-session",
  detail: { repoRoot: REPO_ROOT, traceEnabled: trace.enabled },
});

function readJsonFile(filePath) {
  try {
    return JSON.parse(readFileSync(filePath, "utf8"));
  } catch {
    return null;
  }
}

function isExecutable(filePath) {
  try {
    accessSync(filePath, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function execCommand(command, args, sessionId) {
  return new Promise((resolveResult) => {
    execFile(
      command,
      args,
      {
        cwd: REPO_ROOT,
        env: { ...process.env, KAST_AGENT_SESSION_ID: sessionId },
        maxBuffer: 32 * 1024 * 1024,
      },
      (error, stdout, stderr) => {
        resolveResult({
          ok: !error,
          code: error?.code ?? 0,
          stdout: String(stdout ?? "").trim(),
          stderr: String(stderr ?? "").trim(),
        });
      },
    );
  });
}

let taskLauncher = null;
let launcherResolutionError = null;

function launcherCandidates() {
  const configured = process.env.KAST_AGENT_TASK_LAUNCHER;
  if (configured) {
    if (!isAbsolute(configured)) {
      launcherResolutionError = "KAST_AGENT_TASK_LAUNCHER must be an absolute path";
      return [];
    }
    return [configured];
  }

  const installRoot = process.env.KAST_INSTALL_ROOT ?? join(homedir(), ".local", "share", "kast");
  const installManifest = readJsonFile(join(installRoot, "install.json"));
  return [
    installManifest?.entrypoints?.taskLauncher,
    join(homedir(), ".local", "bin", "kast-agent-task"),
  ].filter((candidate, index, candidates) =>
    typeof candidate === "string" && candidate.length > 0 && candidates.indexOf(candidate) === index,
  );
}

function resolveTaskLauncher() {
  if (taskLauncher) return taskLauncher;
  const rejected = [];
  for (const candidate of launcherCandidates()) {
    if (!isAbsolute(candidate) || !isExecutable(candidate)) {
      rejected.push(candidate);
      continue;
    }
    const siblingKast = join(dirname(candidate), "kast");
    if (!isExecutable(siblingKast)) {
      rejected.push(`${candidate} (missing executable sibling kast)`);
      continue;
    }
    taskLauncher = candidate;
    trace.emit("copilot.task_launcher.resolved", {
      sdkRegistrationScope: "extension-session",
      detail: { taskLauncher, siblingKast, rejected },
    });
    return taskLauncher;
  }

  launcherResolutionError ??= rejected.length > 0
    ? `no attested kast-agent-task launcher pair; rejected: ${rejected.join(", ")}`
    : "no attested kast-agent-task launcher was configured or installed";
  trace.emit("copilot.task_launcher.resolve_failed", {
    sdkRegistrationScope: "extension-session",
    outcome: "failed",
    detail: { rejected, error: launcherResolutionError },
  });
  return null;
}

function lifecycleContext(operation, result) {
  const evidence = [result.stdout, result.stderr].filter(Boolean).join("\n");
  if (result.ok) return evidence || `Kast agent task ${operation} completed.`;
  return `Kast agent task ${operation} failed${evidence ? `:\n${evidence}` : "."}`;
}

async function runLifecycle(operation, input, invocation) {
  const launcher = resolveTaskLauncher();
  const sessionId = invocation?.sessionId;
  const result = !sessionId
    ? { ok: false, code: 2, stdout: "", stderr: "Copilot hook invocation has no session ID" }
    : launcher
      ? await execCommand(launcher, [operation], sessionId)
      : { ok: false, code: 127, stdout: "", stderr: launcherResolutionError };
  trace.emit(`copilot.task.${operation}`, {
    invocationId: invocation?.sessionId,
    sdkRegistrationScope: "extension-session",
    outcome: result.ok ? "completed" : "failed",
    detail: {
      toolName: input?.toolName,
      exitCode: result.code,
      stdout: result.stdout,
      stderr: result.stderr,
    },
  });
  return result;
}

let session;
const hooks = {
  onSessionStart: async (input, invocation) => {
    const result = await runLifecycle("begin", input, invocation);
    return { additionalContext: lifecycleContext("begin", result) };
  },
  onPreToolUse: async (input, invocation) => {
    const result = await runLifecycle("status", input, invocation);
    return { additionalContext: lifecycleContext("status", result) };
  },
  onPostToolUse: async (input, invocation) => {
    const result = await runLifecycle("status", input, invocation);
    return { additionalContext: lifecycleContext("status", result) };
  },
  onPostToolUseFailure: async (input, invocation) => {
    const result = await runLifecycle("status", input, invocation);
    return { additionalContext: lifecycleContext("status", result) };
  },
  onSessionEnd: async (input, invocation) => {
    const result = await runLifecycle("status", input, invocation);
    await session?.log(`kast extension audit: ${lifecycleContext("status", result)}`, {
      level: result.ok ? "info" : "warning",
      ephemeral: result.ok,
    });
    return null;
  },
};

trace.emit("copilot.session.join_requested", {
  sdkRegistrationScope: "extension-session",
  detail: { tools: [], lifecycle: "kast-agent-task" },
});

session = await joinSession({
  tools: [],
  hooks,
  disabledSkills: ["kast"],
});
trace.attachSession(session);
trace.emit("copilot.session.joined", {
  sdkRegistrationScope: "extension-session",
  outcome: "completed",
  detail: { tools: [], lifecycle: "kast-agent-task" },
});

const launcher = resolveTaskLauncher();
if (!launcher) {
  await session.log(
    `kast extension: ${launcherResolutionError}. Install or repair Kast's attested launcher pair and reload the Copilot session.`,
    { level: "warning" },
  );
} else {
  await session.log(`kast extension ready (task launcher: ${launcher})`, { ephemeral: true });
}
