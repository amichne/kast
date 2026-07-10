# Journey-First Documentation Rewrite Design

## Purpose

Rewrite the Kast documentation site so high-quality documentation is native to
the project instead of emerging from ad hoc page edits. The new structure should
serve concrete reader journeys while using Diataxis to keep each page focused on
one reader need: learning by doing, accomplishing a task, looking up facts, or
understanding context.

This design supersedes the current "CLI command manual only" public
documentation model. The implementation must add a superseding ADR before
rewriting navigation or published pages.

## Current Problem

The current site is accurate and command-grounded, but most pages orbit the
command surface instead of the reader's journey. That creates several problems:

- New readers see install commands before they understand which install lane
  matches their host.
- `quickstart.md` behaves like a first-run guide, but it uses placeholder
  symbols and cannot guarantee a successful tutorial path.
- Command pages mix reference, procedural guidance, and short conceptual
  explanations.
- Recipes are useful but command-named, so readers must already know which
  command family solves their problem.
- Troubleshooting has the right instinct but lacks a lookup shape for symptoms,
  causes, checks, and fixes.
- Durable rationale lives mostly in agent-only ADRs, so public explanation is
  thin even when it would help readers operate Kast correctly.

The installed `writing-documentation-with-diataxis` skill and
`.agents/docs/documentation-journeys.md` make these gaps explicit.

## Goals

- Put reader journeys ahead of command taxonomy.
- Preserve command discoverability for developers, agents, scripts, release
  engineers, and operators.
- Give each page a primary Diataxis role.
- Keep every claim source-backed by code, command output, schema files,
  contract scripts, README, or accepted ADRs.
- Move generated or protocol-owned material out of hand-authored prose.
- Make examples either executable, fixture-backed, or clearly marked as
  placeholders.
- Keep the published site useful without requiring readers to know the internal
  ADR history.

## Non-Goals

- Do not reintroduce raw RPC or OpenAPI pages into the public navigation.
- Do not publish broad product essays that are detached from operating Kast.
- Do not hand-edit generated protocol, catalog, package, or site output.
- Do not change CLI behavior as part of the docs rewrite.
- Do not force every command into a complete generated reference unless the
  implementation adds or reuses a verification path for that coverage.

## Readers

The site should explicitly serve these readers:

| Reader | Needs |
| --- | --- |
| macOS developer | Install the machine binary and plugin, open a repository, verify readiness, and run a first semantic command |
| Linux or hosted-agent operator | Install a headless bundle, prepare repository guidance, start a backend, and verify the runtime |
| Agent or script author | Learn which `kast agent` commands are safe public automation paths |
| CLI operator | Look up command groups, flags, output modes, and health boundaries |
| Release or mirror engineer | Package, verify, mirror, activate, and trust distribution artifacts |
| Stuck reader | Diagnose install drift, backend readiness, indexing, and semantic command failures |
| Architecture-curious reader | Understand why Kast separates distribution, setup, runtime, semantic commands, and evidence |

## Recommended Navigation

Replace the current command-manual navigation with journey-first navigation:

```toml
nav = [
  { "Start" = "index.md" },
  { "Install" = [
    { "macOS developer machine" = "install/macos.md" },
    { "Headless Linux and hosted agents" = "install/headless-linux.md" },
  ]},
  { "Learn" = [
    { "First semantic workflow" = "learn/first-semantic-workflow.md" },
    { "How Kast thinks about evidence" = "learn/evidence-model.md" },
  ]},
  { "Use Kast" = [
    { "Choose a command" = "use/choose-a-command.md" },
    { "Inspect Kotlin" = "use/inspect-kotlin.md" },
    { "Plan safe edits" = "use/plan-safe-edits.md" },
    { "Automate with agents" = "use/automate-with-agents.md" },
  ]},
  { "Reference" = [
    { "Command surface" = "reference/commands.md" },
    { "Agent commands" = "reference/agent-commands.md" },
    { "Mutation selectors" = "reference/mutation-selectors.md" },
    { "Runtime and output modes" = "reference/runtime-and-output.md" },
  ]},
  { "Troubleshoot" = "troubleshoot.md" },
  { "Distribute" = [
    { "Release and mirror workflow" = "distribute/release-and-mirror.md" },
    { "Runtime artifact contract" = "distribute/runtime-artifact-contract.md" },
  ]},
  { "Design Notes" = [
    { "Operating model" = "design/operating-model.md" },
  ]},
]
```

The final file names may change during implementation if nearby source evidence
suggests a tighter structure, but the reader journeys should remain.

## Page Roles

