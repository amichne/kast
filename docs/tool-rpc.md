# Tool RPC for agent extensions

`kast-tool-rpc` is a one-shot process interface for Kast's IDEA-backed tools.
It does not speak MCP or connect to the Codex App Server. The process uses its
current working directory to discover the exact Gradle workspace, then uses
Kast's existing IDEA preparation and semantic operation path.

The installed command is
`${XDG_DATA_HOME:-$HOME/.local/share}/kast/current/bin/kast-tool-rpc-complete`.
Both adapters below call this command directly. Set `KAST_TOOL_RPC_COMMAND` to an
absolute executable path when trying a checkout build.

## Contract

`catalog` writes one UTF-8 JSON object to stdout. Its `type` is `catalog`, and
`catalog.tools` contains each tool's name, description, generated input schema,
and `READ` or `WRITE` effect. The schema version is `1`.

```shell
"$HOME/.local/share/kast/current/bin/kast-tool-rpc-complete" catalog
```

`call NAME` reads one JSON object from stdin (at most 1 MiB) and writes one
JSON object to stdout. Its `type` is `complete`, `qualified`,
`rejected_document`, or `rejected`. The first three retain the canonical Kast
result under `document`; `rejected` carries a closed boundary `failure`.
Qualified results are partial evidence, and a rejected result does not prove
absence. The tool name must appear in the current catalog.

```shell
printf '%s\n' '{"name":"OrderService"}' |
  "${XDG_DATA_HOME:-$HOME/.local/share}/kast/current/bin/kast-tool-rpc-complete" call search_classes
```

Use the exact `ref` and qualification returned by one tool in follow-up calls.
The `change` tool is marked `WRITE`; it plans, applies, verifies, and attempts
recovery within one call. Clients must request write approval for it.

## Copilot CLI

Copy the installed adapter into the user extension directory:

```shell
mkdir -p "$HOME/.copilot/extensions/kast"
cp "${XDG_DATA_HOME:-$HOME/.local/share}/kast/current/share/kast/adapters/copilot/extension.mjs" \
  "$HOME/.copilot/extensions/kast/extension.mjs"
copilot --experimental
```

The extension obtains the live catalog and registers each tool with the Copilot
SDK. Read tools skip per-call permission prompts; `change` retains the Copilot
permission prompt. No Copilot MCP server is needed.

## Pi

Copy the installed adapter into Pi's user extension directory:

```shell
mkdir -p "$HOME/.pi/agent/extensions"
cp "${XDG_DATA_HOME:-$HOME/.local/share}/kast/current/share/kast/adapters/pi/extension.ts" \
  "$HOME/.pi/agent/extensions/kast.ts"
```

It registers the same live catalog in Pi.
For `change`, it asks for interactive approval and refuses the call without an
approving UI. Both clients pass their current workspace to the same RPC
command, so one Kast installation serves different repositories and worktrees.
