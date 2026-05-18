#!/usr/bin/env node
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { mkdir, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { createMockKastCaller } from "../mock-backend.mjs";

const scratch = mkdtempSync(join(tmpdir(), "kast-mock-backend-"));

try {
  const worktree = join(scratch, "worktree");
  await mkdir(join(worktree, "src"), { recursive: true });
  writeFileSync(join(worktree, "src", "Demo.kt"), "package sample\n\nclass Demo\n");

  const payloadPath = join(scratch, "mock-payloads.json");
  writeFileSync(
    payloadPath,
    JSON.stringify(
      {
        $schema: "https://github.com/amichne/kast/evaluation/mock-backend.schema.json",
        schema_version: 1,
        target_repo: "sample",
        entries: [
          {
            method: "symbol/resolve",
            matcher: { symbol: "Demo" },
            result: {
              type: "RESOLVE_SUCCESS",
              ok: true,
              query: { workspaceRoot: ".", symbol: "Demo" },
              symbol: {
                fqName: "sample.Demo",
                kind: "CLASS",
                location: {
                  filePath: "src/Demo.kt",
                  startOffset: 16,
                  endOffset: 20,
                  startLine: 3,
                  startColumn: 7,
                  preview: "class Demo",
                },
              },
              filePath: "src/Demo.kt",
              offset: 16,
              candidate: { line: 3, column: 7, context: "class Demo" },
              logFile: ".kast/mock.log",
            },
            provenance: { source: "history", fallback: false },
          },
        ],
      },
      null,
      2,
    ) + "\n",
  );

  const caller = createMockKastCaller({
    payloadPath,
    worktreePath: worktree,
    bindings: {},
  });
  const resolveEnvelope = JSON.parse(await caller.call("symbol/resolve", { symbol: "Demo" }));
  assert.equal(resolveEnvelope.jsonrpc, "2.0");
  assert.equal(resolveEnvelope.id, 1);
  assert.equal(resolveEnvelope.result.ok, true);
  assert.equal(resolveEnvelope.result.filePath, resolve(worktree, "src/Demo.kt"));
  assert.equal(resolveEnvelope.result.symbol.location.filePath, resolve(worktree, "src/Demo.kt"));
  assert.equal(caller.metadata().backend_mode, "mock");
  assert.equal(caller.metadata().payload_entry_count, 1);

  const renameWorktree = join(scratch, "rename-worktree");
  await mkdir(join(renameWorktree, "src"), { recursive: true });
  writeFileSync(
    join(renameWorktree, "src", "AnalysisDispatcher.kt"),
    "package sample\n\nclass AnalysisDispatcher\nfun use(value: AnalysisDispatcher) = value\n",
  );
  const mutationCaller = createMockKastCaller({
    payloadPath,
    worktreePath: renameWorktree,
    bindings: {
      slots: {
        RENAME_TARGET: {
          symbol: "AnalysisDispatcher",
          newName: "RpcAnalysisDispatcher",
          file: "src/AnalysisDispatcher.kt",
          expected: {
            affectedFiles: ["src/AnalysisDispatcher.kt"],
          },
        },
      },
    },
  });
  const renameEnvelope = JSON.parse(
    await mutationCaller.call("symbol/rename", {
      type: "RENAME_BY_SYMBOL_REQUEST",
      symbol: "AnalysisDispatcher",
      newName: "RpcAnalysisDispatcher",
    }),
  );
  assert.equal(renameEnvelope.result.ok, true);
  assert.deepEqual(renameEnvelope.result.affectedFiles, [resolve(renameWorktree, "src/AnalysisDispatcher.kt")]);
  assert.match(readFileSync(join(renameWorktree, "src", "AnalysisDispatcher.kt"), "utf8"), /RpcAnalysisDispatcher/);

  const writeEnvelope = JSON.parse(
    await mutationCaller.call("symbol/write-and-validate", {
      type: "INSERT_AT_OFFSET_REQUEST",
      filePath: resolve(renameWorktree, "src/AnalysisDispatcher.kt"),
      offset: 0,
      content: "@Deprecated(\"Use RpcAnalysisDispatcher instead\")\n",
    }),
  );
  assert.equal(writeEnvelope.result.ok, true);
  assert.equal(writeEnvelope.result.diagnostics.clean, true);
  assert.match(
    readFileSync(join(renameWorktree, "src", "AnalysisDispatcher.kt"), "utf8"),
    /^@Deprecated\("Use RpcAnalysisDispatcher instead"\)/,
  );

  const undeclaredWriteEnvelope = JSON.parse(
    await mutationCaller.call("symbol/write-and-validate", {
      type: "CREATE_FILE_REQUEST",
      filePath: resolve(renameWorktree, "src", "Undeclared.kt"),
      content: "package sample\n\nclass Undeclared\n",
    }),
  );
  assert.equal(undeclaredWriteEnvelope.error.code, -32044);

  const missEnvelope = JSON.parse(await caller.call("symbol/resolve", { symbol: "Missing" }));
  assert.equal(missEnvelope.error.code, -32044);
  assert.equal(caller.metadata().misses.length, 1);
} finally {
  await rm(scratch, { recursive: true, force: true });
}

console.log("All mock backend tests passed.");
