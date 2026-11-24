# Kast agent guidance

This directory owns the canonical Kast skill for the native `kast` command.
The build projects it into one plugin for Claude Code and Codex, with the session hook and an optional MCP server.
Kast writes no agent configuration. Each agent installs the plugin with its own commands.

## Export the skill

```sh
kast install skill
kast install skill --output ./guidance
```

The output directory is the parent of `kast/`.
The default parent is `./.agents/skills`.
A complete identical skill returns `ALREADY_INSTALLED` without writes.
A different or incomplete skill returns `SKILL_EXISTS` and keeps every byte.
Select another output directory or explicitly remove the old skill before another export.
Each exported skill records the Control version in `metadata.version`.

## Connect an agent

An installation holds two marketplaces, each with the plugin `kast@kast`:

| Directory | Plugin content |
| --- | --- |
| `share/kast/agent-tools` | Skill and session hook |
| `share/kast/agent-tools/compositions/mcp` | Skill, session hook, and MCP server |

`.codex-plugin/plugin.json` is the one authored identity. The generator derives the Claude Code manifest and marketplace from it.

```sh
claude plugin marketplace add <directory> && claude plugin install kast@kast
codex plugin marketplace add <directory> && codex plugin add kast@kast
```

Start a fresh agent session. A completed plugin installation does not establish content in an existing agent session.
Codex runs the session hook only after the user trusts it.
The [agent setup guide](../docs/public/reference/integrations.mdx) gives the complete procedure.

## Workspace preparation

The first explicit semantic request trusts the exact canonical workspace root and prepares its private IntelliJ IDEA Host.
The CLI and MCP share that managed runtime. Use a workspace whose build code you trust.

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

Follow-up source reads, retained-result reads, and queries from issued references use the existing workspace.
They preserve the original authority and reject stale references without causing a model reload.
Use `kast diagnostic check --path src/main/kotlin --json` to inspect compiler diagnostics.
Use `--path .` explicitly for the workspace root. Incomplete coverage does not establish a clean scope.
Set `KAST_DEBUG=1` to send preparation and response diagnostics to stderr.
Normal commands keep these diagnostics silent. Library logs report errors only.

`kast status` reports the running coordinator's version, or `COORDINATOR_NOT_RUNNING`.
`BROKER_NOT_OBSERVED` marks Codex facts that the running coordinator does not observe. Neither reason establishes workspace readiness.

## Contract and recovery

Use `kast describe` for the installed CLI contract.
The [canonical skill](skills/kast/SKILL.md) owns scope, issued references, qualifications, cancellation, and recovery guidance.
The [public CLI guide](skills/kast/public-cli.md) supplies shell examples.

## Development evidence

Run `./gradlew -p build-logic test --tests '*GenerateAgentToolsTaskTest'` for composition and resource admission.
Run `./gradlew :distribution:cli:test --tests '*SkillExportTest'` for skill export.
Run `./gradlew :cli:test --tests '*PublicSkillCommandDriftTest'` to check the commands that the skill teaches.
These tests do not establish a live semantic query or model behavior.
