---
title: "ADR 0001: Agent-first install and docs operating model"
description: Current Kast delivery contract for global binary install,
  repository-local Copilot integrations, and future documentation iteration.
icon: lucide/file-check
---

# ADR 0001: Agent-first install and docs operating model

Status: Accepted

Date: 2026-06-17

This ADR records the current delivery contract for Kast documentation and
agent-facing setup. It exists so future agents can update the site from a
checked-in operating model instead of preserving stale conversation context.

## Context

Kast is currently presented as a compiler-backed Kotlin analysis surface for
Copilot, terminal workflows, CI jobs, and hosted agents. The documentation used
to lead with runtime choices, backend details, and direct JSON-RPC examples.
That made the first user path harder to see and made future updates prone to
mixing several scopes into one install story.

The current product story separates install scope from runtime detail:

- The `kast` binary and IDEA or Android Studio plugin are installed once at
  the macOS developer-machine level through Homebrew.
- Copilot integrations are installed separately into each repository that
  should use Kast.
- Headless Linux installs are a second lane for hosted agents, CI runners, and
  servers that need their own binary and backend runtime.

## Decision

The documentation will lead with an agent-first install model:

```console
brew tap amichne/kast
brew install kast
brew install --cask kast-plugin

cd /path/to/your/repository
kast install copilot
```

This is the primary macOS developer-machine path. The reader should understand
the macOS machine layer, the repository layer, and the separate Linux
headless-server lane before seeing detailed runtime material:

| Scope | Owner | Current command | Documentation role |
|-------|-------|-----------------|--------------------|
| Machine CLI | Global `kast` binary | `brew install kast` | First step for developer machines |
| Machine IDE plugin | Homebrew-managed JetBrains plugin links | `brew install --cask kast-plugin` | Required macOS developer-machine component |
| Repository | Copilot/LSP package files under `.github` | `kast install copilot` | First step per repository |
| Headless server | Linux bundle with binary, config, and runtime | `scripts/install-ubuntu-debian.sh install` | Second lane for hosted agents |

Detailed backend, API, repair, shell, release, and local-development material
remains supported, but it belongs behind the first path as reference or
collapsible detail.

## Source of truth

Future changes must start from these checked-in sources rather than from old
conversation summaries.

| Surface | Source of truth | Validation |
|---------|-----------------|------------|
| Published site nav | `zensical.toml` and `docs/docs.json` | `.github/scripts/test-docs-navigation-contract.sh` |
| First reader path | `docs/index.md`, `docs/getting-started/install.md`, `docs/for-agents/index.md` | `.github/scripts/test-docs-content-contract.sh` |
| Headless server path | `docs/getting-started/headless-linux.md` | `.github/scripts/test-docs-content-contract.sh` |
| Public summary | `README.md` | `.github/scripts/test-docs-content-contract.sh` |
| Use-case framing | `docs/supported-use-cases.md` | `zensical build --clean` |
| Copilot package source | `cli-rs/resources/plugin/` | `.github/scripts/test-kast-copilot-plugin.sh` |
| Installed Copilot outputs | `.github/lsp.json`, `.github/instructions/`, `.github/agents/`, `.github/extensions/kast/` | `kast install copilot --force` plus package tests |
| RPC/tool catalog | `cli-rs/resources/kast-skill/references/commands.json` | `cargo run --manifest-path cli-rs/Cargo.toml -- generate contract --check` |
| API summary block | `docs/reference/api-specification.md` generated block | `.github/scripts/render-rpc-contract-summary.py --check` |

Generated or installed copies must not become independent product truth. When
they drift, update the source owner and regenerate or reinstall the copy.

## Iteration framework

Any future documentation change that affects the first path must answer these
questions before editing public prose:

1. What is the current reader job?
2. Which scope is changing: machine, repository, headless server, runtime, or
   reference?
3. Which checked-in source proves the behavior today?
4. Which generated or installed outputs must move with the source?
5. Which contract test should fail if the old story returns?

Use this update matrix when the answer changes:

| Change trigger | Required updates |
|----------------|------------------|
| Global binary or IDE plugin install changes | README, `docs/index.md`, install guide, docs content contract |
| Repository Copilot package changes | `cli-rs/resources/plugin/`, generated `.github` outputs, install guide, agent docs, package tests |
| RPC or tool catalog changes | `commands.json`, generated contract artifacts, API summary block, Copilot extension shared catalog |
| Primary reader path changes | New or superseding ADR, docs nav, landing page, install guide, agent overview, content/navigation contracts |
| Runtime support changes | Backends docs, install guides, troubleshooting, README runtime table |
| New optional complexity | Collapsible detail or reference page first; promote only after it becomes part of the golden path |

## Staleness rules

Future agents should treat stale intent as a documentation bug. Do not preserve
old wording because it appeared in a prior summary, PR body, issue, or
conversation.

- Prefer the newest accepted ADR plus live source files over memory.
- Keep "current behavior" wording tied to commands, manifests, scripts, or
  release assets that exist now.
- Move historical details out of the first path unless the reader must know
  them to install or use Kast today.
- Add or update a contract test when stale wording has already caused drift.
- If the product goal changes, add a superseding ADR before rewriting broad
  documentation.

## Acceptance checklist

A docs iteration that changes the agent-first story is complete only when:

- The machine vs repository vs headless-server scope split is still explicit.
- The primary developer-machine path stays separate from the headless server
  path.
- `README.md`, `docs/index.md`, install docs, and agent docs agree.
- Source package files and installed `.github` outputs have a clear owner.
- Generated reference pages are regenerated or explicitly left untouched.
- Local validation includes the docs content contract, docs navigation
  contract, `git diff --check`, and `zensical build --clean`.
