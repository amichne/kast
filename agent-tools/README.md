# Kast agent guidance

The default plugin supplies canonical guidance for the native `kast` command.
It does not register MCP or install Python hooks.
Its session hook calls `kast hook session-start --wait none` to start preparing supported Gradle projects in IntelliJ IDEA.
Non-project directories skip preparation. Invalid events and failed preparation return a nonzero status.
The persistent IntelliJ IDEA Host owns Kotlin semantics for CLI and MCP.

## Install guidance

After core installation, select one harness:

```sh
kast install codex
kast install copilot
kast install pi
```

Codex and Copilot own their plugin resources and configuration.
Pi discovers the copied skill through its native skill directory.
Pi also installs the session extension that calls the same command.
Pi starts preparation in the background during session startup.
Each harness gives the model context that distinguishes preparation from readiness and rejection.
Pi includes that context before the next agent turn. Rejected preparation also produces a warning.
Each command examines the selected installed release and the provider's actual resources.
Start a fresh agent session after setup.
Codex needs trust for the installed hook definition before it runs.

Use `kast hook session-start --workspace /absolute/project/path --wait none` to start preparation from a terminal.
Without `--workspace`, the command reads a host event with an absolute `cwd` from standard input.
Hook replies report `preparing` or `ready` and the selected preparation allowance. They ignore `KAST_OUTPUT_TOON`.
Codex receives `hookSpecificOutput.additionalContext`; Copilot receives `additionalContext`.
`--host codex` and `--host copilot` select those envelopes.

Wait explicitly when a task needs a ready workspace:

```sh
kast workspace wait --workspace /absolute/project/path --wait 30000
kast workspace wait --workspace /absolute/project/path --wait indefinite
```

The first command permits 30000 milliseconds. The second has no preparation deadline.
Both report their wait mode before preparation. Cancellation, native failure, a changed host, and editor closure end the wait.
A preparation timeout returns exit status 124. It does not prove that native import work stopped.
Inspect current workspace state before repeating an uncertain lifecycle effect.

Set `KAST_WORKSPACE_PREPARATION_WAIT` to positive milliseconds or `indefinite` for the default allowance.
Each wait keeps its own allowance when it joins existing preparation.
The setting also supports saved installation and workspace configuration. Explicit `--wait` takes precedence.
Without a setting or flag, the existing 1020000-millisecond allowance applies.
Background hooks return after native admission; their launch deadline does not bound an explicit workspace wait.

Follow-up source reads, retained-result reads, and queries from issued references use the existing workspace.
They preserve the original authority and reject stale references without causing a model reload.
Use `kast diagnostic check --path src/main/kotlin --json` to inspect compiler diagnostics.
Use `--path .` explicitly for the workspace root. Incomplete coverage does not establish a clean scope.
Set `KAST_DEBUG=1` to send preparation and response diagnostics to stderr.
Normal commands keep these diagnostics silent. Library logs report errors only.

`kast status` reports `BROKER_NOT_OBSERVED` when private-endpoint configuration lacks broker state.
This reason describes broker evidence. It does not report a failed compiler epoch or establish workspace readiness.

To select optional MCP resources, add `--mcp`:

```sh
kast install codex --mcp
kast install copilot --mcp
kast install pi --mcp
```

The optional task service needs uv and Python 3.12 or 3.13.
Its script locks own the dependency versions.
CLI-only setup removes only exactly owned MCP resources.
Foreign registrations remain unchanged.

## Export the canonical skill

```sh
kast install skill
kast install skill --output ./guidance
```

The output directory is the parent of `kast/`.
The default parent is `./.agents/skills`.
A complete identical skill returns `ALREADY_INSTALLED` without writes.
A different or incomplete skill returns `SKILL_EXISTS` and keeps every byte.
Select another output directory or explicitly remove the old skill before another export.

After installation or upgrade, Kast refreshes guidance for the harness integrations that it owns.
Kast replaces the recorded Pi skill directory as a unit.
Codex and Copilot keep control of their plugin resources.
Each bundled skill records the Control version in `metadata.version`.
If the result reports `HARNESS_REFRESH_REQUIRED`, repeat `kast install <harness>` before a fresh session.
Standalone exports keep their no-overwrite contract.

## Contract and recovery

Use `kast describe` for the installed CLI contract.
The [canonical skill](skills/kast/SKILL.md) owns scope, issued references, qualifications, cancellation, and recovery guidance.
The [public CLI guide](skills/kast/public-cli.md) supplies shell examples.

Optional MCP exposes the task catalog in `skills/kast/scripts/tasks.py`.
The installed tool RPC keeps its binary and wire contracts.
A successful provider installation does not establish content in an existing agent session.

## Development evidence

Run `./gradlew -p build-logic test --tests '*GenerateAgentToolsTaskTest'` for composition and resource admission.
Run `./gradlew :distribution:cli:test --tests '*GuidanceInstallationTest'` for provider resources and exact ownership.
Run `node --test cli/src/test/js/native-onboarding.test.mjs` for the optional Pi adapter.
Run the task suite from `packaging/task-access` with `uv run --locked pytest`.
These tests do not establish a live semantic query or model behavior.

## Pi inventory example

Read the tool's declared result shape before field access.
Inside Pi code, `tools.read({path})` returns a string for text files.
`tools.bash({command})` returns `output`, `exit_code`, and `truncated`.
Filter large results before `text()` or `store()`.
For an existing Bash inventory, `directoryPrefix` must end with `/`:

```javascript
const { exit_code, truncated } = result;
if (exit_code !== 0 || truncated !== false) {
  text({ exit_code, truncated, counts: "UNAVAILABLE" });
} else {
  const matches = result.output.split("\n").filter(path => path.startsWith(directoryPrefix));
  const paths = matches.slice(0, 20);
  text({ exit_code, truncated, matchCount: matches.length, omittedCount: matches.length - paths.length, paths });
}
```

Counts cover only supplied output.
Failed or truncated inventories grant no path or count claims.
The example stays outside the startup brief and receives separate executable assertions.
