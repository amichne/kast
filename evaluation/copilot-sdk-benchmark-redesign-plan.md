# Copilot SDK Benchmark Redesign Plan

## Goal

Redesign the Kast benchmark runner so the Copilot SDK owns live session
execution and event capture, while the repository owns deterministic validation
and aggregation. The benchmark must report two separate evidence surfaces:

- Mechanically derived measures: facts computed from SDK events, telemetry,
  git state, build/test commands, tool calls, and exact oracles.
- LLM-graded measures: judgment calls that require qualitative review of the
  final answer against a rubric.

The merged benchmark output may combine these surfaces for convenience, but it
must preserve the raw mechanical and LLM-graded artifacts independently.

## Current Problems To Fix

- The runner writes transcripts but relies on parsing those transcripts for too
  many process metrics.
- Every full benchmark run currently records `workspace_dirty_post=true`, so
  run isolation is not trustworthy.
- Token metrics are missing or zero even though the SDK event schema exposes
  usage events.
- Build/test iteration metrics are not captured as first-class benchmark
  outputs.
- The `with_skill` condition registers `kast_*` tools and a short system hint,
  but does not prove that the real packaged Kast skill instructions were loaded.
- Mutation tasks are graded mostly from assistant prose instead of from git
  diffs, file contents, compile/test exit codes, and allowed-file policies.
- Some deterministic graders conflate unrelated concepts, such as treating a
  raw file line-count oracle as a minimum citation count.
- LLM grading and mechanical grading can blur together, making failures hard to
  audit.

## Target Artifact Layout

Each run should emit durable artifacts under its run directory:

```text
run-N/
  inputs.json
  sdk-events.jsonl
  otel.jsonl
  final-answer.md
  mechanical.json
  llm-grade-input.json
  llm-grade.json
  grading.json
  timing.json
```

`grading.json` is the merged view. `mechanical.json` and `llm-grade.json` are
the source-of-truth inputs for that view and must remain independently
inspectable.

## SDK-Owned Execution

Move as much runtime capture as possible into `evaluation/runners/copilot-sdk`.

The SDK runner should:

- Create a fresh Copilot session per run.
- Set `model`, `reasoningEffort`, `workingDirectory`, `availableTools`,
  `tools`, `systemMessage`, `skillDirectories`, `instructionDirectories`, and
  telemetry configuration explicitly.
- Use `session.on(...)` to write the raw typed session event stream to
  `sdk-events.jsonl`.
- Enable telemetry file output through `CopilotClient({ telemetry: { exporterType:
  "file", filePath, captureContent } })` and write it to `otel.jsonl`.
- Capture `assistant.message` as `final-answer.md`.
- Capture `assistant.usage`, `session.usage_info`, `session.shutdown`,
  `session.compaction_start`, and `session.compaction_complete` for token and
  context metrics.
- Capture `tool.execution_start`, `tool.execution_complete`, permission events,
  command events, model-call failures, hook events, and custom tool handler
  results for process metrics.
- Use SDK session hooks (`onPreToolUse`, `onPostToolUse`,
  `onUserPromptSubmitted`, `onSessionStart`, `onSessionEnd`,
  `onErrorOccurred`) to record structured lifecycle events and enforce
  benchmark policy.
- Record SDK, Copilot CLI, Node, platform, target model, reasoning effort, and
  runner version metadata in `inputs.json` or `mechanical.json`.

## Benchmark Matrix

Use at least these configurations:

- `with_skill`: real Kast skill loaded and `kast_*` custom tools registered.
- `tool_only`: `kast_*` custom tools registered, but no Kast skill
  instructions loaded.
- `without_skill`: no Kast skill and no `kast_*` custom tools.

The `without_skill` configuration should either deny direct `kast` shell use or
mark such usage as baseline contamination. The policy must be explicit and
recorded in `mechanical.json`.

## Mechanical Measures

Mechanical measures are facts. They are derived without an LLM and cannot be
overridden by LLM grading.

### Identity And Reproducibility

- Benchmark schema version.
- Runner version.
- SDK package version.
- Copilot CLI version.
- Model and reasoning effort.
- Target repository and target git SHA.
- Benchmark branch/SHA.
- Prompt text and prompt hash.
- Rendered case ID and case version.
- Skill path, skill hash, tool-set hash, and instruction hash.
- Worktree path.

### Timing

- Wall-clock run duration.
- Time to first session event.
- Time to first assistant delta.
- Time to first assistant final message.
- Time to first tool call.
- Time to final answer.
- Time to `session.idle`.
- Per-tool start/end/duration.
- Retry count and timeout status.

### Token And Context Metrics

Use SDK usage events where available:

- Input tokens.
- Output tokens.
- Reasoning tokens.
- Cache read tokens.
- Cache write tokens.
- Total tokens.
- Per-model usage.
- Context-window tokens from `session.usage_info`.
- Compaction count.
- Compaction tokens used.
- Tokens removed by compaction.

If a token field is unavailable, record `null` plus an explicit source reason.
Do not write `0` unless the SDK explicitly reports zero.

### Tool And Permission Metrics

- Total tool calls.
- Custom `kast_*` tool calls.
- Built-in file read/write/edit calls.
- Shell calls.
- Generic search calls.
- Permission requests and outcomes.
- Denied calls.
- Failed tool calls.
- Model-call failures.
- Hook errors.
- Tool result truncation indicators when available.

