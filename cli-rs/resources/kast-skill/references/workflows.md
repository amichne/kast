# Kast workflow ownership

Use this file when a task needs an install/config/package check, a project
readiness gate, or a repeatable semantic request sequence. Keep exact request
fields in `commands.json` and `references/requests/`; this file owns the
decision points.

## Install and package verification

Run the read-only verifier before claiming the active package, skill,
instructions, config, or binary are current:

```sh
python3 scripts/verify-kast-state.py --workspace-root "$PWD" \
  --require-gradle-project
```

Add `--require-copilot`, `--require-skill`, or `--require-instructions` only
when that repository-local artifact is required for the task. The script emits
JSON with command-surface evidence, `doctor`, `paths`, package marker state,
catalog hash comparisons, and recovery commands.

If the verifier reports stale state, use the one owner for that state:

| Symptom | Owner |
| --- | --- |
| `kast` missing, bad manifest, install-owned config drift | `kast doctor --repair` |
| active binary lacks `kast agent`, shows top-level `kast rpc`, or shows `install affected` | refresh the active binary, in this repo `./gradlew installDevelopmentLocal` |
| stale repository Copilot files or catalog mismatch | `kast install copilot --force` |
| stale repository skill | `kast install skill --force` |
| stale Markdown instructions | `kast install instructions --force` |

Do not edit generated `.github` package files as source. The source owners are
`cli-rs/resources/plugin/`, `cli-rs/resources/kast-instructions/`, and this
skill tree.

## Project readiness

Before semantic work, prove three things:

1. the active `kast` binary exposes `kast agent`;
2. the workspace is a Gradle project or the task explicitly is not about
   Kotlin/Gradle semantics;
3. backend or index failures have a recovery attempt or a clear blocker.

For `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a
missing source-index database, warm the runtime:

```sh
kast up --workspace-root "$PWD" --backend idea
```

Use `kast restart --workspace-root "$PWD"` when daemon/config drift is likely,
then re-run the failed request. Use `kast status --workspace-root "$PWD"` to
quote runtime evidence.

## File-backed request exchange

For any nontrivial call, build the params file and preserve stdout/stderr:

```sh
python3 scripts/kast-agent-call.py symbol/query \
  --params-json '{"query":"EventBean","modes":["exact","lexical"],"limit":10}' \
  --workspace-root "$PWD"
```

For large payloads, put JSON in a file and pass `--params-file`. The script
validates that the method exists in the shipped catalog, writes `params.json`,
`stdout.json`, and `stderr.txt`, and fails if the agent envelope or nested
result reports failure.

Use `--dry-run` to construct and validate the params file without contacting a
backend. Mutating methods such as `symbol/write-and-validate`, `symbol/rename`,
`raw/rename`, `raw/optimize-imports`, and `raw/apply-edits` require
`--allow-mutation`.

## Semantic workflow patterns

Use `scripts/kast-semantic-workflow.py` for common identity-first sequences and
fall back to `scripts/kast-agent-call.py` only when you need a catalog method
outside these patterns:

```sh
python3 scripts/kast-semantic-workflow.py --workspace-root "$PWD" \
  symbol --symbol EventBean --references --callers incoming
python3 scripts/kast-semantic-workflow.py --workspace-root "$PWD" \
  diagnostics --file-path "$PWD/src/main/kotlin/App.kt"
```

The workflow runner preserves every step under one output directory and stops
after the first failing step. Mutating workflows (`rename`, `write`) require
`--allow-mutation`; use `--dry-run` to build request files without contacting a
backend.

Use the narrowest identity-first sequence that fits the evidence you already
have:

| Starting point | Sequence |
| --- | --- |
| unknown symbol name | `symbol/query` with tight filters, then `symbol/discover` when context is available, then `symbol/resolve` |
| exact file offset | `raw/resolve`, then `raw/references`, `raw/call-hierarchy`, `raw/type-hierarchy`, or `raw/implementations` |
| known symbol identity | `symbol/references` or `symbol/callers`; inspect exhaustiveness/truncation before summarizing |
| changed Kotlin file | `raw/workspace-refresh` for touched files, then `raw/diagnostics` |
| rename | prefer `symbol/rename` or `raw/rename` dry-run evidence before applying edits |
| structured edit | `symbol/write-and-validate` when it can express the change; otherwise `raw/apply-edits` with hashes, then diagnostics |

Read `references/requests/<category>/<method>/minimal.json` and
`maximal.json` for sample payloads. Read `commands.yaml` for field names and
variant discriminators.

## Hook candidate

No hook ships in the compact skill. If a host supports hooks, use
`scripts/verify-kast-state.py --require-gradle-project` as the read-only gate
before Kotlin semantic tasks and add `--require-copilot` only for Copilot
package publication checks.
