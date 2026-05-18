import crypto from "node:crypto";

const SHELL_TOOL_NAMES = new Set(["bash", "shell", "sh", "powershell", "zsh"]);
const FILE_READ_TOOL_NAMES = new Set(["view", "read_bash", "read_file", "glob"]);
const FILE_WRITE_TOOL_NAMES = new Set(["edit", "write", "create", "apply_patch", "write_bash"]);
const GENERIC_SEARCH_TOOL_NAMES = new Set(["rg", "grep", "glob", "find", "ls"]);
const BUILD_TEST_COMMAND_RE =
  /\b(?:gradlew|gradle|mvn|pytest|go test|cargo test|npm test|pnpm test|yarn test|build|compile|test)\b/i;

function toTimestampMs(value) {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function metricValue(value, source, reason) {
  return {
    value: value ?? null,
    source,
    ...(reason ? { reason } : {}),
  };
}

function aggregateUsage(events) {
  const usageEvents = events.filter((event) => event?.type === "assistant.usage" && event.data && typeof event.data === "object");
  const sum = (field) => {
    const values = usageEvents
      .map((event) => event.data?.[field])
      .filter((value) => typeof value === "number");
    return values.length ? values.reduce((total, value) => total + value, 0) : null;
  };
  const input = sum("inputTokens");
  const output = sum("outputTokens");
  const reasoning = sum("reasoningTokens");
  const cacheRead = sum("cacheReadTokens");
  const cacheWrite = sum("cacheWriteTokens");
  const total = [input, output, reasoning, cacheRead, cacheWrite].every((value) => value !== null)
    ? input + output + reasoning + cacheRead + cacheWrite
    : null;
  const perModelUsage = {};
  for (const event of usageEvents) {
    const model = event.data?.model;
    if (typeof model !== "string" || !model) continue;
    if (!perModelUsage[model]) {
      perModelUsage[model] = { inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 };
    }
    for (const [key, metricKey] of [
      ["inputTokens", "inputTokens"],
      ["outputTokens", "outputTokens"],
      ["reasoningTokens", "reasoningTokens"],
      ["cacheReadTokens", "cacheReadTokens"],
      ["cacheWriteTokens", "cacheWriteTokens"],
    ]) {
      if (typeof event.data?.[key] === "number") {
        perModelUsage[model][metricKey] += event.data[key];
      }
    }
  }
  return {
    input_tokens: input !== null ? metricValue(input, "assistant.usage") : metricValue(null, "assistant.usage", "missing"),
    output_tokens: output !== null ? metricValue(output, "assistant.usage") : metricValue(null, "assistant.usage", "missing"),
    reasoning_tokens: reasoning !== null ? metricValue(reasoning, "assistant.usage") : metricValue(null, "assistant.usage", "missing"),
    cache_read_tokens: cacheRead !== null ? metricValue(cacheRead, "assistant.usage") : metricValue(null, "assistant.usage", "missing"),
    cache_write_tokens: cacheWrite !== null ? metricValue(cacheWrite, "assistant.usage") : metricValue(null, "assistant.usage", "missing"),
    total_tokens: total !== null ? metricValue(total, "derived_from_assistant.usage") : metricValue(null, "derived_from_assistant.usage", "missing_component"),
    per_model_usage: perModelUsage,
  };
}

function latestUsageInfo(events) {
  const usageInfoEvents = events.filter((event) => event?.type === "session.usage_info" && event.data && typeof event.data === "object");
  const latest = usageInfoEvents.at(-1)?.data ?? {};
  return {
    current_tokens: typeof latest.currentTokens === "number" ? latest.currentTokens : null,
    token_limit: typeof latest.tokenLimit === "number" ? latest.tokenLimit : null,
    conversation_tokens: typeof latest.conversationTokens === "number" ? latest.conversationTokens : null,
    system_tokens: typeof latest.systemTokens === "number" ? latest.systemTokens : null,
    tool_definition_tokens: typeof latest.toolDefinitionsTokens === "number" ? latest.toolDefinitionsTokens : null,
    messages_length: typeof latest.messagesLength === "number" ? latest.messagesLength : null,
  };
}

function compactionMetrics(events) {
  const complete = events.filter((event) => event?.type === "session.compaction_complete" && event.data && typeof event.data === "object");
  const tokensRemoved = complete
    .map((event) => event.data?.tokensRemoved)
    .filter((value) => typeof value === "number")
    .reduce((total, value) => total + value, 0);
  const compactionTokens = complete
    .map((event) => event.data?.compactionTokensUsed)
    .filter((value) => value && typeof value === "object")
    .reduce(
      (totals, value) => ({
        inputTokens: totals.inputTokens + (typeof value.inputTokens === "number" ? value.inputTokens : 0),
        outputTokens: totals.outputTokens + (typeof value.outputTokens === "number" ? value.outputTokens : 0),
        reasoningTokens: totals.reasoningTokens + (typeof value.reasoningTokens === "number" ? value.reasoningTokens : 0),
      }),
      { inputTokens: 0, outputTokens: 0, reasoningTokens: 0 },
    );
  return {
    compaction_count: complete.length,
    compaction_tokens_used: compactionTokens,
    tokens_removed_by_compaction: tokensRemoved,
  };
}

function collectToolExecutions(events) {
  const executions = new Map();
  const permissionRequests = new Map();
  for (const event of events) {
    if (event?.type === "permission.requested" && event.data && typeof event.data === "object") {
      permissionRequests.set(event.data.requestId, event.data);
    }
    if (event?.type === "tool.execution_start" && event.data && typeof event.data === "object") {
      const timestampMs = toTimestampMs(event.timestamp);
      executions.set(event.data.toolCallId, {
        toolCallId: event.data.toolCallId,
        toolName: event.data.toolName,
        startedAt: event.timestamp ?? null,
        startedAtMs: timestampMs,
        arguments: event.data.arguments ?? {},
      });
    }
    if (event?.type === "tool.execution_complete" && event.data && typeof event.data === "object") {
      const timestampMs = toTimestampMs(event.timestamp);
      const existing = executions.get(event.data.toolCallId) ?? {
        toolCallId: event.data.toolCallId,
        toolName: event.data.toolName,
        arguments: {},
      };
      const terminalContent = Array.isArray(event.data.result?.contents)
        ? event.data.result.contents.find((item) => item?.type === "terminal")
        : null;
      executions.set(event.data.toolCallId, {
        ...existing,
        toolName: event.data.toolName ?? existing.toolName,
        completedAt: event.timestamp ?? null,
        completedAtMs: timestampMs,
        success: Boolean(event.data.success),
        error: event.data.error ?? null,
        result: event.data.result ?? null,
        exitCode: typeof terminalContent?.exitCode === "number" ? terminalContent.exitCode : null,
      });
    }
  }
  const executionList = [...executions.values()].sort((left, right) => (left.startedAtMs ?? 0) - (right.startedAtMs ?? 0));
  const perTool = executionList.map((execution) => ({
    tool_name: execution.toolName ?? "",
    tool_call_id: execution.toolCallId,
    started_at: execution.startedAt ?? null,
    completed_at: execution.completedAt ?? null,
    duration_ms:
      typeof execution.startedAtMs === "number" && typeof execution.completedAtMs === "number"
        ? execution.completedAtMs - execution.startedAtMs
        : null,
    success: execution.success ?? null,
  }));
  const toolNames = executionList.map((execution) => String(execution.toolName ?? ""));
  const toolResultTruncations = executionList.filter((execution) => {
    const result = execution.result;
    return result?.detailedContent && result?.content && result.detailedContent !== result.content;
  }).length;
  const buildTestCommands = executionList
    .filter((execution) => SHELL_TOOL_NAMES.has(String(execution.toolName ?? "")))
    .map((execution) => {
      const command = typeof execution.arguments?.command === "string"
        ? execution.arguments.command
        : [...permissionRequests.values()].find((request) => request.toolCallId === execution.toolCallId)?.permissionRequest?.fullCommandText ?? "";
      return {
        tool_call_id: execution.toolCallId,
        command,
        started_at: execution.startedAt ?? null,
        completed_at: execution.completedAt ?? null,
        exit_code: execution.exitCode,
        duration_ms:
          typeof execution.startedAtMs === "number" && typeof execution.completedAtMs === "number"
            ? execution.completedAtMs - execution.startedAtMs
            : null,
      };
    })
    .filter((command) => BUILD_TEST_COMMAND_RE.test(command.command));
  return {
    executionList,
    perTool,
    tools: {
      total_tool_calls: executionList.length,
      kast_tool_calls: toolNames.filter((name) => name.startsWith("kast_")).length,
      builtin_file_reads: toolNames.filter((name) => FILE_READ_TOOL_NAMES.has(name)).length,
      builtin_file_writes: toolNames.filter((name) => FILE_WRITE_TOOL_NAMES.has(name)).length,
      shell_calls: toolNames.filter((name) => SHELL_TOOL_NAMES.has(name)).length,
      generic_search_calls: toolNames.filter((name) => GENERIC_SEARCH_TOOL_NAMES.has(name)).length,
      failed_tool_calls: executionList.filter((execution) => execution.success === false).length,
      tool_result_truncations: toolResultTruncations,
      per_tool: perTool,
    },
    buildTestCommands,
  };
}

function permissionMetrics(events) {
  const requested = events.filter((event) => event?.type === "permission.requested" && event.data && typeof event.data === "object");
  const completed = events.filter((event) => event?.type === "permission.completed" && event.data && typeof event.data === "object");
  const outcomes = completed.map((event) => String(event.data?.result?.kind ?? ""));
  return {
    total_requests: requested.length,
    approved: outcomes.filter((kind) => kind === "approved" || kind === "approved-for-session").length,
    denied: outcomes.filter((kind) => kind.startsWith("denied")).length,
    outcomes,
  };
}

function buildTestIterations(buildTestCommands, runStartedAtMs) {
  const firstInvocation = buildTestCommands[0];
  const passingInvocation = buildTestCommands.find((command) => command.exit_code === 0);
  const finalInvocation = buildTestCommands.at(-1);
  return {
    commands: buildTestCommands,
    first_command_time_ms: firstInvocation?.started_at ? toTimestampMs(firstInvocation.started_at) - runStartedAtMs : null,
    first_passing_command_time_ms:
      passingInvocation?.completed_at ? toTimestampMs(passingInvocation.completed_at) - runStartedAtMs : null,
    total_invocations: buildTestCommands.length,
    failed_invocations: buildTestCommands.filter((command) => command.exit_code !== 0).length,
    final_status:
      !finalInvocation ? "not_run" : finalInvocation.exit_code === 0 ? "passed" : "failed",
  };
}

export function summarizeSessionEvents({ events, runStartedAtMs }) {
  const orderedEvents = [...events].sort((left, right) => (toTimestampMs(left.timestamp) ?? 0) - (toTimestampMs(right.timestamp) ?? 0));
  const firstEventMs = toTimestampMs(orderedEvents[0]?.timestamp);
  const firstAssistantDeltaMs = toTimestampMs(orderedEvents.find((event) => event.type === "assistant.message_delta")?.timestamp);
  const finalAnswerEvent = [...orderedEvents].reverse().find((event) => event.type === "assistant.message");
  const finalAnswerMs = toTimestampMs(finalAnswerEvent?.timestamp);
  const idleMs = toTimestampMs(orderedEvents.find((event) => event.type === "session.idle")?.timestamp);
  const { executionList, perTool, tools, buildTestCommands } = collectToolExecutions(orderedEvents);
  const firstToolMs = executionList[0]?.startedAtMs ?? null;
  const usage = aggregateUsage(orderedEvents);
  const contextWindow = latestUsageInfo(orderedEvents);
  const compaction = compactionMetrics(orderedEvents);
  const permissions = permissionMetrics(orderedEvents);
  const buildTest = buildTestIterations(buildTestCommands, runStartedAtMs);
  const sessionErrors = orderedEvents.filter((event) => event?.type === "session.error");
  const hookErrors = orderedEvents.filter((event) => event?.type === "hook.end" && event.data?.success === false);
  return {
    final_answer: typeof finalAnswerEvent?.data?.content === "string" ? finalAnswerEvent.data.content : "",
    timing: {
      wall_clock_run_duration_ms: idleMs !== null ? idleMs - runStartedAtMs : null,
      time_to_first_session_event_ms: firstEventMs !== null ? firstEventMs - runStartedAtMs : null,
      time_to_first_assistant_delta_ms: firstAssistantDeltaMs !== null ? firstAssistantDeltaMs - runStartedAtMs : null,
      time_to_first_assistant_final_ms: finalAnswerMs !== null ? finalAnswerMs - runStartedAtMs : null,
      time_to_first_tool_call_ms: firstToolMs !== null ? firstToolMs - runStartedAtMs : null,
      time_to_final_answer_ms: finalAnswerMs !== null ? finalAnswerMs - runStartedAtMs : null,
      time_to_session_idle_ms: idleMs !== null ? idleMs - runStartedAtMs : null,
      per_tool: perTool,
    },
    tokens: {
      ...usage,
      context_window: contextWindow,
      ...compaction,
    },
    tools,
    permissions,
    build_test_iterations: buildTest,
    errors: {
      total_session_errors: sessionErrors.length,
      model_call_failures: sessionErrors.filter((event) => event.data?.errorContext === "model_call").length,
      hook_errors: hookErrors.length,
    },
  };
}

export function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}
