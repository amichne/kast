---
title: Kast
description: Compiler-backed Kotlin analysis for your terminal, CI, agent,
  or IntelliJ-backed workflow.
icon: lucide/network
---

# Compiler-backed Kotlin answers — outside the IDE

`grep` finds where a name appears. `kast` tells you which declaration it
resolves to, who actually calls it, and whether the rename you're about to
apply still matches what's on disk. Same K2 engine your IDE uses, exposed
as a daemon you can drive from a shell, a CI job, or an LLM agent.

Pick your entry point:
<div class="grid cards" markdown>

-   :material-console:{ .lg .bottom } __Install the CLI__

    ---

    A standalone JVM daemon you start from a terminal. Works in CI, in a
    container, on a server with no editor in sight.

    [:octicons-arrow-right-16: CLI install guide](getting-started/install.md#one-line-install)

-   :octicons-plug-16:{ .lg .left } __Install the IntelliJ plugin__

    ---

    Reuse the analysis session IntelliJ already has open. No second JVM,
    no second cold start, same JSON-RPC over a socket.

    [:octicons-arrow-right-16: Plugin install guide](getting-started/install.md#install-the-intellij-plugin-manually)

</div>

## Two runtimes, one wire format

The standalone daemon and the IntelliJ plugin speak the same JSON-RPC.
Switch backends without changing a single script or prompt.
[Compare backends →](getting-started/backends.md)

## What `kast` does that text search can't

- **Resolves the symbol, not the string.** Two functions named `process`
  in different classes are two different `fqName`s. Overloaded calls
  resolve to the right overload because the compiler picked it.
  [How resolve works →](what-can-kast-do/understand-symbols.md)
- **Proves the reference list is complete.** Every `references` response
  carries `searchScope.exhaustive`. When it's `true`, every candidate
  file in the workspace was actually searched — not sampled, not
  truncated. [Trace usage →](what-can-kast-do/trace-usage.md)
- **Tells you where the call tree stopped, and why.** Depth, fan-out,
  total edges, timeout — all configurable, all reported back. No silent
  truncation. [Bounded hierarchies →](what-can-kast-do/trace-usage.md#expand-the-call-hierarchy)
- **Refuses to apply a stale edit.** Rename returns SHA-256 hashes of
  every file it read. If anything drifted before you apply, the daemon
  rejects the write instead of corrupting your tree.
  [Plan-then-apply →](what-can-kast-do/refactor-safely.md)

Your editor's LSP is still better for keystroke feedback. `kast` is for
the work that happens outside the editor — CI gates, scripted refactors,
agents that need to answer "is this list complete?" with a yes or no.
[Full comparison →](architecture/kast-vs-lsp.md)

## 60 seconds to a real answer

Three commands, run from the root of any Kotlin project. Requires Java 21+.

```console linenums="1" title="Install, start, query" hl_lines="1 2 3"
# 1. Install the kast CLI
curl -fsSL https://raw.githubusercontent.com/amichne/kast/HEAD/kast.sh | bash

# 2. Start a backend for this workspace (waits until indexing is READY)
kast workspace ensure --backend-name=standalone --workspace-root=$(pwd)

# 3. Resolve a symbol — point at any .kt file and any byte offset on a name
kast resolve \
  --workspace-root=$(pwd) \
  --file-path=$(pwd)/src/main/kotlin/App.kt \
  --offset=42
```

The first `workspace ensure` is the slow command — the daemon discovers
your project and indexes it. Everything after that hits a warm session.
Already running IntelliJ with the plugin? Skip step 2; `kast` finds the
IDE's backend on its own.

## Next steps

<div class="grid cards" markdown>

-   :octicons-download-24:{ .lg .middle } **Install**

    ---

    Pick a backend, get the binary on your `PATH`, verify it answers.

    [:octicons-arrow-right-24: Install](getting-started/install.md)

-   :octicons-zap-24:{ .lg .middle } **What `kast` actually does**

    ---

    Every capability with real CLI calls, real JSON, and the gotchas
    that bite first-time users.

    [:octicons-arrow-right-24: Understand symbols](what-can-kast-do/understand-symbols.md)

-   :octicons-copilot-24:{ .lg .middle } **From an agent**

    ---

    Give your LLM something stronger than `grep` and a file system —
    bounded answers it can quote with proof.

    [:octicons-arrow-right-24: For agents](for-agents/index.md)

-   :octicons-rocket-24:{ .lg .middle } **Recipes**

    ---

    Copy-paste workflows for the things you actually do: find callers,
    rename a symbol, gate CI on diagnostics.

    [:octicons-arrow-right-24: Recipes](recipes.md)

</div>
