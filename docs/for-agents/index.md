---
title: Kast for agents
description: What Kast gives your LLM agent that grep, ripgrep, and text
  search can't.
icon: lucide/bot
---

# What Kast gives your agent

LLM agents can already search files and rewrite text. What they usually
lack is a semantic runtime that understands Kotlin the way a compiler does.
Kast fills that gap with four capabilities that text search can never
provide: stable symbol identity, exhaustive evidence, conflict-safe edits,
and workspace-aware results.

Agents reach those capabilities through the same JSON-RPC surface in two
runtime modes. The standalone runtime keeps semantic state in an
independent daemon that works in terminals, CI, remote machines, and
cloud agents. The IntelliJ plugin-backed runtime exposes the same
protocol from inside a running IntelliJ project, so tools can piggyback
on the IDE's already-open project model, indexes, and analysis session.

| Practical value | What Kast returns | Why it matters to an agent |
|---|---|---|
| Semantic identity | Exact declaration, fully qualified name, kind, and location | The agent can talk about one symbol instead of guessing from matching text |
| Exhaustive evidence | Reference results with `searchScope.exhaustive` plus bounded hierarchies with truncation metadata | The agent can say what is complete, what is bounded, and where evidence stops |
| Safe edits | Plan-then-apply mutations with SHA-256 conflict detection | The agent can review changes before apply and detect stale plans |
| Workspace-aware results | Analysis scoped to one Gradle workspace, including module boundaries and visibility | The answer reflects the project structure instead of file-by-file guesses |

## Symbol identity — not string matching

Kast resolves the exact declaration at a position instead of matching
text, so your agent can refer to a symbol by its fully qualified name for
the rest of the conversation.
[Understand symbols →](../what-can-kast-do/understand-symbols.md)

## Exhaustive evidence — not line matches

Kast returns bounded call hierarchies and reference lists with
`searchScope.exhaustive`, so your agent knows exactly which functions are
callers and whether a usage search was complete.
[Trace usage →](../what-can-kast-do/trace-usage.md)

## Safe edits — not find-and-replace

Kast's two-phase plan→apply flow with SHA-256 file hashes lets your agent
review edits before touching disk and detects conflicts if files change
in between.
[Refactor safely →](../what-can-kast-do/refactor-safely.md)

## Workspace awareness — not file-by-file

Kast analyzes entire Gradle workspaces as a single session, giving your
agent module boundaries and visibility-scoped results rather than
per-file guesses.
[Manage workspaces →](../what-can-kast-do/manage-workspaces.md)

## Same protocol, two runtime modes

The contract stays the same across both backends. What changes is where
semantic state lives and who keeps it warm.

| Runtime mode | Where semantic state lives | Best fit |
|---|---|---|
| Standalone | In a long-lived kast daemon outside any IDE | Terminals, CI, remote machines, and cloud agents |
| IntelliJ plugin | Inside the running IntelliJ project, reusing its model and indexes | Local tools and agents when the IDE is already open |

If IntelliJ is already open, agents and tools can connect to the plugin
backend and benefit from the IDE already being warm. If no IDE is running,
the standalone backend exposes the same JSON-RPC surface independently.

## What your agent can do with Kast

These tasks become reliable once your agent has semantic code intelligence,
regardless of which runtime mode is serving the request:

- **Resolve a symbol** before summarizing usage — the agent knows exactly
  which declaration it's talking about.
- **Find all references** and report whether the search was complete —
  the agent doesn't have to guess.
- **Walk a call graph** with explicit bounds — the agent can explain
  where the tree was truncated and why.
- **Plan a rename** with conflict detection — the agent can verify edits
  before touching disk.
- **Find implementations** of an interface — the agent gets concrete
  subclasses, not string matches.
- **Check diagnostics** to verify code compiles after changes — the agent
  catches errors without running the build.

## Next steps

Use these pages to go deeper:

- [Understand the backends](../getting-started/backends.md) — see how the
  standalone daemon and IntelliJ plugin expose the same protocol
- [Talk to your agent](talk-to-your-agent.md) — how to prompt your agent
  to use Kast effectively
- [Install the skill](install-the-skill.md) — get the packaged Kast
  skill into your workspace
- [Direct CLI usage](direct-cli.md) — when agents call the CLI directly
