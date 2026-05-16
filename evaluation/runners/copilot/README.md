# Copilot CLI runner

Adapter scripts that drive the kast evaluation/benchmark suite using the
standalone `@github/copilot` CLI noninteractively, in parallel, on a
zero-cost model by default.

## Prerequisites

- `copilot` CLI on `PATH` (or `COPILOT_BIN` set to its absolute path).
  See <https://docs.github.com/copilot/copilot-in-the-cli>.
- An authenticated Copilot session (`copilot auth login` once).
- Python 3.11+ (already required by the evaluation framework).

## Quick start

```bash
# Smoke: one case, one run, one worker.
bash evaluation/runners/copilot/run-benchmark.sh \
  --bindings  evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot-smoke \
  --iteration smoke \
  --runs-per-config 1 \
  --concurrency 1 \
  -- --case vp-disambiguate-member
```

```bash
# Full parallel run on the zero-cost default model.
bash evaluation/runners/copilot/run-benchmark.sh \
  --bindings  evaluation/bindings/kast.json \
  --workspace .benchmarks/copilot \
  --iteration iteration-001 \
  --runs-per-config 5 \
  --concurrency 4
```

`run-benchmark.sh --help` lists every flag. Anything after `--` is
forwarded verbatim to `evaluation/scripts/run_evaluation.py`, which is
how `--case <id>` (repeatable) gets through.

## Files

| File | Purpose |
|------|---------|
| `run-one.sh` | Invoked once per (eval × config × run) by `dispatch_runs.py`. Streams the rendered prompt into `copilot --prompt` and writes the transcript to the dispatcher's expected path. |
| `run-benchmark.sh` | End-to-end orchestrator. Pre-wires `run-one.sh` into `run_evaluation.py --dispatch-command-template`. |

## Model selection

The runner defaults to `gpt-5-mini`, currently a 0× premium-request
model on Copilot CLI. Override with either flag or env var:

```bash
# Per-invocation flag.
bash evaluation/runners/copilot/run-benchmark.sh --model claude-haiku-4.5 ...

# Or env var (also respected by direct run-one.sh calls).
COPILOT_MODEL=claude-haiku-4.5 bash evaluation/runners/copilot/run-benchmark.sh ...
```

If GitHub changes which model is free, update `COPILOT_MODEL`'s default
in `run-one.sh`.

## Parallel-safety: per-run state isolation

`run-one.sh` pins `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME`,
and `XDG_CACHE_HOME` inside each `run_dir` (`.copilot-state/…`) so
concurrent workers cannot collide on Copilot's session/log/auth caches.
On first run each worker may need to re-authenticate inside its sandbox;
if you'd rather share an existing global session, override before
calling the runner, e.g.:

```bash
export XDG_CONFIG_HOME="$HOME/.copilot-state/config"
bash evaluation/runners/copilot/run-benchmark.sh ...
```

This trades isolation for shared auth — only do it when you're confident
your Copilot CLI version is concurrency-safe.

## Escape hatches

| Variable | Effect |
|----------|--------|
| `COPILOT_MODEL` | Model name passed to `copilot --model`. Default: `gpt-5-mini`. |
| `COPILOT_BIN`   | Absolute path to the `copilot` binary. Default: `copilot` (looked up on `PATH`). |
| `COPILOT_EXTRA_ARGS` | Extra args appended verbatim to every `copilot --prompt` call (word-split, no quoting). Use sparingly. |
| `KAST_WORKSPACE_ROOT` | Repo root passed to `copilot --add-dir`. Set automatically by `run-benchmark.sh` from the bindings file; only set this yourself when calling `run-one.sh` directly. |

## What this runner does *not* do

- Grading. The `--grade-command-template` slot in `run_evaluation.py` is
  untouched; the framework still falls back to its built-in script-based
  grader for `graded_by: script` expectations, and LLM-judged
  expectations remain unscored unless you supply your own grader.
- Catalog rendering or workspace scaffolding. Those phases live in
  `evaluation/scripts/` and run unchanged.
