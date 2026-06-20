import { randomUUID } from "node:crypto";
import { appendFileSync, realpathSync } from "node:fs";
import { resolve } from "node:path";

const TRUE_VALUES = new Set(["1", "true", "yes", "on"]);

function truthy(value) {
  return TRUE_VALUES.has(String(value ?? "").trim().toLowerCase());
}

function canonicalPathOrNull(path) {
  if (!path || typeof path !== "string") return null;
  try {
    return realpathSync.native(resolve(path));
  } catch {
    return resolve(path);
  }
}

function normalizeTraceValue(value) {
  if (value == null) return null;
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(normalizeTraceValue);
  }
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, nested]) => [key, normalizeTraceValue(nested)]),
    );
  }
  return String(value);
}

function firstString(...values) {
  return values.find((value) => typeof value === "string" && value.length > 0) ?? null;
}

export function traceFieldsFromParams(params = {}) {
  const targetFilePath = firstString(
    params.filePath,
    params.targetFilePath,
    params.position?.filePath,
    params.edits?.[0]?.filePath,
    params.fileOperations?.[0]?.filePath,
  );
  return {
    targetFilePath,
    canonicalTargetFilePath: canonicalPathOrNull(targetFilePath),
    moduleName: firstString(params.moduleName),
    gradleProjectPath: firstString(params.gradleProjectPath, params.projectPath),
  };
}

export function createTraceEmitter({
  env = process.env,
  repoRoot = process.cwd(),
  processId = process.pid,
  now = () => new Date().toISOString(),
  idFactory = randomUUID,
  appendFile = appendFileSync,
} = {}) {
  const traceFile = String(env.KAST_COPILOT_TRACE_FILE ?? "").trim();
  const enabled = truthy(env.KAST_COPILOT_TRACE) || traceFile.length > 0;
  const extensionInstanceId = idFactory();
  const canonicalWorkspaceRoot = canonicalPathOrNull(repoRoot);
  const pending = [];
  let sequence = 0;
  let sessionLog = null;

  function record(eventName, fields = {}) {
    return {
      type: "kast.copilot.trace",
      schemaVersion: 1,
      sequence: ++sequence,
      timestamp: now(),
      eventName,
      invocationId: fields.invocationId ?? null,
      parentInvocationId: fields.parentInvocationId ?? null,
      agentRole: fields.agentRole ?? null,
      agentInstanceId: fields.agentInstanceId ?? extensionInstanceId,
      reviewInvocationId: fields.reviewInvocationId ?? null,
      workspaceId: fields.workspaceId ?? canonicalWorkspaceRoot,
      workspaceRoot: fields.workspaceRoot ?? repoRoot,
      canonicalWorkspaceRoot,
      ideaProjectName: fields.ideaProjectName ?? null,
      ideaProjectBasePath: fields.ideaProjectBasePath ?? null,
      processId,
      threadName: fields.threadName ?? "node-main",
      sdkRegistrationScope: fields.sdkRegistrationScope ?? null,
      targetFilePath: fields.targetFilePath ?? null,
      canonicalTargetFilePath: fields.canonicalTargetFilePath ?? canonicalPathOrNull(fields.targetFilePath),
      moduleName: fields.moduleName ?? null,
      gradleProjectPath: fields.gradleProjectPath ?? null,
      outcome: fields.outcome ?? null,
      detail: normalizeTraceValue(fields.detail ?? null),
    };
  }

  function write(recordValue) {
    if (traceFile) {
      try {
        appendFile(traceFile, `${JSON.stringify(recordValue)}\n`, "utf8");
      } catch {
        // Trace output must not change extension behavior.
      }
    }
    if (sessionLog) {
      void sessionLog(JSON.stringify(recordValue), { ephemeral: true, level: "debug" }).catch(() => {});
    } else {
      pending.push(recordValue);
    }
  }

  return {
    enabled,
    extensionInstanceId,
    emit(eventName, fields = {}) {
      if (!enabled) return null;
      const next = record(eventName, fields);
      write(next);
      return next;
    },
    attachSession(session) {
      if (!enabled) return;
      sessionLog = (message, options) => session.log(message, options);
      while (pending.length > 0) {
        const next = pending.shift();
        void sessionLog(JSON.stringify(next), { ephemeral: true, level: "debug" }).catch(() => {});
      }
    },
  };
}