### Repo State And Mutation Safety

Each run should execute in a fresh git worktree at the target SHA.

Record:

- Pre-run SHA and dirty state.
- Post-run SHA and dirty state.
- `git diff --name-status`.
- Full patch or patch hash.
- Touched files.
- Allowed-file policy result.
- Whether target edits are present.
- Whether unexpected edits occurred.
- Whether the worktree was preserved for debugging.

Mutation tasks must be graded from worktree state and command exit codes, not
only from assistant text.

### Build And Test Iterations

Record commands from SDK command/tool events and explicit harness probes:

- Build/test command text.
- Start/end timestamps.
- Exit code.
- First compile/test command time.
- First passing compile/test command time.
- Total compile/test invocations.
- Failed compile/test invocation count.
- Final compile/test status.

If the agent does not run the requested validation command, the harness should
optionally run a post-run probe and record it separately as harness validation,
not as agent behavior.

### Exact Oracle Checks

Keep deterministic checks for:

- Expected paths/modules/symbols present.
- Forbidden paths/modules/symbols absent.
- File citations present and valid on disk.
- Minimum/maximum citation counts when the oracle actually means citations.
- Exact output literals.
- Expected file content after edits.
- Compile/test status from command results.
- Tool-use expectations from SDK events.

Each oracle type should have a named checker. Avoid generic "flatten expected
and search transcript" behavior for cases where the semantics differ.

## LLM-Graded Measures

LLM grading is reserved for qualitative judgments that cannot be mechanically
derived.

Use it for:

- Whether the final answer resolves the user's task.
- Whether the reasoning is coherent and grounded in evidence.
- Whether the answer is appropriately scoped.
- Whether uncertainty and limitations are communicated.
- Whether semantic hallucinations appear beyond exact-oracle coverage.
- Whether the response is useful despite partial mechanical misses.

LLM grading input should be constrained:

- Prompt.
- Final answer.
- Case rubric.
- Mechanical summary.
- Relevant oracle results.
- Minimal excerpts from tool output when needed.

The grader must not change mechanical facts. It can only add rubric judgments
and explanations.

## Aggregation Contract

`benchmark.json` should separate sections:

```json
{
  "mechanical_summary": {},
  "llm_graded_summary": {},
  "combined_summary": {},
  "runs": []
}
```

Each run should include:

```json
{
  "eval_id": "...",
  "configuration": "...",
  "run_number": 1,
  "status": "valid|invalid",
  "mechanical": {},
  "llm_graded": {},
  "combined": {},
  "integrity": {}
}
```

Invalid runs should be excluded from comparative scores but reported in an
integrity section with reasons.

## Implementation Phases

### Phase 1: Contracts

- Add JSON schemas for `mechanical.json`, `llm-grade.json`, and merged
  `grading.json`.
- Update `benchmark.schema.json` to distinguish mechanical, LLM-graded, and
  combined summaries.
- Add fixture tests for schema validation.

### Phase 2: SDK Event Recorder

- Refactor `run-one.mjs` into a structured event recorder.
- Write `sdk-events.jsonl`, `otel.jsonl`, `final-answer.md`, `inputs.json`, and
  initial `mechanical.json`.
- Populate token, timing, tool, permission, and session metrics directly from
  SDK events.

### Phase 3: Isolated Worktrees

- Add per-run worktree creation from the target SHA.
- Run each Copilot session inside its worktree.
- Capture pre/post git state and diffs.
- Preserve failed worktrees when requested; clean successful ones by default.

### Phase 4: Mechanical Grading

- Replace transcript-only deterministic grading with typed checkers.
- Add checkers for citations, exact oracles, tool use, file edits, allowed
  paths, compile/test commands, and post-run probes.
- Remove or rewrite incorrect generic checks.

### Phase 5: LLM Grading

- Add a separate LLM grader step that reads `llm-grade-input.json` and writes
  `llm-grade.json`.
- Use a stable rubric and include mechanical facts as context.
- Ensure LLM grading cannot override mechanical pass/fail or integrity status.

### Phase 6: Aggregation And Reporting

- Aggregate mechanical, LLM-graded, and combined measures separately.
- Report time, tokens, tool calls, test iterations, pass rates, invalid runs,
  flaky runs, and contamination separately.
- Update `benchmark.md` to show the split clearly.

### Phase 7: Validation

- Add runner unit tests for SDK event parsing with synthetic events.
- Add schema tests for generated artifacts.
- Add smoke benchmark for one read-only case and one mutation case.
- Run the full benchmark only after smoke output proves nonzero token metrics,
  clean worktree isolation, and valid mechanical checks.

## Acceptance Criteria

- Every run starts from a clean worktree at the target SHA.
- Dirty post-run state is either expected and captured as a diff or invalidates
  the run.
- Token fields are nonzero when SDK reports usage; unavailable fields are
  explicit `null` values with source reasons.
- Test/build iteration metrics are derived from command/tool events and harness
  probes, not assistant prose.
- `with_skill` proves the real skill/instructions were loaded.
- `tool_only` separates tool availability from instruction quality.
- `without_skill` is not contaminated by `kast_*` tools.
- Mechanical failures remain mechanical failures even if the LLM grader likes
  the answer.
- The aggregate can answer separately: did the skill improve correctness, did
  it improve reliability, did it reduce time, did it reduce tokens, and did it
  reduce test iterations?
