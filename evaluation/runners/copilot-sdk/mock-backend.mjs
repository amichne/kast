import crypto from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, relative, resolve } from "node:path";

const MOCK_ERROR_CODE = -32044;
const PATH_KEYS = new Set([
  "file",
  "filePath",
  "targetFile",
  "contentFile",
  "logFile",
  "workspaceRoot",
]);
const PATH_LIST_KEYS = new Set([
  "affectedFiles",
  "createdFiles",
  "deletedFiles",
  "expectedFiles",
  "decoyFiles",
  "files",
  "filePaths",
  "sourceRoots",
  "refreshedFiles",
  "removedFiles",
]);

function readJson(path) {
  if (!path || !existsSync(path)) {
    return {};
  }
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function isPathLike(value) {
  return typeof value === "string" && value.includes("/") && !/^[a-z][a-z0-9+.-]*:\/\//i.test(value);
}

function toWorkspaceRelative(value, worktreePath) {
  if (typeof value !== "string" || !value) return value;
  if (value === "." || value === "$WORKSPACE") return "";
  if (isAbsolute(value)) {
    const rel = relative(worktreePath, value);
    return rel.startsWith("..") ? value : rel;
  }
  return value;
}

function materializePath(value, worktreePath) {
  if (typeof value !== "string" || !value) return value;
  if (value === "." || value === "$WORKSPACE") return worktreePath;
  return isAbsolute(value) ? value : resolve(worktreePath, value);
}

function isWithinWorktree(filePath, worktreePath) {
  const resolvedFile = resolve(filePath);
  const resolvedWorktree = resolve(worktreePath);
  const rel = relative(resolvedWorktree, resolvedFile);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

function addDeclaredFile(files, value, worktreePath) {
  if (typeof value === "string" && value.trim()) {
    files.add(resolve(materializePath(value, worktreePath)));
  }
}

function addDeclaredFiles(files, values, worktreePath) {
  if (Array.isArray(values)) {
    for (const value of values) {
      addDeclaredFile(files, value, worktreePath);
    }
  }
}

function collectSlotFiles(files, slot, worktreePath) {
  if (!slot || typeof slot !== "object") return;
  addDeclaredFile(files, slot.file, worktreePath);
  addDeclaredFile(files, slot.filePath, worktreePath);
  const expected = slot.expected && typeof slot.expected === "object" ? slot.expected : {};
  for (const key of ["affectedFiles", "expectedFiles", "expectedConsumerFiles", "createdFiles"]) {
    addDeclaredFiles(files, expected[key], worktreePath);
  }
  if (Array.isArray(expected.implementations)) {
    for (const implementation of expected.implementations) {
      collectSlotFiles(files, implementation, worktreePath);
    }
  }
}

function declaredMutationFiles(bindings, worktreePath) {
  const files = new Set();
  const slots = bindings?.slots && typeof bindings.slots === "object" ? bindings.slots : {};
  for (const slot of Object.values(slots)) {
    collectSlotFiles(files, slot, worktreePath);
  }
  return files;
}

function materializeValue(value, key, worktreePath) {
  if (Array.isArray(value)) {
    return value.map((item) =>
      PATH_LIST_KEYS.has(key) || (typeof item === "string" && isPathLike(item))
        ? materializePath(item, worktreePath)
        : materializeValue(item, key, worktreePath),
    );
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([childKey, childValue]) => [
        childKey,
        materializeValue(childValue, childKey, worktreePath),
      ]),
    );
  }
  if (PATH_KEYS.has(key) || (typeof value === "string" && isPathLike(value))) {
    return materializePath(value, worktreePath);
  }
  return value;
}

function normalizeMatcherValue(value, worktreePath, key) {
  if (typeof value !== "string") return value;
  if (key === "kind") return value.toLowerCase();
  return toWorkspaceRelative(value, worktreePath);
}

function matchesEntry(entry, method, params, worktreePath) {
  if (entry?.method !== method) return false;
  const matcher = entry.matcher ?? {};
  if (matcher.type === "any") return true;
  for (const [key, expected] of Object.entries(matcher)) {
    if (key === "type") continue;
    const actual = normalizeMatcherValue(params?.[key], worktreePath, key);
    const normalizedExpected = normalizeMatcherValue(expected, worktreePath, key);
    if (normalizedExpected !== actual) return false;
  }
  return true;
}

function jsonRpcResult(result) {
  return `${JSON.stringify({ jsonrpc: "2.0", id: 1, result })}\n`;
}

function jsonRpcError(message, data = {}) {
  return `${JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    error: {
      code: MOCK_ERROR_CODE,
      message,
      data,
    },
  })}\n`;
}

function cleanDiagnostics() {
  return {
    clean: true,
    errorCount: 0,
    warningCount: 0,
    errors: [],
  };
}

