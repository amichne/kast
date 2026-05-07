Execute these four workstreams in parallel. They touch disjoint file sets.

---

### Workstream A: Extension probe + version parity (Items 2, 3, 4)

**Goal:** Make the extension resolve the kast binary without requiring a running daemon, and add a CLI/extension version parity check.

**A1 — Make the probe daemon-free (Item 2, fixes Item 3 for free)**

File: `.github/extensions/kast/extension.mjs`

In `supportsWrapperCommands()` (lines 113-123), replace the `workspace-files` RPC probe with a daemon-free command. The current code runs:
```js
const {ok, stdout} = await execBash(`${JSON.stringify(path)} workspace-files ${JSON.stringify(probe)}`);
```
This requires a running daemon for `REPO_ROOT`. Change it to:
```js
const {ok, stdout} = await execBash(`${JSON.stringify(path)} --version`);
```
And validate that `stdout` contains a version string (e.g., matches `/\d+\.\d+/` or is non-empty). This proves the binary exists and is a kast CLI without needing any daemon.

This also eliminates the "item 3" problem — the only reason re-probing kept failing was because `supportsWrapperCommands` kept hitting the daemon-dependent path. With `--version`, re-probing will succeed as soon as the binary is on disk.

**A2 — Version parity check in extension (Item 4)**

File: `.github/extensions/kast/extension.mjs`

In the `onSessionStart` handler (lines 396-407), after resolving the binary, read the `.kast-copilot-version` marker file from the extension's install directory. Compare it against the output of `kast --version`. If they differ, log a warning via `session.log()` with level "warning" indicating CLI/extension version drift.

The marker file name is defined in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/EmbeddedCopilotExtensionResources.kt` line 25 as `.kast-copilot-version`. The extension should look for this file relative to its own directory (i.e., `path.join(EXTENSION_DIR, '..', '..', '.kast-copilot-version')` or similar, depending on the install layout).

**A3 — CLI `verify-extension` subcommand (Item 4)**

Files to modify:
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandParser.kt` — add a `listOf("verify-extension")` case in `parseKnownCommand()` (around line 98-210)
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommand.kt` — add a `VerifyExtension` variant
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandCatalog.kt` — register the new command
- `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandExecutor.kt` (or `DefaultCliCommandExecutor`) — handle the new command

The command should:
1. Read the `.kast-copilot-version` marker from the current working directory's `.github/` tree
2. Compare it to `currentCliVersion()` (from `tty/currentCliVersion`)
3. Output JSON `{"ok": true/false, "cli_version": "...", "extension_version": "..."}` 
4. Exit non-zero if versions don't match

Add a test in `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt`.

---

### Workstream B: Descriptor path audit (Item 1)

**Goal:** Investigate and fix the actual root cause of the descriptor path mismatch.

**Important context:** The writeup's diagnosis was wrong. Both CLI and runtime-libs resolve the descriptor directory through `KastConfig.load().paths.descriptorDir`, which defaults to `~/.kast/cache/daemons` via `defaultConfigDescriptorDir()` in `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationDefaults.kt` line 13. The writeup's proposed fix file (`WorkspaceDirectoryResolver.kt`) is incorrect — that handles workspace data directories, not daemon descriptors.

Steps:
1. Trace every code path that writes daemon descriptors. Start from `WorkspaceRuntimeManager.kt` line 25-28 (`configuredDescriptorDirectory`) and find all callers. Also search for `descriptorDir` and `daemons` across the codebase.
2. Trace every code path that reads daemon descriptors. The runtime-libs side reads via `KastConfig.load()` → `PathsDescriptorDir` → `defaultConfigDescriptorDir()`.
3. Check if `kast workspace ensure` writes the descriptor to a different path than what `KastConfig.load()` resolves. The most likely cause is a stale or workspace-specific `config.toml` overriding `descriptorDir` for one consumer but not the other.
4. If a genuine divergence is found, unify it in `ConfigurationDefaults.kt` and `PathsDescriptorDir.kt`. If it's a config-loading issue, add a diagnostic to `kast workspace ensure` that prints the resolved descriptor directory so users can verify it.
5. Consider adding a `kast workspace ensure` post-check that verifies the descriptor file is readable from the path that runtime-libs would resolve.

---

### Workstream C: Dispatch, timing, and path naming (Items 5, 6, 7, 8, 9)

**Goal:** Create a centralized run dispatcher that replaces manual wave bookkeeping, captures timing from the parent, guards against empty transcripts, supports serial chains, and normalizes path naming.

**C1 — Create `dispatch_runs.py` (Items 5, 6, 7, 8)**

New file: `.agents/skills/kast/value-proof/scripts/dispatch_runs.py`