| Page | Role | Source material |
| --- | --- | --- |
| `index.md` | Landing page | Current `docs/index.md`, README, superseding ADR |
| `install/macos.md` | How-to guide | Current install page, README install section, installer contract tests |
| `install/headless-linux.md` | How-to guide | Current headless Linux page, distribution page, installer scripts |
| `learn/first-semantic-workflow.md` | Tutorial candidate | Current quickstart, CLI tests or a fixture-backed example |
| `learn/evidence-model.md` | Explanation | ADR 0006, current overview, command behavior |
| `use/choose-a-command.md` | How-to guide | Current commands index and recipes |
| `use/inspect-kotlin.md` | How-to guide | Current recipes, agent command docs, Kast skill |
| `use/plan-safe-edits.md` | How-to guide | Current recipes and mutation sections |
| `use/automate-with-agents.md` | How-to guide | Current agent docs, managed guidance rules |
| `reference/commands.md` | Reference | `kast help`, CLI source, docs contract scripts |
| `reference/agent-commands.md` | Reference | Current agent page, CLI source |
| `reference/mutation-selectors.md` | Reference | Current selector table, CLI/API command models |
| `reference/runtime-and-output.md` | Reference | Current lifecycle, install-repair, output mode text |
| `troubleshoot.md` | Diagnostic how-to/reference matrix | Current troubleshooting page, readiness/repair command contracts |
| `distribute/release-and-mirror.md` | How-to guide | Current headless and distribution pages |
| `distribute/runtime-artifact-contract.md` | Reference | Current distribution page and manifest schema |
| `design/operating-model.md` | Explanation | ADR 0006, superseding docs ADR, current overview diagram |

## Superseding ADR

Add a new `.agents/adr/` record that supersedes ADR 0003 for published
documentation. The ADR should state:

- the public docs are now journey-first CLI documentation;
- Diataxis page roles are a shaping constraint, not a four-folder mandate;
- command reference remains public but no longer owns the whole site shape;
- generated protocol material remains outside public navigation;
- `zensical.toml`, docs pages, README links, docs contracts, and
  `.agents/docs/AGENTS.md` are source owners for the new model;
- public explanation is allowed when it helps readers operate Kast, but broad
  rationale still belongs in ADRs.

## Rewrite Strategy

1. Add the superseding ADR.
2. Update `.agents/docs/AGENTS.md` and `documentation-journeys.md` to point at
   the new public structure.
3. Move or rewrite pages into the new tree.
4. Update `zensical.toml` navigation in the same change as page moves.
5. Update README documentation links if paths change.
6. Update docs contract scripts if they encode the old navigation or old
   command-manual-only boundary.
7. Run docs contract checks and the Zensical build.
8. Reader-test the new site with the questions in the reader-testing reference.

## Reader Testing

Before calling the rewrite complete, a cold reader should be able to answer:

- Which install path should I use on macOS?
- Which install path should I use in CI or a hosted Linux agent?
- What command proves the backend is ready?
- How do I resolve a Kotlin symbol before editing?
- How do I plan and apply a safe rename?
- Where do I look up mutation selectors?
- What should I do when readiness reports install drift?
- What artifacts must be verified before a release mirror trusts a bundle?
- Why does Kast keep setup, runtime, semantic commands, and evidence separate?

Each answer should be findable from the published docs without relying on this
conversation.

## Validation

The implementation must run:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

If command examples or page claims depend on CLI behavior, run the narrow CLI
or contract tests that prove those examples. If `zensical` is unavailable,
report the missing dependency and run the contract scripts plus `git diff
--check`.

## Risks

- A full restructure can break external links. Mitigation: update README links
  and use redirects only if the docs toolchain supports them.
- A Diataxis-shaped nav can become abstract. Mitigation: keep navigation labels
  journey-oriented, not framework-oriented.
- Complete command reference can drift. Mitigation: either generate/check it or
  explicitly frame reference pages as curated public command guidance.
- Tutorial claims can overpromise. Mitigation: use a verified fixture before
  calling the first workflow a true tutorial.

## Implementation Decisions

- Label the first semantic workflow as a guided first-run workflow unless the
  implementation finds an existing fixture that can prove every step without
  product-code changes. Do not claim true tutorial reliability without a
  fixture or captured command output.
- Make `reference/commands.md` curated public command guidance with an explicit
  scope note. Do not attempt complete generated reference in this rewrite
  unless an existing contract already exposes the needed command inventory.
- Do not add thin redirect pages unless Zensical already supports redirects in
  the current toolchain. Update README and all internal links; accept external
  path churn as part of the superseded documentation model.
