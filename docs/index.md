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

- **Install the CLI**

  ---

  Start a fully independent standalone daemon for terminal work, CI, and
  agent automation.

  [Open the CLI install guide →](getting-started/install.md#one-line-install)

- **Install the IntelliJ plugin**

  ---

  Connect to the IntelliJ-backed runtime and reuse the IDE's
  already-open project model, indexes, and analysis session.

  [Open the plugin install guide →](getting-started/install.md#install-the-intellij-plugin-manually)

</div>

## Two independent runtime modes, one contract

`kast` ships two independent runtime modes. Both expose the same JSON-RPC
contract, so your scripts, agents, and integrations do not need a different
API when you switch between them.

| Runtime mode | What runs | Best when | Why it shines |
| --- | --- | --- | --- |
| **Standalone CLI + daemon** | The `kast` CLI starts a separate backend process outside IntelliJ | You need terminal automation, CI, or a headless agent | It is fully independent, needs no IDE session, and gives you explicit daemon lifecycle control |
| **IntelliJ plugin-backed runtime** | The plugin starts `kast` inside a running IntelliJ project | IntelliJ is already open on the workspace | It piggybacks on the IDE's already-open project model, indexes, and analysis session |

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

```console linenums="1" title="From zero to first result"
# 1. Install the kast CLI
/bin/bash -c "$(curl -fsSL \
  https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh)"
# Or: curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash

# 2. Start the standalone backend (keep it running in background / separate terminal)
kast-standalone --workspace-root=/path/to/your/project

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

- **Get started**

  ---

  Install `kast`, run your first query, and understand the two backends.

  [Install →](getting-started/install.md)

- **See what `kast` can do**

  ---

  Explore every capability with real examples and content tabs.

  [Understand symbols →](what-can-kast-do/understand-symbols.md)

- **Use `kast` from an agent**

  ---

  Give your LLM agent semantic code intelligence it can't get from grep.

  [For agents →](for-agents/index.md)

- **Dive into the architecture**

  ---

  Learn how the daemon, JSON-RPC transport, and K2 Analysis API fit
  together.

  [How it works →](architecture/how-it-works.md)

</div>
