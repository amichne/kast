---
title: Kast
description: Compiler-backed Kotlin analysis for your terminal, CI, agent,
  or IntelliJ-backed workflow.
icon: lucide/network
---

# IDE-grade Kotlin code intelligence — anywhere you need it

`kast` gives you compiler-backed Kotlin answers outside plain text search.
Use it when `grep` or `rg` can show you where a name appears, but you need to
know which declaration it resolves to, which callers are real, or whether an
edit plan is safe to apply.

Choose the entry point that fits your workflow:
<div class="grid cards" markdown>

-   :material-console:{ .lg .bottom } __Install the CLI__

    ---

    Start a fully independent standalone daemon for terminal work, CI, and
    agent automation.

    [:octicons-arrow-right-16: CLI install guide](getting-started/install.md#one-line-install)

-   :octicons-plug-16:{ .lg .left } __Install the IntelliJ plugin__

    ---

    Connect to the IntelliJ-backed runtime and reuse the IDE's
    already-open project model, indexes, and analysis session.

    [:octicons-arrow-right-16: Plugin install guide](getting-started/install.md#install-the-intellij-plugin-manually)

</div>

## Two independent runtime modes, one contract

`kast` ships two independent runtime modes. Both expose the same JSON-RPC
contract, so your scripts, agents, and integrations do not need a different
API when you switch between them.

| Runtime mode                       | What runs                                                         | Best when                                             | Why it shines                                                                                  |
|------------------------------------|-------------------------------------------------------------------|-------------------------------------------------------|------------------------------------------------------------------------------------------------|
| **Standalone CLI + daemon**        | The `kast` CLI starts a separate backend process outside IntelliJ | You need terminal automation, CI, or a headless agent | It is fully independent, needs no IDE session, and gives you explicit daemon lifecycle control |
| **IntelliJ plugin-backed runtime** | The plugin starts `kast` inside a running IntelliJ project        | IntelliJ is already open on the workspace             | It piggybacks on the IDE's already-open project model, indexes, and analysis session           |

For a deeper comparison, read [Backends](getting-started/backends.md).

## Why `kast` matters

`kast` replaces guesswork with compiler-backed answers. These capabilities make
it useful when text search is not enough:

- **Symbol identity, not text matching** — `kast` resolves the exact compiler
  declaration at a position and returns its fully qualified name, kind, and
  location. [Learn more →](what-can-kast-do/understand-symbols.md)
- **Exhaustive reference search** — Every reference result includes
  `searchScope.exhaustive`, proving whether every candidate file was
  searched. [Learn more →](what-can-kast-do/trace-usage.md)
- **Bounded call hierarchy** — Call trees include explicit depth, fan-out,
  and timeout limits with truncation metadata on every node.
  [Learn more →](what-can-kast-do/trace-usage.md#expand-the-call-hierarchy)
- **Safe mutations** — Rename uses a two-phase plan→apply flow with
  SHA-256 file hashes for conflict detection.
  [Learn more →](what-can-kast-do/refactor-safely.md)

`kast` complements your editor's LSP and your usual text search. Use it when
the workflow happens outside the editor or when you need compiler-backed
answers instead of string matches.
[Full comparison →](architecture/kast-vs-lsp.md)

## Get running in 60 seconds

Install the CLI, start a backend, then run your first query.

```console linenums="1" title="Download, start, query" hl_lines="1 2 3"
# 1. Install the kast CLI
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash

# 2. Start the standalone backend (keep it running in background / separate terminal)
kast daemon start --workspace-root=/path/to/your/project

# 3. Resolve a symbol (in another shell, once the backend is READY)
kast resolve \
  --workspace-root=/path/to/your/project \
  --file-path=/path/to/your/project/src/main/kotlin/App.kt \
  --offset=42
```

Three steps: install, start backend, query. The backend stays warm for
subsequent commands, so everything after the first start is fast.
If IntelliJ with the plugin is already open on the project, skip step 2 —
`kast` connects to the IDE's backend automatically.

## Next steps

<div class="grid cards" markdown>

-   :octicons-download-24:{ .lg .middle } **Get started**

    ---

    Install `kast`, run your first query, and understand the two backends.

    [:octicons-arrow-right-24: Install](getting-started/install.md)

-   :octicons-zap-24:{ .lg .middle } **See what `kast` can do**

    ---

    Explore every capability with real examples and content tabs.

    [:octicons-arrow-right-24: Understand symbols](what-can-kast-do/understand-symbols.md)

-   :octicons-copilot-24:{ .lg .middle } **Use `kast` from an agent**

    ---

    Give your LLM agent semantic code intelligence it can't get from grep.

    [:octicons-arrow-right-24: For agents](for-agents/index.md)

-   :octicons-gear-24:{ .lg .middle } **Dive into the architecture**

    ---

    Learn how the daemon, JSON-RPC transport, and K2 Analysis API fit
    together.

    [:octicons-arrow-right-24: How it works](architecture/how-it-works.md)

</div>
