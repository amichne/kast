# Connect the exact repository

Install the matched Kast distribution and IDEA plugin before connecting an
agent. Follow the release's [installation guide](https://kast.michne.com/start/)
and [connection guide](https://kast.michne.com/agent-harnesses/). The skill and
plugin supply instructions and client configuration; semantic execution still
requires that installed distribution and its supported IDEA host.

## Install the skill or plugin

The release's standalone `kast-skill-v<VERSION>.zip` contains the `kast/` skill
directory. Extract it into `~/.agents/skills/` for personal use, or the intended
repository's `.agents/skills/` for that repository. The standalone skill uses
any already connected Kast transport.

To install the bundled Codex plugin and its marketplace from a matched Kast
installation:

```shell
kast plugin codex
```

The plugin includes this skill and the installed MCP connection. For manual
installation from the source marketplace:

```shell
codex plugin marketplace add amichne/kast
codex plugin add kast@kast
```

For the release's `kast-marketplace-v<VERSION>.zip`, extract the archive, add its
marketplace root with `codex plugin marketplace add /absolute/extracted/marketplace`,
then run `codex plugin add kast@kast`. Use the archive's matching release and
published checksums. The release also provides `kast-plugin-v<VERSION>.zip` as
the plugin payload. Restart the client after installing the plugin.

## Verify binding and tool authority

Select the exact Kotlin Gradle repository or worktree. The installed direct MCP
and Tool RPC processes discover the workspace from their current directory.
Launch the client in that directory. The plugin's managed MCP launcher keeps
the client workspace as its current directory. Do not use the plugin's
installation directory as the semantic workspace.

For an existing direct Codex MCP connection:

```shell
kast connect codex mcp
cd /absolute/path/to/kotlin-repository
codex
```

Start a fresh client session after changing a connection. Inspect MCP
`tools/list`, then call `health_check` and verify its root is the intended
repository. Execute one scoped declaration query and retain its returned
identity, source location, and coverage. A registration or catalog alone proves
neither correct workspace binding nor semantic readiness.

The installed catalog is authoritative for names and arguments. Direct MCP
usually exposes `health_check`, `query_symbols`, `check_diagnostics`,
`add_declaration`, and `replace_body`. Source context is requested with
`query_symbols` symbol output field `SOURCE`. Hosted Codex separately exposes
`workspace_lifecycle`; discover its current conversation catalog.

## One-shot Tool RPC

Run from the selected repository. Use the installed executable's catalog, then
send the chosen example's `value` as one JSON stdin document to `call
query_symbols`:

```shell
cd /absolute/path/to/kotlin-repository
"${KAST_INSTALL_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/kast}/installation/bin/kast-tool-rpc-complete" catalog
"${KAST_INSTALL_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/kast}/installation/bin/kast-tool-rpc-complete" call query_symbols < query.json
```

`query.json` must contain one complete argument document adapted from the
generated examples and admitted by the installed schema. Never execute stored
illustrative handles as though they were issued by the current session.

## Decode the transport and semantic outcome

| Transport | Where to read the canonical document |
| --- | --- |
| Direct MCP | Admitted result's `structuredContent`; inspect `isError` and content for invocation failures. |
| Tool RPC | `document` under `complete`, `qualified`, or `rejected_document`; `rejected` carries a boundary failure. |
| Hosted Codex | The retained canonical document in the installed tool's final envelope. |

Canonical `status` is `complete`, `qualified`, or `rejected`. A successful
transport does not turn a rejected semantic operation into success. Preserve
the outcome's finite failures, scope, qualification, and recovery direction.

If the catalog or input shape differs from these examples, use the installed
schema and report the version boundary. If a tool is missing, the workspace is
wrong, or IDEA preparation rejects, report that blocker before making semantic
claims. Use authorized installation or connection actions to resolve it; normal
query use does not authorize repair or unrelated host changes.
