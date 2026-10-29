# Kast agent tools

This plugin supplies the Kast skill and task tools for Codex, Copilot, and Pi.
The only bundled MCP entry exposes the task catalog in `skills/kast/scripts/tasks.py`.
It does not expose `query_symbols`, `check_diagnostics`, or mutation names as separate MCP tools.
The installed tool RPC keeps its existing binary and wire contracts.

## Connect an agent

Install Kast and restart IntelliJ IDEA before a semantic query.
Start your agent from the exact Kotlin repository or worktree.

For Codex, run `kast plugin codex` and restart Codex.
Review and trust the plugin hook. Hooks without trust do not deliver the brief.
For this checkout, use `codex plugin marketplace add .` and `codex plugin add kast@kast`.

For Copilot, install the bundled plugin:

```sh
copilot plugin install "$HOME/.local/share/kast/installation/share/kast/agent-tools/plugins/kast"
```

If the RPC extension already occupies the Kast slot, remove it with `kast disconnect copilot` first.
Restart Copilot after installation.
The optional `kast connect copilot` SDK extension uses the same task catalog and service.
Use one Copilot integration at a time.

For Pi, run `kast connect pi` and restart Pi.
The installed extension registers the bundled task server through `registerMcpServer`.
Pi owns discovery, permissions, and the connection lifetime.
Use a Pi version with native MCP support.
The extension supplies the brief and the absolute path to the bundled skill.

## Runtime and task contracts

The task server needs uv 0.12.9 or later. It uses Python 3.12 or 3.13.
Its locked script can download pinned dependencies on the first run.
Codex and Copilot launch that script from the plugin root.
Pi and the Copilot SDK extension use the installed plugin directory.

The context hooks remain passive and offline. They use Python 3.9 or later. They need only the standard library.
Pi reads context directly with Node.
Context delivery does not start semantic work. The installed RPC prepares the exact project when a task needs it.
Preparation can save editor buffers and refresh the imported project.
An invalid context resource rejects without a partial brief.

Use `find` before relationship tasks and preserve the selected issued reference.
Use `source` with its default `ANCHOR` before a body region.
Use `walk` for an explicit depth and `trace` for the fixed affected-use topology.
Use `result` and `resume` for existing output.
The [bundled skill](skills/kast/SKILL.md) explains task choices, scope, qualifiers, recovery, and CLI fallback.

For an advanced existing operation, read `schema` and then use `raw`.
Catalog-declared writes need `write:true` and a user-requested source change.
The installed catalog admits requests for contract 17 or 18. Other versions reject.
Canonical RPC text, structured evidence, references, cursors, and failure tags remain intact.
Cancellation stops the child. Submission or effects can remain uncertain, so the service never retries automatically.

`onboarding.template.md` owns the shared brief. `skills/kast/SKILL.md` owns the detailed task workflow.
`packaging/generate-public-query.py` owns the advanced query references from the canonical schema.
The service and native adapter consume one task catalog, without separate query constructors.
The historical direct adapters remain compatibility source and are not the packaged defaults.

## Development evidence

Run the task suite from `packaging/task-access` with `uv run --locked pytest`.
Run `node --test cli/src/test/js/native-onboarding.test.mjs` for Pi registration and context cases.
Run `python3 packaging/test-agent-onboarding.py` for Codex and Copilot hook envelopes.
These tests prove adapter behavior, not a complete live-host or model comparison.
The [original experiment](../experiments/task-access/README.md) records separate Terra and Luna exercises.

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

The source plugin version is `0.0.0`. Release assembly supplies the Control version.
Tool failures keep their canonical rejection and recovery evidence.
Local admission failures use `ACCESS_REJECTED` with `isError:true`. Canonical RPC outcomes keep their original tags.