function textLocation(filePath, content, token) {
  const offset = token ? content.indexOf(token) : 0;
  const safeOffset = offset >= 0 ? offset : 0;
  const before = content.slice(0, safeOffset);
  const line = before.split(/\r?\n/).length;
  const lastBreak = Math.max(before.lastIndexOf("\n"), before.lastIndexOf("\r"));
  const column = safeOffset - lastBreak;
  const preview = content.split(/\r?\n/)[line - 1] ?? "";
  return {
    filePath,
    startOffset: safeOffset,
    endOffset: safeOffset + (token?.length ?? 0),
    startLine: line,
    startColumn: column,
    preview,
  };
}

function renameTarget(bindings) {
  return bindings?.slots?.RENAME_TARGET ?? null;
}

function expectedRenameFiles(target) {
  const expected = target?.expected?.affectedFiles;
  if (Array.isArray(expected) && expected.length > 0) return expected;
  return target?.file ? [target.file] : [];
}

function controlledRename({ params, bindings, worktreePath }) {
  const target = renameTarget(bindings);
  if (!target) {
    return { error: "Mock rename requires RENAME_TARGET bindings." };
  }
  const oldName = String(params.symbol ?? target.symbol ?? "").trim();
  const newName = String(params.newName ?? target.newName ?? "").trim();
  if (!oldName || !newName) {
    return { error: "Mock rename requires symbol and newName." };
  }
  const files = expectedRenameFiles(target).map((file) => materializePath(file, worktreePath));
  const applied = [];
  const affectedFiles = [];
  for (const filePath of files) {
    if (!isWithinWorktree(filePath, worktreePath)) {
      return { error: `Mock rename refused to edit outside the worktree: ${filePath}` };
    }
    if (!existsSync(filePath)) {
      return { error: `Mock rename expected file does not exist: ${filePath}` };
    }
    const before = readFileSync(filePath, "utf8");
    const matcher = new RegExp(`\\b${escapeRegExp(oldName)}\\b`, "g");
    const after = before.replace(matcher, newName);
    if (after !== before) {
      const firstOffset = before.search(matcher);
      writeFileSync(filePath, after);
      applied.push({
        filePath,
        startOffset: firstOffset >= 0 ? firstOffset : 0,
        endOffset: firstOffset >= 0 ? firstOffset + oldName.length : 0,
        newText: newName,
      });
      affectedFiles.push(filePath);
    }
  }
  const primaryFile = materializePath(target.file ?? files[0], worktreePath);
  const location = existsSync(primaryFile)
    ? textLocation(primaryFile, readFileSync(primaryFile, "utf8"), newName)
    : { filePath: primaryFile, startOffset: 0, endOffset: 0, startLine: 1, startColumn: 1, preview: "" };
  return {
    result: {
      type: "RENAME_SUCCESS",
      ok: true,
      query: {
        type: params.type ?? "RENAME_BY_SYMBOL_REQUEST",
        workspaceRoot: worktreePath,
        symbol: oldName,
        newName,
        fileHint: params.fileHint ?? null,
        kind: params.kind ?? null,
        containingType: params.containingType ?? target.containingType ?? null,
        filePath: primaryFile,
        offset: location.startOffset,
      },
      editCount: applied.length,
      affectedFiles,
      applyResult: {
        applied,
        affectedFiles,
        createdFiles: [],
        deletedFiles: [],
        schemaVersion: 1,
      },
      diagnostics: cleanDiagnostics(),
      logFile: resolve(worktreePath, ".kast/mock-backend.log"),
    },
  };
}

function contentFromParams(params, worktreePath) {
  if (typeof params.content === "string") return params.content;
  const contentFile = materializePath(params.contentFile, worktreePath);
  if (typeof contentFile === "string" && existsSync(contentFile)) {
    return readFileSync(contentFile, "utf8");
  }
  return "";
}

