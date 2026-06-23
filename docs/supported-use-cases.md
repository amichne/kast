---
title: Supported use cases
description: Where Kast fits best for developer machines, Copilot workflows,
  and headless agent servers.
icon: lucide/check-circle
---

# Supported use cases

Kast is for Kotlin work where an agent needs compiler-backed evidence before
it acts. It does not replace the IDE for typing code. It gives terminal,
Copilot, CI, and hosted-agent workflows the semantic answers that text search
cannot prove.

## Install scope decides the workflow

Most confusion comes from mixing machine setup with repository setup. Treat
developer machines, repository files, and Linux servers as separate concerns.

??? success "Global binary and IDE plugin: one macOS machine"
    Install the `kast` binary and IDEA or Android Studio plugin once on the
    machine. On macOS developer machines, the Homebrew formula installs the
    CLI and the version-coupled `kast-plugin` cask:

    ```console
    brew tap amichne/kast
    brew install kast
    ```

    The binary owns CLI commands, `kast lsp --stdio`, `kast rpc`, install
    repair, and backend lifecycle commands. The cask links the Kast plugin
    into local JetBrains IDE profiles; run `brew install --cask kast-plugin`
    or `brew reinstall --cask kast-plugin` only for direct cask repair.

??? tip "Repository Copilot files: one repository at a time"
    Run `kast install copilot` inside every repository where Copilot should
    use Kast:

    ```console
    cd /path/to/your/repository
    kast install copilot
    ```

    This writes managed files under `.github`: the LSP config, Kotlin
    instructions, and catalog-backed extension tools. Restart the IDE after
    installing or refreshing them.

??? info "IDEA backend: developer machines use the IDE"
    On macOS developer machines, Kast expects IDEA or Android Studio with the
    Homebrew-managed plugin. The IDE backend reuses the open project model and
    indexes instead of asking local agents to run a Linux-style headless
    runtime.

    ```console
    kast install plugin
    ```

    Use `kast install plugin` to repair Homebrew-managed profile links after
    moving, upgrading, or replacing a JetBrains IDE profile.

??? question "Headless server: give hosted agents their own runtime"
    Use the Ubuntu/Debian headless bundle for CI images, hosted agents, and
    servers with no developer IDE. It installs the binary, config, and bundled
    headless runtime on that Linux machine. It is a server path, not a local
    macOS developer-machine substitute.

## Where Kast excels

These are the jobs Kast is built to make boring for an agent. The value is not
that the agent tries harder; it is that the agent asks the compiler-backed
system for the parts it should not infer.

| Use case | Why Kast helps | First command surface |
|----------|----------------|-----------------------|
| Resolve a symbol before editing | Confirms the exact declaration, kind, and location | Copilot `kast_*` tool or `kast rpc` |
| Find references | Reports whether the candidate search was exhaustive | `raw/references` or `symbol/references` |
| Inspect callers | Returns bounded call hierarchy metadata instead of silent truncation | `raw/call-hierarchy` or `symbol/callers` |
| Plan a rename | Produces edit plans with file hashes before writing | `raw/rename` then `raw/apply-edits` |
| Check diagnostics | Lets an agent validate changed files without running the whole build first | `raw/diagnostics` |

## What to let the agent handle

The first install should stay short. After the global binary and
repository-local Copilot files exist, the agent can handle the detailed
workflow mechanics.

- Resolve the target symbol before expanding references or callers.
- Choose `symbol/*` helper routes when the user gives a name instead of an
  offset.
- Fall back to `kast rpc` when a host does not expose native `kast_*` tools.
- Read completeness and truncation metadata before claiming certainty.
- Re-run stale repository installs with `kast install copilot --force` when
  the managed files are out of date.

## Where to be explicit

Kast should be part of the prompt when correctness depends on Kotlin semantics.
Name the evidence you expect instead of asking the agent to “look around.”

```text title="Prompt shape"
Use Kast to resolve OrderService.processOrder first. Confirm the fully
qualified name and declaration file, then find references and say whether
the search was exhaustive.
```

Use normal text search for plain text, comments, docs, and non-Kotlin file
discovery. Use Kast when overloaded symbols, inherited members, call graphs,
rename plans, or diagnostics matter.
