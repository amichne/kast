# Kast workflow ownership

Use this file when a task needs an install/config/package check, a project
readiness gate, or a repeatable semantic request sequence. Discover exact
request fields with `kast agent tools`; this file owns the decision points.

## Install and package verification

Run the native package verification workflow before claiming the active
package, skill, instructions, config, or binary are current:

```sh
kast agent --output json workflow package-verify --workspace-root "$PWD" \
  --require-gradle-project
```

Pass explicit require and target-root flags when a host-specific resource must
be checked:

```sh
kast agent --output json workflow package-verify --workspace-root "$PWD" \
  --require-copilot --copilot-target-dir "$PWD/.github" \
  --require-skill --skill-target-dir "$PWD/.codex/skills" \
  --require-instructions --instructions-target-dir "$PWD/.codex/instructions"
```

Add `--require-copilot`, `--require-skill`, or `--require-instructions` only
when that repository-local artifact is required for the task. The workflow
emits JSON with command-surface evidence, readiness, paths, manifest-backed
resource state, catalog hash comparisons, and recovery commands.
When a package was installed into a nonstandard host root, pass the same setup
target root with `--copilot-target-dir`, `--skill-target-dir`, or
`--instructions-target-dir` so manifest checks and recovery commands use that
host-owned target instead of only the standard repository roots. The
`package-verify` workflow accepts the same require and target-root flags and
fails when an explicit required target is missing, stale, or not
manifest-backed. Failed required resource checks include
`requiredResources.issues[].recoveryArgv` with the exact recovery invocation to run.
In `--dry-run` mode, catalog-backed workflow steps report `nextRequest`;
`package-verify` reports `nextCommandArgv` because it is native CLI
verification, not a backend method.

Execute recovery commands exactly as emitted. Stale skill and instruction
recovery preserves the target resource root when package verification can
identify one; the owner table below names the state owner, not a command to
rewrite back to bare `kast`.

If package verification reports stale state, use the one owner for that state:

| Symptom | Owner |
| --- | --- |
| `kast` missing, bad manifest, install-owned config drift | `kast ready --fix` |
| configured binary missing or not the running binary | `kast ready --for machine --fix` |
| Kotlin semantic backend absent from the manifest | `kast ready --for kotlin`, then install or activate a backend |
| active binary lacks `kast agent`, shows top-level `kast rpc`, or shows `install affected` | refresh the active binary, in this repo `./gradlew installDevelopmentLocal` |
| stale repository guidance or manifest resource mismatch | `kast setup --force` |
| stale repository skill or Markdown instructions | emitted recovery command |

Do not edit generated `.github` package files as source. The source owners are
`cli-rs/resources/plugin/`, `cli-rs/resources/kast-instructions/`, and this
skill tree.

## Project readiness

Before semantic work, prove three things:

1. the active `kast` binary exposes `kast agent`;
2. the workspace is a Gradle project or the task explicitly is not about
   Kotlin/Gradle semantics;
3. backend or index failures have a recovery attempt or a clear blocker.

Use `kast setup --dry-run --workspace-root "$PWD"` when setup and runtime
readiness both matter. The dry run reports the setup target and runtime command
without writing files or starting a backend. In JSON output, `setup.targetDir`
and `setup.installCommand` describe the guidance install, and `runtimeCommand`
describes backend warmup. For agent-run flows, prefer
`kast --output json setup --workspace-root "$PWD" --no-open-ide` when the
command may inherit a human terminal; interactive onboarding is for operators
choosing global or repository-scoped defaults, not unattended agents.

For `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a
missing source-index database, warm the runtime:

```sh
kast setup --workspace-root "$PWD" --backend idea --no-open-ide
```

Use `kast developer runtime restart --workspace-root "$PWD"` when daemon/config drift is likely,
then re-run the failed request. Use `kast developer runtime status --workspace-root "$PWD"` to
quote runtime evidence.

## File-backed request exchange

For any nontrivial call, build the params file and preserve stdout/stderr:

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_PARAMS="$KAST_TMP/params.json"
KAST_RESULT="$KAST_TMP/stdout.json"
KAST_STDERR="$KAST_TMP/stderr.txt"
printf '%s\n' '{"query":"EventBean","modes":["exact","lexical"],"limit":10}' >"$KAST_PARAMS"
kast agent call symbol/query --params-file "$KAST_PARAMS" \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

For large payloads, put JSON in a file and pass `--params-file`. Check
`kast agent tools` first when the method or params shape is unknown. The call
fails the operation if the agent envelope or nested result reports failure.

Mutating methods such as `symbol/write-and-validate`, `symbol/rename`,
`raw/rename`, `raw/optimize-imports`, and `raw/apply-edits` require explicit
mutation controls on their workflow or command surface.

## Semantic workflow patterns

Use `kast agent workflow ...` for common identity-first sequences. For catalog
methods outside these patterns, use `kast agent call <method> --params-file`
as the file-backed request harness:

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

Use `kast agent tools` for sample schema shape, field names, and variant
discriminators.

## Hook candidate

No hook ships in the compact skill. If a host supports hooks, use
`kast agent workflow package-verify --require-gradle-project` as the read-only
gate before Kotlin semantic tasks and add `--require-copilot` only for Copilot
package publication checks.
