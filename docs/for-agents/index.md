---
title: Kast for agents
description: What Kast gives your LLM agent that grep, ripgrep, and text
  search can't.
icon: lucide/bot
---

# What your agent gets

Your agent already knows how to grep a repo and rewrite text. What it
can't do alone is read Kotlin the way the compiler does. `kast` plugs
that hole. Four things text search will never give you: stable symbol
identity, exhaustive evidence, conflict-safe edits, workspace-aware
results.

## Zero to agent in three commands

```console title="Set up Kast for your agent"
# 1. Drop the kast skill into this repo (writes to .agents/skills/kast)
kast install skill

# 2. Warm the workspace daemon for this repo
kast up --workspace-root="$PWD"

# 3. Hand off — your agent now has the kast skill loaded
```

Done. The skill teaches the workflow and the resolve-first pattern. The
backend keeps Kotlin state warm. Agents should prefer native `kast_*`
tools and fall back to `kast rpc` when they need the CLI directly;
humans use `kast up`, `kast status`, and `kast stop` to manage the
daemon. The rest of this page is what your agent picks up from that.

The agent talks to either runtime over the same JSON-RPC. Standalone
runs as an independent daemon — terminals, CI, remote machines, cloud
agents. The IDEA plugin exposes the same protocol from inside an
open IDE project, reusing the IDE's project model, indexes, and
analysis session.

## Local and hosted agent setup

Local agents usually inherit a developer's installed CLI and workspace.
Hosted Ubuntu/Debian agents should install into a contained root from the
self-contained release bundle before the session starts. Homebrew is still the
developer install path when the target supports it; the Ubuntu/Debian installer
is the only supported non-Brew path.

| Agent environment | Install path | Runtime path | What to hand the agent |
|-------------------|--------------|--------------|-------------------------|
| Local developer agent | `kast install skill` and optional `kast install copilot-extension` | Existing CLI plus standalone or IDEA backend | The packaged skill and native `kast_*` tools |
| CI review agent | `scripts/install-ubuntu-debian.sh install` | Standalone backend warmed in the job | `kast rpc` commands and structured JSON outputs |
| Ubuntu/Debian hosted agent | `scripts/install-ubuntu-debian.sh install` from the release bundle | Contained CLI and standalone backend under `KAST_UBUNTU_DEBIAN_ROOT` | `kast` on `PATH` plus `KAST_CONFIG_HOME` when a custom config root is used |

Use the Ubuntu/Debian path when the agent image cannot rely on a human shell
profile, Homebrew tap state, or an already-open IDE. The installer verifies the
CLI, backend runtime libraries, generated config, and `kast doctor` before it
exits.

| What it gets         | What `kast` returns                                                                       | Why your agent cares                                                            |
|----------------------|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| Semantic identity    | Exact declaration, fully qualified name, kind, location                                   | Talks about one symbol, not "anything matching this string"                     |
| Exhaustive evidence  | References with `searchScope.exhaustive`, hierarchies with truncation metadata            | Says what's complete, what's bounded, where evidence stops                      |
| Safe edits           | Plan-then-apply mutations with SHA-256 conflict detection                                 | Reviews changes before applying; rejects stale plans instead of corrupting code |
| Workspace awareness  | Analysis scoped to one Gradle workspace, with module boundaries and visibility            | Answers reflect the project, not file-by-file guesses                           |

## Symbol identity, not string matching

`kast` resolves the declaration at a position. Your agent gets a fully
qualified name it can keep using for the rest of the conversation
without ambiguity.
[Understand symbols →](../what-can-kast-do/understand-symbols.md)

## Exhaustive evidence, not line matches

References come back with `searchScope.exhaustive`. Hierarchies come
back with explicit depth, fan-out, and truncation metadata. The agent
can quote both.
[Trace usage →](../what-can-kast-do/trace-usage.md)

## Safe edits, not find-and-replace

Plan→apply with SHA-256 file hashes. The agent shows the plan, then
applies it. If anything drifted between the two, the apply fails — no
silent corruption.
[Refactor safely →](../what-can-kast-do/refactor-safely.md)

## Workspace awareness, not file-by-file

`kast` analyzes the whole Gradle workspace as one session. Module
boundaries and visibility shape the results — your agent doesn't need
to reason about them itself.
[Manage workspaces →](../what-can-kast-do/manage-workspaces.md)

## Same protocol, two runtimes

The contract is identical. What changes is where the analysis state
lives and who keeps it warm.

| Runtime         | Where semantic state lives                       | Best fit                                              |
|-----------------|--------------------------------------------------|-------------------------------------------------------|
| Standalone      | A long-lived `kast` daemon outside any IDE       | Terminals, CI, remote machines, cloud agents          |
| IDEA plugin     | Inside a running IDEA or Android Studio project  | Local agents when the IDE is already open and warm    |

If IDEA or Android Studio is open, agents can connect to the plugin and ride
the IDE's warmth. If not, the standalone backend exposes the same surface on
its own.

## What your agent can actually do

Once `kast` is wired in, these stop being approximations:

- **Resolve a symbol** before summarizing usage — no ambiguity about
  which declaration is in play.
- **Find all references** and report whether the search was exhaustive
  — no guessing.
- **Walk a call graph** with explicit bounds — and say where it was
  truncated and why.
- **Plan a rename** with conflict detection — verify the plan, then
  apply.
- **Find implementations** of an interface — concrete subclasses, not
  string matches.
- **Check diagnostics** to confirm code still compiles — without
  running the full build.

## Next steps

- [Understand the backends](../getting-started/backends.md) — same
  protocol, two daemons
- [Talk to your agent](talk-to-your-agent.md) — prompts that get the
  most out of `kast`
- [Install the skill](install-the-skill.md) — drop the packaged skill
  into your workspace
- [Direct CLI usage](direct-cli.md) — when the agent calls `kast rpc`
  itself