function controlledWriteAndValidate({ params, bindings, worktreePath }) {
  const filePath = materializePath(params.filePath, worktreePath);
  if (!filePath || !existsSync(dirname(filePath))) {
    return { error: `Mock write-and-validate cannot access file directory: ${filePath}` };
  }
  const resolvedFilePath = resolve(filePath);
  if (!isWithinWorktree(resolvedFilePath, worktreePath)) {
    return { error: `Mock write-and-validate refused to edit outside the worktree: ${resolvedFilePath}` };
  }
  if (!declaredMutationFiles(bindings, worktreePath).has(resolvedFilePath)) {
    return { error: `Mock write-and-validate refused undeclared binding file: ${resolvedFilePath}` };
  }
  const before = existsSync(filePath) ? readFileSync(filePath, "utf8") : "";
  const content = contentFromParams(params, worktreePath);
  let after = before;
  let startOffset = 0;
  let endOffset = 0;
  if (params.type === "CREATE_FILE_REQUEST") {
    after = content;
  } else if (params.type === "INSERT_AT_OFFSET_REQUEST") {
    startOffset = Number(params.offset ?? 0);
    endOffset = startOffset;
    after = before.slice(0, startOffset) + content + before.slice(startOffset);
  } else if (params.type === "REPLACE_RANGE_REQUEST") {
    startOffset = Number(params.startOffset ?? 0);
    endOffset = Number(params.endOffset ?? startOffset);
    after = before.slice(0, startOffset) + content + before.slice(endOffset);
  } else {
    return { error: `Unsupported mock write-and-validate request type: ${params.type}` };
  }
  writeFileSync(filePath, after);
  return {
    result: {
      type: "WRITE_AND_VALIDATE_SUCCESS",
      ok: true,
      query: {
        type: params.type,
        workspaceRoot: worktreePath,
        filePath,
        ...(params.type === "INSERT_AT_OFFSET_REQUEST" ? { offset: startOffset } : {}),
        ...(params.type === "REPLACE_RANGE_REQUEST" ? { startOffset, endOffset } : {}),
      },
      appliedEdits: before === after ? 0 : 1,
      importChanges: 0,
      diagnostics: cleanDiagnostics(),
      message: "Mock backend applied the requested edit and reported clean diagnostics.",
      logFile: resolve(worktreePath, ".kast/mock-backend.log"),
    },
  };
}

function diagnosticsResponse(params, worktreePath) {
  const filePaths = Array.isArray(params.filePaths) ? params.filePaths.map((file) => materializePath(file, worktreePath)) : [];
  return {
    type: "DIAGNOSTICS_SUCCESS",
    ok: true,
    query: { workspaceRoot: worktreePath, filePaths },
    clean: true,
    errorCount: 0,
    warningCount: 0,
    infoCount: 0,
    diagnostics: [],
    logFile: resolve(worktreePath, ".kast/mock-backend.log"),
  };
}

export function createMockKastCaller({ payloadPath, worktreePath, bindings = {} }) {
  const payload = readJson(payloadPath);
  const entries = Array.isArray(payload.entries) ? payload.entries : [];
  const payloadText = payloadPath && existsSync(payloadPath) ? readFileSync(payloadPath, "utf8") : "";
  const misses = [];
  const errors = [];
  const metadata = {
    backend_mode: "mock",
    payload_path: payloadPath ? resolve(payloadPath) : null,
    payload_hash: payloadText ? sha256(payloadText) : null,
    payload_entry_count: entries.length,
    provenance_summary: payload.provenance_summary ?? {},
    misses,
    errors,
  };

  const recordError = (method, params, message) => {
    const entry = { method, params, message };
    misses.push(entry);
    errors.push(entry);
    return jsonRpcError(message, { method, params });
  };

  return {
    metadata: () => ({ ...metadata, misses: [...misses], errors: [...errors] }),
    async call(method, params = {}) {
      if (method === "symbol/rename") {
        const outcome = controlledRename({ params, bindings, worktreePath });
        return outcome.error ? recordError(method, params, outcome.error) : jsonRpcResult(outcome.result);
      }
      if (method === "symbol/write-and-validate") {
        const outcome = controlledWriteAndValidate({ params, bindings, worktreePath });
        return outcome.error ? recordError(method, params, outcome.error) : jsonRpcResult(outcome.result);
      }
      if (method === "raw/diagnostics") {
        return jsonRpcResult(diagnosticsResponse(params, worktreePath));
      }

      const entry = entries.find((candidate) => matchesEntry(candidate, method, params, worktreePath));
      if (!entry) {
        return recordError(method, params, `No mock payload matched ${method}.`);
      }
      let result = materializeValue(entry.result, "result", worktreePath);
      if (method === "raw/workspace-files" && result?.ok && Array.isArray(result.modules)) {
        result = {
          ...result,
          query: {
            ...(result.query ?? {}),
            workspaceRoot: worktreePath,
            includeFiles: Boolean(params.includeFiles),
            moduleName: params.moduleName ?? null,
            maxFilesPerModule: params.maxFilesPerModule ?? null,
          },
          modules: result.modules
            .filter((module) => !params.moduleName || module.name === params.moduleName)
            .map((module) => {
              const allFiles = Array.isArray(module.files) ? module.files : [];
              const max = Number.isInteger(params.maxFilesPerModule) ? params.maxFilesPerModule : allFiles.length;
              return {
                ...module,
                files: params.includeFiles ? allFiles.slice(0, max) : [],
                filesTruncated: Boolean(params.includeFiles && allFiles.length > max),
              };
            }),
        };
      }
      return jsonRpcResult(result);
    },
  };
}

export const MOCK_KAST_ERROR_CODE = MOCK_ERROR_CODE;
