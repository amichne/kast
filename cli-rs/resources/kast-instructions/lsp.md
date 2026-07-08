# Kast LSP Instructions

Use `kast agent lsp --stdio` when an agent host understands Language Server Protocol
and needs Kotlin semantic operations through a standard LSP adapter.

```sh
kast agent lsp --stdio --workspace-root "$PWD"
```

The adapter speaks LSP over stdio and forwards semantic requests to the active
Kast backend. These instructions assume the installed `kast` binary is on
`PATH`; the workspace must have a ready backend. Pass an absolute
`--workspace-root` when the LSP host launches outside the repository root.

## Startup

For local macOS developer machines, reopen the repository in IntelliJ IDEA or
Android Studio with the Kast plugin enabled if semantic state is missing. The
plugin prepares `.kast/setup/workspace.json` and owns IDEA backend activation:

```sh
kast ready --for agent --workspace-root "$PWD"
```

For hosted Linux agents, warm the headless backend after installing the Linux
headless bundle:

```sh
kast setup --workspace-root "$PWD"
kast developer runtime up --workspace-root "$PWD" --backend headless
```

## Capabilities

During initialization, Kast exposes normal LSP capabilities plus custom
`kast/*` methods under `capabilities.experimental.kastMethods`. CLI-capable
hosts should prefer typed `kast agent` commands unless they specifically need
LSP transport.

Use the built-in LSP methods for standard editor flows such as definition,
references, hover, document symbols, implementations, call hierarchy, type
hierarchy, and rename. Use custom `kast/*` methods when the host can call them
directly and needs catalog-backed workflows such as `symbol/query` or
`database/metrics`.

If initialization reports a stale or indexing runtime, warm the backend and
retry instead of falling back to text search.
