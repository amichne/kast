# Kast agent tools

This plugin supplies MCP tools and a compact brief at session start.
Codex and Copilot use hooks. Pi uses an extension with native MCP support.
Each host gets the same brief, its workspace path, and absolute paths to the schema and examples.
Kast does not publish a discoverable skill or a standalone skill archive.

Install Kast and restart IntelliJ IDEA before a semantic query.
Start your agent from the exact Kotlin repository or worktree.
Preparation can save editor buffers and refresh the imported project.

Codex also uses a background hook to prepare the nearest Kotlin Gradle project within the Git checkout.
It skips other repositories and session compaction.
The setup hook reports indexed readiness only for the exact project.
Kast uses its private socket by default.

## Codex

```sh
kast plugin codex
```

Restart Codex after installation. Review and trust the plugin hook in Codex.
Codex skips untrusted hooks. Installation alone does not prove context delivery.
The hook also restores the brief after compaction.

For this checkout's marketplace, use:

```sh
codex plugin marketplace add .
codex plugin add kast@kast
```

## Copilot

Install the bundled plugin with Copilot's plugin manager:

```sh
copilot plugin install "$HOME/.local/share/kast/installation/share/kast/agent-tools/plugins/kast"
```

If `kast connect copilot` installed the RPC extension, remove that registration first with `kast disconnect copilot`.
Restart Copilot after installation. Copilot owns its plugin cache and removal.
The plugin supplies MCP tools and a `sessionStart` hook.

## Pi

```sh
kast connect pi
```

Restart Pi after registration.
The installed extension registers `kast-mcp-complete` through Pi's native `registerMcpServer` API.
It reads the shared context at `session_start` and adds it to each model request's system prompt.
Pi owns MCP discovery, permissions, and the connection lifetime.
Use a Pi version with native MCP support.

## Runtime and references

The context hooks need `uv` and Python 3.11 through 3.14.
The Codex setup hook needs Python 3.12 or 3.13 and its locked Pydantic dependency.
Install `uv` on `PATH` or at `~/.local/bin/uv`. Pi uses `~/.local/bin/uv`.
The locked context script uses only the Python standard library and runs offline.
It reads bundled resources without IDE calls, workspace writes, or model requests.
An invalid event, absent resource, or oversized brief rejects without partial context.
The host decides whether a hook failure stops the session.

`onboarding.template.md` owns the prose.
`packaging/generate-public-query.py` derives enum lists, the query schema, and the named example array from `tools.schema.json`.
The brief explains that interfaces use `declarationKinds:["CLASS"]`.
It distinguishes relative directory paths, Kotlin packages, exact source-set names, and issued references.
The complete schema and examples stay on disk until the agent needs them.

The source plugin version is `0.0.0`. Release assembly supplies the Control version.
Tool failures keep their canonical rejection and recovery evidence.
MCP transport rejections use `MCP_REJECTED` or `BOUNDARY_REJECTED` with `isError:true`.

Host contracts: [Codex plugins](https://developers.openai.com/plugins/build/plugins),
[Codex hooks](https://learn.chatgpt.com/docs/hooks),
[Copilot plugins](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-plugin-reference),
[Copilot hooks](https://docs.github.com/en/copilot/reference/hooks-reference),
and [Pi MCP](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/mcp.md).
