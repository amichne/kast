# Kast agent guidance

The default plugin supplies canonical guidance for the native `kast` command.
It does not register MCP or install Python hooks.
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
Each command examines the selected installed release and the provider's actual resources.
Start a fresh agent session after setup.

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

Core replacement does not refresh copied skills or cached provider resources.
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
