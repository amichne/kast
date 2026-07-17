# ADR 0001: Agent-first install and docs operating model

Status: Accepted; public documentation model superseded by ADR 0003; forward
product surface superseded by ADR 0006; macOS workspace setup model superseded
by ADR 0007 and ADR 0010

Date: 2026-06-17

This ADR records the current delivery contract for Kast documentation and
agent-facing setup. It exists so future agents can update the site from a
checked-in operating model instead of preserving stale conversation context.
ADR 0003 supersedes the public documentation topology. ADR 0007 supersedes the
macOS repository setup authority and Homebrew distribution relationship. This
ADR remains only as the install-scope split that newer ADRs refine.

## Context

Kast is currently presented as a compiler-backed Kotlin analysis surface for
Copilot, terminal workflows, CI jobs, and hosted agents. The documentation used
to lead with runtime choices, backend details, and direct JSON-RPC examples.
That made the first user path harder to see and made future updates prone to
mixing several scopes into one install story.

The current product story separates install scope from runtime detail:

- The `kast` binary and IDEA or Android Studio plugin are installed once at
  the macOS developer-machine level through Homebrew.
- Repository guidance is prepared by the Kast plugin on macOS and by
  `kast setup` on non-macOS headless or server hosts.
- Headless Linux installs are a second lane for hosted agents, CI runners, and
  servers that need their own binary and backend runtime.

## Decision

The documentation will lead with an agent-first install model. The reader
should understand the macOS machine layer, the repository layer, and the
separate Linux headless-server lane before seeing detailed runtime material.
The current public macOS command path is owned by ADR 0010.

| Scope | Owner | Current command | Documentation role |
|-------|-------|-----------------|--------------------|
| Machine CLI + IDE plugin | Global `kast` binary plus Homebrew-managed JetBrains plugin artifact | `brew install kast` | First step for developer machines |
| Machine IDE plugin repair | Homebrew-managed JetBrains plugin links | `kast developer machine plugin` | Profile-link repair path |
| macOS repository | IntelliJ plugin writes skill-facing guidance and workspace metadata | Open the repository with the Kast plugin active | First step per macOS repository |
| non-macOS repository | Skill and managed context guidance | `kast setup --workspace-root <repo>` | Headless/server repository path |
| Headless server | Linux bundle with binary, config, and runtime | `scripts/install-ubuntu-debian.sh install` | Second lane for hosted agents |

Detailed backend, API, repair, shell, release, and local-development material
remains supported, but it belongs behind the first path as reference or
collapsible detail.

## Source of truth

Future changes must start from these checked-in sources rather than from old
conversation summaries.

| Surface | Source of truth | Validation |
|---------|-----------------|------------|
| Published site nav | `zensical.toml` | `.github/scripts/test-docs-navigation-contract.sh` |
| First reader path | `docs/index.md`, `docs/getting-started/install.md`, `docs/getting-started/quickstart.md`, `docs/commands/` | `.github/scripts/test-docs-content-contract.sh` |
| Headless server path | `docs/getting-started/headless-linux.md` | `.github/scripts/test-docs-content-contract.sh` |
| Public summary | `README.md` | `.github/scripts/test-docs-content-contract.sh` |
| Plugin package source | `cli-rs/resources/plugin/` | `.github/scripts/test-kast-copilot-plugin.sh` |
| Internal command catalog | `cli-rs/protocol/source/commands.json` | `cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check` |
| Protocol artifacts | `cli-rs/protocol/` | `.github/scripts/render-rpc-contract-summary.py --check`, `./gradlew :analysis-api:test` |

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
| Plugin package source changes | `cli-rs/resources/plugin/`, install guide, command docs, package tests |
| Internal command catalog changes | `commands.json`, generated contract artifacts under `cli-rs/protocol/`, catalog smoke tests |
| Primary reader path changes | New or superseding ADR, docs nav, landing page, install guide, command overview, content/navigation contracts |
| Runtime support changes | Command docs, install guides, troubleshooting, README runtime table |
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
- Source package files and generated plugin artifacts have a clear owner.
- Generated reference pages are regenerated or explicitly left untouched.
- Local validation includes the docs content contract, docs navigation
  contract, `git diff --check`, and `zensical build --clean`.
