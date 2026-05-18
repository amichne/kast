#!/usr/bin/env node
import assert from "node:assert/strict";
import { summarizeSessionEvents } from "../run-artifacts.mjs";

const runStartedAtMs = Date.parse("2026-01-01T00:00:00.000Z");
const events = [
  {
    type: "assistant.message_delta",
    timestamp: "2026-01-01T00:00:01.000Z",
    data: { delta: "Thinking" },
  },
  {
    type: "tool.execution_start",
    timestamp: "2026-01-01T00:00:02.000Z",
    data: {
      toolCallId: "tool-1",
      toolName: "bash",
      arguments: {
        command: "./gradlew test --offline",
      },
    },
  },
  {
    type: "permission.requested",
    timestamp: "2026-01-01T00:00:02.100Z",
    data: {
      requestId: "perm-1",
      permissionRequest: {
        kind: "shell",
        fullCommandText: "./gradlew test --offline",
      },
    },
  },
  {
    type: "permission.completed",
    timestamp: "2026-01-01T00:00:02.200Z",
    data: {
      requestId: "perm-1",
      result: { kind: "approved" },
      toolCallId: "tool-1",
    },
  },
  {
    type: "tool.execution_complete",
    timestamp: "2026-01-01T00:00:05.000Z",
    data: {
      toolCallId: "tool-1",
      toolName: "bash",
      success: true,
      result: {
        content: "BUILD SUCCESSFUL",
        detailedContent: "BUILD SUCCESSFUL in 3s",
        contents: [
          {
            type: "terminal",
            text: "BUILD SUCCESSFUL",
            exitCode: 0,
            cwd: "/tmp/worktree",
          },
        ],
      },
    },
  },
  {
    type: "assistant.usage",
    timestamp: "2026-01-01T00:00:06.000Z",
    data: {
      model: "gpt-5-mini",
      inputTokens: 11,
      outputTokens: 7,
      reasoningTokens: 3,
      cacheReadTokens: 2,
      cacheWriteTokens: 1,
    },
  },
  {
    type: "session.usage_info",
    timestamp: "2026-01-01T00:00:06.100Z",
    data: {
      currentTokens: 120,
      tokenLimit: 200000,
      conversationTokens: 80,
      systemTokens: 20,
      toolDefinitionsTokens: 20,
      messagesLength: 4,
    },
  },
  {
    type: "session.compaction_complete",
    timestamp: "2026-01-01T00:00:06.200Z",
    data: {
      success: true,
      tokensRemoved: 40,
      compactionTokensUsed: {
        inputTokens: 10,
        outputTokens: 4,
        reasoningTokens: 1,
      },
    },
  },
  {
    type: "assistant.message",
    timestamp: "2026-01-01T00:00:07.000Z",
    data: {
      content: "Done.",
    },
  },
  {
    type: "session.idle",
    timestamp: "2026-01-01T00:00:08.000Z",
    data: {},
  },
];

const metrics = summarizeSessionEvents({ events, runStartedAtMs });

assert.equal(metrics.timing.time_to_first_assistant_delta_ms, 1000);
assert.equal(metrics.timing.time_to_first_tool_call_ms, 2000);
assert.equal(metrics.timing.time_to_final_answer_ms, 7000);
assert.equal(metrics.tokens.input_tokens.value, 11);
assert.equal(metrics.tokens.output_tokens.value, 7);
assert.equal(metrics.tokens.reasoning_tokens.value, 3);
assert.equal(metrics.tokens.total_tokens.value, 24);
assert.equal(metrics.tokens.context_window.current_tokens, 120);
assert.equal(metrics.tokens.compaction_count, 1);
assert.equal(metrics.tokens.tokens_removed_by_compaction, 40);
assert.equal(metrics.tools.total_tool_calls, 1);
assert.equal(metrics.tools.shell_calls, 1);
assert.equal(metrics.tools.tool_result_truncations, 1);
assert.equal(metrics.permissions.total_requests, 1);
assert.equal(metrics.permissions.approved, 1);
assert.equal(metrics.build_test_iterations.total_invocations, 1);
assert.equal(metrics.build_test_iterations.failed_invocations, 0);
assert.equal(metrics.build_test_iterations.final_status, "passed");
assert.equal(metrics.build_test_iterations.commands[0].command, "./gradlew test --offline");

console.log("All run artifact tests passed.");
