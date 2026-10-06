# Tool RPC for agent extensions

`kast-tool-rpc` is a one-shot process interface for Kast's IDEA-backed tools.
It does not speak MCP or connect to the Codex App Server. The process uses its
current working directory to discover the exact Gradle workspace, then uses
Kast's existing IDEA preparation and semantic operation path.

The installed command is
`${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-tool-rpc-complete`.
Both adapters below call this command directly. Set `KAST_TOOL_RPC_COMMAND` to an
absolute executable path when trying a checkout build.

## Contract

`catalog` writes one UTF-8 JSON object to stdout. Its `type` is `catalog`, and
`catalog.tools` contains each tool's name, description, generated input schema,
and `READ` or `WRITE` effect. The schema version is `2`; the catalog also gives
the call deadline and response ceiling used by bundled adapters.

```shell
"$HOME/.local/share/kast/installation/bin/kast-tool-rpc-complete" catalog
```

`call NAME` reads one JSON object from stdin (at most 1 MiB) and writes one
JSON object to stdout. Its `type` is `complete`, `qualified`,
`rejected_document`, or `rejected`. The first three retain the canonical Kast
result under `document`; `rejected` carries a closed boundary `failure`.
Qualified results are partial evidence, and a rejected result does not prove
absence. The tool name must appear in the current catalog.

```shell
printf '%s\n' '{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderService"}}}' |
  "${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-tool-rpc-complete" call query_symbols
```

Use the exact `ref` and qualification returned by one tool in follow-up calls.
The `add_declaration` tool is marked `WRITE`; it plans, applies, verifies, and attempts
recovery within one call. Clients must request write approval for it.

## Copilot CLI

Register the verified bundled adapter at user scope:

```shell
kast connect copilot
copilot --experimental
```

For a custom Copilot home, set `COPILOT_HOME` or pass
`kast connect copilot --destination /absolute/copilot/home`.

The extension obtains the live catalog and registers each tool with the Copilot
SDK. All tools use Copilot's normal permission policy. The extension does not
request permission to bypass tool prompts when it loads. No Copilot MCP server is needed.

## Pi

Register the bundled Pi adapter:

```shell
kast connect pi
```

The first connection respects `PI_CODING_AGENT_DIR`. You can also select its
agent home with `kast connect pi --destination /absolute/pi/agent`.
All connection commands accept `--destination` and retain the selected directory
in `~/.config/kast/config.json` for reconnect, upgrade, and disconnect. Start the
harness with the same home setting so it discovers the installed connection.

By default, connecting recovers a conflicting Kast slot from the verified release
and retains a private backup of the displaced bytes. Failed transactions restore
the prior connection and Kast configuration. Use `--no-recover` to reject a
conflict without replacement.

It registers the same live catalog in Pi.
For `add_declaration`, it asks for interactive approval and refuses the call without an
approving UI. Both clients pass their current workspace to the same RPC
command, so one Kast installation serves different repositories and worktrees.
