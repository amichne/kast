---
title: Kast for agents
description: How Copilot and other agents use a global Kast binary plus
  repository-local integrations.
icon: lucide/bot
---

# Kast for agents

Agents on macOS developer machines need the Homebrew-managed Kast machine
install, which includes the `kast` binary and IDEA or Android Studio plugin,
plus repository-local integration files in the repository they are working on.
Keep machine and repository scopes separate.

## Agent setup in two scopes

On a developer machine, install the Homebrew formula once. The formula installs
or refreshes the matching `kast-plugin` cask, then you install Copilot files in
each repository where the agent should use Kast.

```console title="Developer-machine agent setup"
brew tap amichne/kast
brew install kast

cd /path/to/your/repository
kast install copilot
```

Restart IDEA or Android Studio after Homebrew links or refreshes the plugin
and after installing repository files. The Copilot package starts
`kast lsp --stdio`, loads Kotlin-specific instructions, exposes `kast-reader`
and `kast-writer`, and provides catalog-backed `kast_*` tools.

??? success "Machine-level responsibility"
    The global `kast` binary owns CLI commands, LSP startup, direct JSON-RPC,
    install repair, and backend lifecycle commands. The Homebrew formula keeps
    the `kast-plugin` cask version-coupled, and the cask owns the IDE plugin
    links into local JetBrains profiles. A single machine install can serve
    many repositories. Use `brew install --cask kast-plugin` or
    `brew reinstall --cask kast-plugin` only when repairing the cask directly.

??? tip "Repository-level responsibility"
    `kast install copilot` writes managed files under the current repository's
    `.github` directory. Run it once per repository. Rerun with `--force`
    after upgrading the binary or when the package files look stale.

## Local and hosted agents

The right runtime depends on where the agent is running. The command surface
stays the same; the backend that provides Kotlin state changes.

| Agent environment | Install path | Runtime path | What the agent gets |
|-------------------|--------------|--------------|---------------------|
| Local Copilot in a developer repo | Homebrew global binary with version-coupled `kast-plugin` cask, plus `kast install copilot` in that repo | LSP through the global binary, then IDEA backend on developer machines | Repository instructions, `kast-reader`, `kast-writer`, and `kast_*` tools |
| Local agent with an open IDE | Homebrew machine install plus repository Copilot files | IDEA backend reusing the open project | Warm IDE project model and the same Kast protocol |
| CI or hosted Linux agent | Ubuntu/Debian headless bundle | Headless backend warmed with `kast up --backend=headless` | `kast` on `PATH`, structured JSON-RPC, and bundled runtime libraries |

Use the Linux headless path when the agent image cannot rely on Homebrew, a
human shell profile, or an already-open IDE. Do not present it as the local
macOS developer-machine equivalent.

## What your agent gets

Kast gives an agent evidence it can quote. It should use that evidence before
summarizing, refactoring, or claiming that a result is complete.

| What it gets | What Kast returns | Why the agent cares |
|--------------|-------------------|---------------------|
| Semantic identity | Exact declaration, fully qualified name, kind, location | Talks about one symbol, not a matching string |
| Exhaustive evidence | References with `searchScope.exhaustive` and hierarchies with truncation metadata | Says what is complete and where evidence stops |
| Safe edits | Plan-then-apply mutations with SHA-256 conflict detection | Rejects stale plans instead of corrupting files |
| Workspace awareness | Analysis scoped to the Gradle workspace | Answers reflect module boundaries and visibility |

## Same protocol, two runtimes

The headless backend and IDEA plugin backend expose the same JSON-RPC
contract. Agents do not need a different prompt or command shape when the
runtime changes.

| Runtime | Where semantic state lives | Best fit |
|---------|----------------------------|----------|
| Headless | A packaged IDEA-backed daemon outside any IDE | Terminals, CI, remote machines, cloud agents |
| IDEA plugin | Inside a running IDEA or Android Studio project | Local agents when the IDE is already open and warm |

On developer machines, agents reuse IDEA or Android Studio through the
Homebrew-managed plugin. The headless backend exposes the same surface for
CI runners, hosted Linux agents, and server images that install the Linux
bundle.

## What your agent can do

Once Kast is wired in, these workflows stop being text-search guesses.

- Resolve a symbol before summarizing usage.
- Find references and report whether the search was exhaustive.
- Walk a call graph with explicit bounds and truncation metadata.
- Plan a rename with conflict detection before applying it.
- Find implementations of an interface or abstract type.
- Check diagnostics to validate changed files.

## Next steps

The first page explains the repository package. The prompt page explains how
to ask for useful evidence.

- [Copilot integrations](install-the-skill.md) explains the repository files
  and the skill-only fallback.
- [Talk to your agent](talk-to-your-agent.md) gives prompt shapes that make
  agents resolve first.
- [Direct CLI usage](direct-cli.md) covers `kast rpc` when an agent needs a
  raw fallback.
