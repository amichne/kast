# Kast LSP Instructions

Use `kast agent lsp --stdio` when an agent host understands Language Server Protocol
and needs Kotlin semantic operations through a standard LSP adapter.

```sh
kast agent lsp --stdio
```

The adapter speaks LSP over stdio and forwards semantic requests to the active
Kast backend. These instructions assume the installed `kast` binary is on
`PATH`; the workspace must have a ready backend.

## Startup

For local developer machines, warm the IDEA backend before LSP startup if
semantic state is missing:

```sh
kast runtime up --workspace-root "$PWD" --backend idea
```

For hosted Linux agents, warm the headless backend after installing the Linux
headless bundle:

```sh
kast runtime up --workspace-root "$PWD" --backend headless
```

## Capabilities

During initialization, Kast exposes normal LSP capabilities plus custom
`kast/*` methods under `capabilities.experimental.kastMethods`. Those custom
methods are generated from `cli-rs/resources/kast-skill/references/commands.json`.

Use the built-in LSP methods for standard editor flows such as definition,
references, hover, document symbols, implementations, call hierarchy, type
hierarchy, and rename. Use custom `kast/*` methods when the host can call them
directly and needs catalog-backed workflows such as `symbol/query` or
`database/metrics`.

If initialization reports a stale or indexing runtime, warm the backend and
retry instead of falling back to text search.