This script should:
1. Accept a scaffolded iteration directory (from `run_value_proof.py`) as input
2. Discover all `run_instructions.md` files to build a run manifest
3. Support a `--concurrency` flag (default 4) for parallel sub-agent dispatch
4. Support a `chain_id` field: runs with the same `chain_id` execute serially in one sub-agent; different chains parallelize freely
5. For each run:
   - Record `start_ts` before dispatch
   - Dispatch the sub-agent (invoke whatever execution mechanism is used)
   - Record `end_ts` after completion
   - Write `timing.json` with wall-clock `executor_duration_seconds = end_ts - start_ts` (parent-side timing, not self-reported)
   - Assert `os.path.getsize(outputs/transcript.md) > 0` after completion; if empty, mark the run as failed and optionally re-dispatch (up to `--max-retries`, default 1)
6. Stream completion status to stdout as runs finish
7. Exit with a summary: N succeeded, M failed, K retried

**C2 — Add `chain_id` to catalog schema (Item 8)**

File: `.agents/skills/kast/value-proof/catalog.json`

Add an optional `chain_id` field to cases that must run serially in the same workspace. For example, `vp-multi-file-rename` and `vp-edit-and-validate` both mutate the workspace and should share a chain:
```json
"chain_id": "safe-mutations-chain"
```
Update `run_value_proof.py` to propagate `chain_id` into `eval_metadata.json` so `dispatch_runs.py` can read it.

**C3 — Fix path naming (Item 9)**

File: `.agents/skills/kast/value-proof/scripts/run_value_proof.py`, line 116

Currently: `eval_dir = iteration_dir / f"eval-{case_id}"` where `case_id` is e.g. `vp-disambiguate-member`, producing `eval-vp-disambiguate-member`.

Two options (pick one):
- **Option A (preferred):** Have `run_value_proof.py` emit a `manifest.json` at scaffold time that maps `eval_id → on-disk directory name`. All downstream tools (dispatch, grader, aggregator) read from this manifest instead of guessing paths.
- **Option B:** Drop the `eval-` prefix: `eval_dir = iteration_dir / case_id`. Simpler but requires updating any existing iteration directories.

Go with Option A. After scaffolding, write `manifest.json` to `iteration_dir` with structure:
```json
{
  "evals": {
    "vp-disambiguate-member": {"dir": "eval-vp-disambiguate-member", "chain_id": null},
    "vp-multi-file-rename": {"dir": "eval-vp-multi-file-rename", "chain_id": "safe-mutations-chain"}
  }
}
```

---

### Workstream D: CLI ergonomics + grading schema (Items 10, 11)

**D1 — Default args for `generate_executive_summary.py` (Item 10)**

File: `.agents/skills/kast/value-proof/scripts/generate_executive_summary.py`, lines 189-195

Change the argument parser to:
- Accept a single positional `iteration_dir` argument (the iteration directory path)
- Default `--benchmark` to `{iteration_dir}/benchmark.json`
- Default `--bindings` to `{iteration_dir}/bindings.json` (or search for `bindings/*.json` in the parent)
- Default `--output` to `{iteration_dir}/executive_summary.md`
- Default `--html-output` to `{iteration_dir}/executive_summary.html`
- Keep the explicit flags as overrides for CI use

This makes the 90% case a single command: `python generate_executive_summary.py path/to/iteration-001`

**D2 — Publish `grading.schema.json` (Item 11)**

New file: `.agents/skills/kast/value-proof/grading.schema.json` (or `.agents/skills/skill-creator/scripts/grading.schema.json` next to `validation.py`)

Extract the schema from `validate_grading_data()` in `.agents/skills/skill-creator/scripts/validation.py` (lines 978-1061) into a JSON Schema document. The schema should define:
- `expectations`: array of objects with `text` (string), `passed` (boolean), `evidence` (string)
- `summary`: object with `passed` (int >= 0), `failed` (int >= 0), `total` (int >= 0), `pass_rate` (number 0.0-1.0)
- `execution_metrics`: object with `tool_calls` (object), `total_tool_calls` (int >= 0), `total_steps` (int >= 0), `errors_encountered` (int >= 0), `output_chars` (int >= 0), `transcript_chars` (int >= 0)
- `timing`: object with `executor_duration_seconds` (number >= 0), `grader_duration_seconds` (number >= 0), `total_duration_seconds` (number >= 0)

Then update `validate_grading_data()` in `validation.py` to load and validate against this schema file (using `jsonschema` or by keeping the current logic but referencing the schema as the source of truth). At minimum, add a comment pointing to the schema file so future developers know where the contract is defined.

Also update `write_placeholder_grading()` in `run_value_proof.py` (line 62-81) to reference or import the schema to stay in sync.
