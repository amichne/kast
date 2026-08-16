# Agent marketplace state

Kast's Codex plugin is externally authored and published. Installed payloads,
marketplace snapshots, and runtime caches are observations, not source of
truth for this repository.

| Marketplace | Source | Ref | Plugin |
|---|---|---|---|
| `kast` | `https://github.com/amichne/kast-marketplace` | `main` | `kast@kast` |

The plugin supplies Kast routing guidance plus lifecycle hooks. It does not
embed a Kast runtime. It is intentionally disabled because its routing,
startup hook, diagnostics MCP, and semantic index currently duplicate the
native IntelliJ MCP path.

## Current policy

Keep `kast@kast` and `kast-operations@slopsentral` disabled in Codex. Do not
start the Kast runtime from agent instructions. Kotlin work uses the bundled
IntelliJ MCP server with its full tool catalog.

Re-enable Kast only through an explicit task that demonstrates a capability
the native IntelliJ surface does not provide, identifies the ownership and
startup cost, and supplies a focused reliability proof.

## Re-enable after review

```console
codex plugin marketplace add amichne/kast-marketplace --ref main --json
codex plugin add kast@kast --json
```

Start a new Codex thread after changing plugin state. Do not treat installed
payloads or marketplace caches as repository authority.

## Validation

```console
codex plugin marketplace list
codex plugin list
codex mcp get idea --json
```
