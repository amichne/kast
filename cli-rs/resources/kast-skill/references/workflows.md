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
JSON with command-surface evidence, readiness, paths, manifest-backed
resource state, catalog hash comparisons, and recovery commands.

Execute recovery commands exactly as emitted. When the verifier is run with
`--kast-bin` or another absolute executable, recovery strings preserve the
selected executable token. Stale skill and instruction recovery also preserves
the target resource root when the verifier can identify one; the owner table
below names the state owner, not a command to rewrite back to bare `kast`.

If the verifier reports stale state, use the one owner for that state:

| Symptom | Owner |
| --- | --- |
| `kast` missing, bad manifest, install-owned config drift | `kast ready --fix` |
| configured binary missing or not the running binary | `kast ready --for machine --fix` |
| Kotlin semantic backend absent from the manifest | `kast ready --for kotlin`, then install or activate a backend |
| active binary lacks `kast agent`, shows top-level `kast rpc`, or shows `install affected` | refresh the active binary, in this repo `./gradlew installDevelopmentLocal` |
| stale repository Copilot package files or manifest resource mismatch | `kast agent setup copilot --force` |
| stale repository skill | emitted `kast agent setup skill ... --force` recovery |
| stale Markdown instructions | emitted `kast agent setup instructions ... --force` recovery |

Do not edit generated `.github` package files as source. The source owners are
`cli-rs/resources/plugin/`, `cli-rs/resources/kast-instructions/`, and this
skill tree.

## Project readiness

Before semantic work, prove three things:

1. the active `kast` binary exposes `kast agent`;
2. the workspace is a Gradle project or the task explicitly is not about
   Kotlin/Gradle semantics;
3. backend or index failures have a recovery attempt or a clear blocker.

Use `kast agent up --dry-run --workspace-root "$PWD"` when harness setup and
runtime readiness both matter. The dry run reports the selected harness,
workspace-root-derived setup target, and runtime command without writing files
or starting a backend. In JSON output, `setup.targetDir` is the resolved package
target and `setup.installCommand` is the exact install-only command, including
the executable token and `--target-dir`.

Use `kast agent setup auto --dry-run` when only harness package selection
matters. It derives the default target from the current directory unless
`--target-dir` is passed, and JSON output reports `targetDir` with the matching
`installCommand`.

For `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a
missing source-index database, warm the runtime:

```sh
kast runtime up --workspace-root "$PWD" --backend idea
```

Use `kast runtime restart --workspace-root "$PWD"` when daemon/config drift is likely,
then re-run the failed request. Use `kast runtime status --workspace-root "$PWD"` to
quote runtime evidence.

## File-backed request exchange

For any nontrivial call, build the params file and preserve stdout/stderr:

```sh
python3 scripts/kast-agent-call.py symbol/query \
  --params-json '{"query":"EventBean","modes":["exact","lexical"],"limit":10}' \
  --workspace-root "$PWD"
```

For large payloads, put JSON in a file and pass `--params-file`. The script
validates that the method exists in the shipped catalog, checks that the active
binary exposes a valid `kast agent tools` envelope, writes `params.json`,
`stdout.json`, and `stderr.txt`, and fails if the agent envelope or nested
result reports failure.

Use `--dry-run` to construct and validate the params file without contacting a
backend. Mutating methods such as `symbol/write-and-validate`, `symbol/rename`,
`raw/rename`, `raw/optimize-imports`, and `raw/apply-edits` require
`--allow-mutation`.

## Semantic workflow patterns

Use `kast agent workflow ...` for common identity-first sequences. For catalog
methods outside these patterns, use `scripts/kast-agent-call.py` as the
file-backed request harness:

```sh
kast agent workflow symbol --workspace-root "$PWD" \
  --out-dir "$PWD/.kast-workflow/symbol" \
  --symbol EventBean --references --callers incoming
kast agent workflow diagnostics --workspace-root "$PWD" \
  --out-dir "$PWD/.kast-workflow/diagnostics" \
  --file-path "$PWD/src/main/kotlin/App.kt"
```

The workflow runner preserves `input.json`, `stdout.json`, `stderr.txt`, and
`workflow.json` under one output directory and stops after the first failing
step. `write-validate` requires `--allow-mutation`; use `--dry-run` to build
request files without contacting a backend. `rename-plan` always uses dry-run
rename evidence.

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
