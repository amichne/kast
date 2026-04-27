# Ubiquitous Language

## Product model

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Kast** | A compiler-backed Kotlin analysis system that answers semantic questions through a stable JSON-RPC contract. | Toolchain, daemon, plugin |
| **CLI** | The `kast` command-line entrypoint that routes requests to a running backend. | App, backend, server |
| **Backend** | A long-lived analysis runtime that serves Kast requests for one workspace. | Server, daemon, CLI |
| **Runtime mode** | The hosting path for a backend: standalone or IntelliJ plugin-backed. | Backend type, install mode |
| **Standalone backend** | The independent JVM backend started with `kast-standalone` for headless work. | CLI backend, local server, plugin |
| **Daemon** | The running standalone backend process that keeps semantic state warm across commands. | Backend mode, service |
| **IntelliJ plugin-backed runtime** | The backend hosted inside an open IntelliJ project that reuses the IDE's warm state. | Plugin, IDE mode, local backend |

## Workspace and analysis

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Workspace** | The root path Kast analyzes as one bounded unit of code and configuration. | Project, repo, checkout |
| **Analysis session** | The warm Kotlin semantic state Kast builds for a workspace. | Cache, index |
| **Descriptor** | A discovery record that tells external clients how to reach a running backend. | Manifest, socket |
| **Capability surface** | The set of operations a running backend truthfully advertises. | Feature list, support mode |
| **Semantic query** | A compiler-backed read operation that asks for facts about code in a workspace. | Grep, text search, lookup |
| **Planned mutation** | An edit workflow that separates planning from applying so stale state can block unsafe writes. | Rewrite, blind patch, search-and-replace |

## Core operations

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Resolve** | A semantic query that identifies the exact declaration referenced at a position. | Find, locate, grep |
| **References** | A semantic query that returns real usages of a declaration inside the workspace. | Matches, hits, text results |
| **Call hierarchy** | A bounded semantic traversal of caller or callee relationships for executable symbols. | Call graph, stack trace |
| **Diagnostics** | Compiler-backed problems reported for files in the workspace. | Lint, warnings list |
| **Rename** | A planned mutation that computes and applies a safe symbol rename across the workspace. | Search-and-replace, rewrite |
| **Apply edits** | The step that materializes a reviewed edit plan after hash checks still match. | Patch, overwrite, rewrite |

## Agent integration

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Agent** | An external automation client that invokes Kast against a workspace. | Bot, assistant, script |
| **Skill** | A packaged agent-facing interface that wraps Kast operations for LLM workflows. | Prompt, command, macro |

## Relationships

- A **CLI** sends requests to exactly one **Backend** for a given **Workspace**.
- A **Backend** serves exactly one **Workspace** and owns one warm **Analysis session** at a time.
- A **Runtime mode** hosts one **Backend**.
- A running **Standalone backend** is one **Daemon**.
- A **Descriptor** advertises one running **Backend** to external tools.
- **Resolve**, **References**, **Call hierarchy**, and **Diagnostics** all read from the same **Analysis session**.
- A **Rename** or **Apply edits** flow is a **Planned mutation** against one **Workspace**.
- A **Skill** or **Agent** invokes Kast through the same **Capability surface** exposed by a **Backend**.

## Example dialogue

> **Dev:** "For Kast, is the **CLI** the same thing as the **Backend**?"
>
> **Domain expert:** "No — the **CLI** is the control plane, while the **Backend** is the long-lived runtime that answers requests for a **Workspace**."
>
> **Dev:** "So when I start `kast-standalone`, am I creating the **Standalone backend** and its **Analysis session**?"
>
> **Domain expert:** "Exactly. That running process is the **Daemon**, and later **Resolve**, **References**, and **Diagnostics** calls reuse the same warm **Analysis session**."
>
> **Dev:** "If IntelliJ is already open, do I still need the standalone path?"
>
> **Domain expert:** "Not unless you want it — the **IntelliJ plugin-backed runtime** hosts the **Backend** inside the IDE and exposes the same **Capability surface**."
>
> **Dev:** "So a **Skill** still talks to the same system either way?"
>
> **Domain expert:** "Yes. The host changes, but the **Skill** still targets the same contract for the same **Workspace**."

## Flagged ambiguities

- "backend" was used both for the generic serving runtime and for the headless `kast-standalone` process — use **Backend** for the generic concept and **Standalone backend** or **Daemon** for the headless one.
- "runtime" and "backend" were used interchangeably — a **Runtime mode** is the hosting path, while a **Backend** is the serving analysis process.
- "project", "repo", and "workspace" were used loosely — use **Workspace** for the root Kast analyzes, and reserve "project model" for IntelliJ or Gradle internals.
- "plugin" can mean an installable artifact or an active runtime — use **IntelliJ plugin** for the installable artifact and **IntelliJ plugin-backed runtime** for the running backend.
- "skill" and "command" should stay separate — a **Skill** is an agent-facing wrapper, while a `kast` command is a direct CLI operation.
