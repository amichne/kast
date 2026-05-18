# Copilot SDK Runner

Adapter scripts that drive the Kast evaluation suite through
`@github/copilot-sdk`. This is the only supported Copilot-backed runner in the
repo.

## Quick Start

```bash
bash evaluation/runners/copilot-sdk/run-benchmark.sh \
  --bindings evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot-sdk-smoke \
  --iteration smoke \
  --runs-per-config 1 \
  --concurrency 1 \
  --timeout-ms 180000 \
  -- --case vp-disambiguate-member
```

The wrapper installs runner-local Node dependencies, renders the catalog,
dispatches runs, runs the script grader, and writes `benchmark.json`.

Each run now emits first-class artifacts under its run directory:

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

## Files

| File | Purpose |
| --- | --- |
| `run-one.mjs` | Invoked once per eval/config/run by `dispatch_runs.py`; opens a Copilot SDK session, records SDK/telemetry artifacts, and runs in an isolated git worktree. |
| `run-benchmark.sh` | End-to-end wrapper that prewires `run-one.mjs` into `evaluation/scripts/run_evaluation.py`. |
| `tests/test-kast-tools.mjs` | Verifies the shared `kast_*` tool contract exported from `.github/extensions/_shared/kast-tools.mjs`. |
| `tests/test-run-artifacts.mjs` | Verifies SDK event parsing for timing, tokens, tool calls, permissions, and build/test iterations. |

## Configuration

| Variable | Effect |
| --- | --- |
| `SDK_MODEL` | Model name for the SDK session. Default: `gpt-5-mini`. |
| `SDK_TIMEOUT_MS` | Milliseconds to wait for `session.idle` before failing the run. Default: `180000`. |
| `COPILOT_CLI_PATH` | Copilot CLI executable used by the SDK. Defaults to `COPILOT_BIN`, then `which copilot`, then the SDK bundled binary. |
| `COPILOT_BIN` | Compatibility alias for `COPILOT_CLI_PATH`. |
| `COPILOT_GITHUB_TOKEN` | Explicit token for SDK authentication. Falls back to `COPILOT_SDK_GITHUB_TOKEN`, `GITHUB_TOKEN`, then `GH_TOKEN`. |
| `COPILOT_SDK_GITHUB_TOKEN` | Runner-specific explicit token override. |
| `KAST_BIN` | Path to the `kast` binary used by custom tool handlers. Default: `kast`. |
| `KAST_WORKSPACE_ROOT` | Workspace root passed to `kast rpc` and used as the SDK session working directory. The wrapper reads it from the bindings file. |
| `KAST_EVAL_SKIP_NPM_CI` | Set to `1` only when dependencies are already installed and you want to skip the wrapper's `npm ci` step. |

The benchmark matrix uses three configurations:

- `with_skill` loads the real repo Kast skill and registers `kast_*` tools.
- `tool_only` registers `kast_*` tools without loading the Kast skill.
- `without_skill` loads neither the Kast skill nor `kast_*` tools and denies direct `kast` shell use as explicit baseline policy.

The runner intentionally uses the default Copilot home so the SDK can reuse the
operator's authenticated Copilot session. Per-run benchmark artifacts still
live under each `run_dir`.
