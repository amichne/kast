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
| `run-single-mock-benchmark.sh` | One-pass mock-backend wrapper that runs the zero-cost model, asks Codex to summarize aggregate outputs, and publishes compact metrics to `amichne/cast-benchmarks`. |
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
| `KAST_EVAL_KAST_BACKEND` | `real` or `mock` backend for `kast_*` tools. Default: `real`. |
| `KAST_EVAL_REAL_BACKEND_WORKSPACE` | For real backend runs, choose whether `kast_*` calls target the isolated `worktree` or original `target` workspace. Default: `worktree`. |
| `KAST_EVAL_MOCK_PAYLOADS` | Mock backend payload path used when the backend is `mock`. |
| `KAST_EVAL_DISABLE_HOOKS` | Set to `1` to force an isolated Copilot config dir even when using the real backend. |
| `KAST_EVAL_SKIP_NPM_CI` | Set to `1` only when dependencies are already installed and you want to skip the wrapper's `npm ci` step. |

The default benchmark matrix uses three configurations:

- `with_skill` loads the real repo Kast skill and registers `kast_*` tools.
- `tool_only` registers `kast_*` tools without loading the Kast skill.
- `without_skill` loads neither the Kast skill nor `kast_*` tools and denies direct `kast` shell use as explicit baseline policy.

For four-scenario isolation, pass
`--configs without_skill,skill_only,tool_only,with_skill`. `skill_only` loads
the Kast skill while disabling `kast_*` tools and direct `kast` shell fallback,
which separates instruction value from custom-tool value.

## Mock KAST Backend

Use `--kast-backend mock` when the benchmark should measure agent behavior
against presumed normal KAST outputs without starting the real Gradle Tooling
API backed daemon:

```bash
bash evaluation/runners/copilot-sdk/run-benchmark.sh \
  --bindings evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot-sdk-mock \
  --iteration mock-smoke \
  --runs-per-config 1 \
  --concurrency 2 \
  --kast-backend mock
```

If `--mock-payloads` is omitted, the wrapper generates one at
`<workspace>/<iteration>-mock-backend.json` using
`evaluation/scripts/generate_mock_backend_payloads.py`. Add one or more
`--history-root` values to mine archived `sdk-events.jsonl` outputs or Copilot
root `events.jsonl` session history first; any missing methods are filled from
`catalog.json` and `bindings/*.json`.

Mock mode is runner-local only. It does not change `kast rpc`, the standalone
daemon, or production CLI behavior. Runs record backend mode, payload hash,
history/fallback counts, and mock misses in `inputs.json` and `mechanical.json`.
Any unmatched mock call is reported as a JSON-RPC error and invalidates the
aggregate run with `mock_backend_error`.

For a single zero-cost snapshot that also writes a Codex aggregate analysis and
commits compact metrics to `amichne/cast-benchmarks`, use:

```bash
bash evaluation/runners/copilot-sdk/run-single-mock-benchmark.sh
```

When `~/.copilot/session-state` exists, the single-run wrapper mines it by
default. Use `--no-default-history` for a fallback-only mock payload.

Use `--dry-run` to inspect the benchmark, Codex, and publish contract without
launching Copilot.

The runner intentionally uses the default Copilot home so the SDK can reuse the
operator's authenticated Copilot session. Per-run benchmark artifacts still
live under each `run_dir`.

To build a compact gist-ready report from completed benchmark artifacts:

```bash
python3 evaluation/scripts/summarize_benchmark_outputs.py \
  --benchmark .benchmarks/copilot-sdk-mock/mock-four-way-full-suite-3x-20260519T053232Z \
  --known-good .benchmarks/copilot-sdk-mock/mock-four-way-full-suite-3x-20260519T053232Z \
  --output-dir .benchmarks/summary/kast-benchmark-summary-20260519
```
