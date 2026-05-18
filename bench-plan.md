# Copilot SDK Kast Benchmark Runner And Eval Consolidation

## Summary
- Add a root evaluation runner at `evaluation/runners/copilot-sdk/` that drives Copilot through `@github/copilot-sdk@1.0.0-beta.4`, registers `kast_*` custom tools for `with_skill`, and writes raw SDK event JSONL to each run’s `outputs/transcript.md`.
- Extract the existing `.github/extensions/kast/extension.mjs` tool definitions into a shared JS module so the Copilot extension and SDK runner use one `kast_*` contract.
- Consolidate evaluation ownership: root `evaluation/` remains the only benchmark source of truth; remove stale skill-local eval copies/placeholders from `.agents/skills/kast`.

## Key Changes
- Create a runner-local Node package under `evaluation/runners/copilot-sdk/` with `package.json` and lockfile; do not add root Node package metadata.
- Add `run-one.mjs` with the dispatcher args expected by `dispatch_runs.py`: `--instructions`, `--transcript`, `--run-dir`, `--eval-id`, `--configuration`, `--run-number`, `--attempt`.
- Add a thin `run-benchmark.sh` wrapper for the SDK runner and prewire `evaluation/scripts/run_evaluation.py`.
- Implement SDK behavior:
  - `with_skill`: create a session with shared `kast_*` tools via `defineTool`, `approveAll`, `availableTools` restricted to the `kast_*` names, and an added system hint telling the model to use resolve-first flows for Kotlin semantic work.
  - `without_skill`: create a session with no `kast_*` custom tools, preserving baseline isolation checks.
  - Tool handlers call `kast rpc` with `execFile`, JSON-RPC `{jsonrpc:"2.0", method, params, id:1}`, and `--workspace-root`.
  - Use the default Copilot home for authentication, keep benchmark artifacts under `run_dir`, and default `SDK_MODEL=gpt-5-mini` plus `SDK_TIMEOUT_MS=180000`.
- Extract shared tool definitions into `.github/extensions/_shared/kast-tools.mjs`; keep extension-specific binary resolution, session hooks, and Kotlin generic-tool warnings in `extension.mjs`.
- Update `parse_tool_calls.py` for SDK JSONL events and de-duplicate request/start/end records by `toolCallId`; keep the current parser/grader/aggregator flow.

## Eval Cleanup
- Delete `.agents/skills/kast/evaluation/**`; it is a stale duplicate of root `evaluation/`.
- Delete `.agents/skills/kast/evals/**`; the current files are empty placeholders and conflict with root `evaluation/README.md`’s source-of-truth claim.
- Delete `evaluation/runners/copilot/**`; the old Copilot CLI runner is superseded by the SDK runner because it cannot enforce custom `kast_*` tool registration.
- Update `.agents/skills/kast/SKILL.md`, `AGENTS.md`, `kast-cli/build.gradle.kts`, `EmbeddedSkillResources`, and packaging tests so packaged skills no longer carry skill-local eval placeholders or duplicated benchmark scripts.
- Narrow `kast eval skill`/`SkillAdapter` back to skill-surface health: SKILL.md presence, trigger/description, native tool coverage, legacy artifact absence, references/scripts. Root behavioral benchmarking stays under `evaluation/`.

## Test Plan
- Start with tests:
  - Add parser fixture coverage for SDK JSONL tool events if needed.
  - Add/shared-module tests that assert the exported `kast_*` tool name set matches the extension’s expected names.
  - Update skill packaging/eval tests to prove deleted skill-local eval paths are no longer expected.
- Run:
  - `npm ci --prefix evaluation/runners/copilot-sdk`
  - `node --check evaluation/runners/copilot-sdk/run-one.mjs`
  - `python3 -m unittest evaluation/scripts/tests/test_value_proof_scripts.py evaluation/scripts/tests/test_run_evaluation.py`
  - `python3 evaluation/scripts/validate_history_assets.py`
  - `./gradlew :kast-cli:processResources :kast-cli:test --offline`
- Smoke the actual path when Copilot auth is available:
  - SDK with-skill one-case run should generate `outputs/transcript.md`, `outputs/tool_calls.jsonl`, and `benchmark.json`.
  - With-skill grading should show `kast_calls > 0`.
  - Without-skill runs should not register SDK `kast_*` tools; any `kast_calls > 0` remains a baseline contamination signal.

## Assumptions
- Root `evaluation/` is the only benchmark/eval source of truth for Kast.
- The SDK dependency remains runner-local.
- The JSON-RPC method contract does not change.
- The initial implementation extracts shared tool definitions now, but does not try to make installed packaged skills run the SDK benchmark outside this repo.
