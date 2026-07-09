# Journey-First Docs Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Kast's command-manual-only docs with journey-first CLI documentation shaped by Diataxis reader roles.

**Architecture:** Add a superseding agent-only ADR, update agent docs guidance, restructure `zensical.toml`, rewrite published pages into journey, how-to, reference, troubleshoot, distribute, and design sections, then update docs contracts and README links. Keep generated protocol artifacts outside the published docs tree.

**Tech Stack:** Zensical TOML navigation, Material-style Markdown extensions, Bash/Python docs contract scripts, repository-local Agent Skills.

## Global Constraints

- Do not change CLI behavior.
- Do not hand-edit generated protocol, catalog, package, or site output.
- Do not publish raw RPC/OpenAPI pages in the public navigation.
- Keep every docs claim backed by implementation, `kast --help`, schema files, contract scripts, README, or ADRs.
- Use `apply_patch` for manual file edits.
- Preserve the installed project skill under `.agents/skills/writing-documentation-with-diataxis/`.
- Run `.github/scripts/test-docs-content-contract.sh`, `.github/scripts/test-docs-navigation-contract.sh`, `zensical build --clean`, and `git diff --check` before completion.

---

### Task 1: Supersede The Documentation Operating Model

**Files:**
- Create: `.agents/adr/0011-journey-first-documentation-operating-model.md`
- Modify: `.agents/docs/AGENTS.md`
- Modify: `.agents/docs/documentation-journeys.md`

**Interfaces:**
- Consumes: approved design spec at `docs/superpowers/specs/2026-07-09-journey-first-docs-rewrite-design.md`
- Produces: source-of-truth policy for journey-first public docs and Diataxis page roles

- [ ] **Step 1: Add superseding ADR**

Create `.agents/adr/0011-journey-first-documentation-operating-model.md` with:

```markdown
# ADR 0011: Journey-first documentation operating model

Status: Accepted

Date: 2026-07-09

This ADR supersedes ADR 0003 for the public documentation structure. ADR 0003
still records why generated protocol and broad architecture pages were removed
from the published site.

## Context

The command-manual-only site kept Kast accurate and close to the CLI, but it
made every reader enter through command taxonomy. The Diataxis review found
that new developers, hosted-agent operators, agent authors, release engineers,
and stuck readers have different jobs and need different page shapes.

## Decision

The published Zensical site is now journey-first CLI documentation. Navigation
is organized by reader journey, while each page has one primary Diataxis role:
tutorial or guided first-run workflow, how-to guide, reference, explanation, or
diagnostic matrix.

Command reference remains public, but it no longer owns the whole site shape.
Public explanation is allowed when it helps readers operate Kast correctly.
Broad rationale and product-surface decisions remain in agent-only ADRs unless
a future ADR deliberately publishes them.

## Public Navigation

- Start
- Install
- Learn
- Use Kast
- Reference
- Troubleshoot
- Distribute
- Design Notes

## Source Of Truth

| Surface | Source of truth | Validation |
| --- | --- | --- |
| Published site nav | `zensical.toml` | `.github/scripts/test-docs-navigation-contract.sh` |
| Reader journeys and page roles | `.agents/docs/documentation-journeys.md` | `.github/scripts/test-docs-content-contract.sh` |
| Published Markdown | `docs/` | `.github/scripts/test-docs-content-contract.sh`, `zensical build --clean` |
| CLI command shape | `kast --help`, `cli-rs/src/cli/`, `cli-rs/src/main.rs` | Cargo CLI tests and docs content contract |
| Protocol artifacts | `cli-rs/protocol/` | `./gradlew :analysis-api:test`, `./gradlew :analysis-server:test` |

## Change Process

When a docs change alters reader flow, command coverage, or published
navigation:

1. Update `zensical.toml` and affected `docs/` pages together.
2. Keep each page tied to one primary reader job and Diataxis role.
3. Update `.agents/docs/documentation-journeys.md` when reader journeys or page
   roles change.
4. Keep examples command-first and prefer typed `kast agent` commands over raw
   transport or workflow helpers.
5. Keep generated protocol docs outside `docs/`.
6. Update docs contract scripts so stale public paths and unsupported claims
   fail loudly.

## Validation

```console
.github/scripts/test-docs-navigation-contract.sh
.github/scripts/test-docs-content-contract.sh
zensical build --clean
git diff --check
```
```

- [ ] **Step 2: Update `.agents/docs/AGENTS.md`**

Replace the old command-manual sentence with a journey-first statement and keep
the existing generated-protocol boundary.

- [ ] **Step 3: Update `.agents/docs/documentation-journeys.md`**

Align reader journeys and page roles with the new navigation and file paths.

- [ ] **Step 4: Verify this task**

Run: `git diff --check`

Expected: no output.

---

### Task 2: Restructure Navigation And Published Source Files

**Files:**
- Modify: `zensical.toml`
- Move/rewrite docs under: `docs/`

**Interfaces:**
- Consumes: ADR 0011 and `documentation-journeys.md`
- Produces: journey-first docs tree matching `zensical.toml`

- [ ] **Step 1: Create new docs directories**

Run:

```bash
mkdir -p docs/install docs/learn docs/use docs/reference docs/distribute docs/design
```

- [ ] **Step 2: Remove old published Markdown paths after content is moved**

Old paths to replace:

```text
docs/getting-started/install.md
docs/getting-started/headless-linux.md
docs/getting-started/quickstart.md
docs/commands/index.md
docs/commands/lifecycle.md
docs/commands/install-repair.md
docs/commands/agent.md
docs/commands/metrics.md
docs/commands/lsp.md
docs/recipes.md
docs/troubleshooting.md
docs/distribution/runtime-artifact-contract.md
```

- [ ] **Step 3: Add new published Markdown paths**

New paths:

```text
docs/index.md
docs/install/macos.md
docs/install/headless-linux.md
docs/learn/first-semantic-workflow.md
docs/learn/evidence-model.md
docs/use/choose-a-command.md
docs/use/inspect-kotlin.md
docs/use/plan-safe-edits.md
docs/use/automate-with-agents.md
docs/reference/commands.md
docs/reference/agent-commands.md
docs/reference/mutation-selectors.md
docs/reference/runtime-and-output.md
docs/troubleshoot.md
docs/distribute/release-and-mirror.md
docs/distribute/runtime-artifact-contract.md
docs/design/operating-model.md
```

- [ ] **Step 4: Update `zensical.toml` nav**

Use this group order:

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

- [ ] **Step 5: Verify this task**

Run: `git diff --check`

Expected: no output.

---

### Task 3: Rewrite Journey And How-To Pages

**Files:**
- Modify: `docs/index.md`
- Create: `docs/install/macos.md`
- Create: `docs/install/headless-linux.md`
- Create: `docs/learn/first-semantic-workflow.md`
- Create: `docs/use/choose-a-command.md`
- Create: `docs/use/inspect-kotlin.md`
- Create: `docs/use/plan-safe-edits.md`
- Create: `docs/use/automate-with-agents.md`
- Create: `docs/troubleshoot.md`

**Interfaces:**
- Consumes: current pages, `kast --help`, `kast agent --help`, ADR 0006
- Produces: reader-job pages with task-oriented headings and next-step exits

- [ ] **Step 1: Rewrite `docs/index.md`**

Make it a landing page that routes by reader job and keeps a compact operating
model diagram.

- [ ] **Step 2: Write install pages**

Write `docs/install/macos.md` for Homebrew/plugin setup and
`docs/install/headless-linux.md` for Linux/hosted-agent setup. Keep platform
branches separate.

- [ ] **Step 3: Write first workflow page**

Write `docs/learn/first-semantic-workflow.md` as a guided first-run workflow,
not a guaranteed tutorial. Include readiness, backend verification, symbol
lookup, diagnostics, and rename plan commands.

- [ ] **Step 4: Write use pages**

Write task-oriented how-to pages for choosing commands, inspecting Kotlin,
planning safe edits, and automating with agents.

- [ ] **Step 5: Write troubleshooting page**

Write `docs/troubleshoot.md` as a symptom/cause/check/fix matrix with command
snippets for readiness, backend readiness, Gradle/indexing, semantic failures,
and mutation planning.

- [ ] **Step 6: Verify this task**

Run:

```bash
git diff --check
.github/scripts/test-docs-content-contract.sh
```

Expected: no whitespace errors. Content contract may fail until Task 5 updates
old path checks; use failures as input for that task.

---

### Task 4: Rewrite Reference, Distribution, And Explanation Pages

**Files:**
- Create: `docs/reference/commands.md`
- Create: `docs/reference/agent-commands.md`
- Create: `docs/reference/mutation-selectors.md`
- Create: `docs/reference/runtime-and-output.md`
- Create: `docs/distribute/release-and-mirror.md`
- Create: `docs/distribute/runtime-artifact-contract.md`
- Create: `docs/design/operating-model.md`

**Interfaces:**
- Consumes: current command pages, distribution page, manifest schema,
  `kast --help`, `kast agent --help`, `kast developer runtime --help`,
  `kast developer release --help`
- Produces: compact reference and explanation pages with source-backed command
  tables

- [ ] **Step 1: Write command reference**

Use live help output to describe command groups. State that the page is curated
public command guidance, not a complete generated reference.

- [ ] **Step 2: Write agent command reference**

Document typed `kast agent` commands, default output, and the plan/apply
boundary for mutations.

- [ ] **Step 3: Write selector and runtime references**

Move mutation selector lookup to `reference/mutation-selectors.md` and runtime,
readiness, repair, backend, and output mode facts to
`reference/runtime-and-output.md`.

- [ ] **Step 4: Write distribution pages**

Split release/mirror workflow from runtime artifact contract. Keep the manifest
schema link in `distribute/runtime-artifact-contract.md`.

- [ ] **Step 5: Write operating model explanation**

Explain distribution, workspace setup, runtime backend, semantic commands, and
evidence without turning the page into a command how-to.

- [ ] **Step 6: Verify this task**

Run:

```bash
git diff --check
.github/scripts/test-docs-content-contract.sh
```

Expected: no whitespace errors. Content contract may fail until Task 5 updates
old path checks.

---

### Task 5: Update Contracts And README Links

**Files:**
- Modify: `.github/scripts/test-docs-navigation-contract.sh`
- Modify: `.github/scripts/test-docs-content-contract.sh`
- Modify: `README.md`

**Interfaces:**
- Consumes: new nav and page paths
- Produces: tests that enforce the new journey-first model

- [ ] **Step 1: Update navigation contract**

Replace old group order with:

```python
required_group_order = [
    "Start",
    "Install",
    "Learn",
    "Use Kast",
    "Reference",
    "Troubleshoot",
    "Distribute",
    "Design Notes",
]
```

Update placement checks to the new page paths.

- [ ] **Step 2: Update content contract paths**

Replace old page variables with new paths. Keep checks that reject raw RPC,
OpenAPI links, retired aliases, and generated protocol pages. Update required
text checks to enforce journey-first docs, typed agent commands, install lane
separation, troubleshooting matrix shape, and distribution verification.

- [ ] **Step 3: Update README documentation links**

Point README links to new public paths:

```text
https://kast.michne.com/install/macos/
https://kast.michne.com/install/headless-linux/
https://kast.michne.com/learn/first-semantic-workflow/
https://kast.michne.com/reference/commands/
https://kast.michne.com/use/inspect-kotlin/
```

- [ ] **Step 4: Verify this task**

Run:

```bash
.github/scripts/test-docs-navigation-contract.sh
.github/scripts/test-docs-content-contract.sh
git diff --check
```

Expected: both contract scripts pass.

---

### Task 6: Reader-Test And Build

**Files:**
- Modify only if reader testing exposes gaps.

**Interfaces:**
- Consumes: rewritten docs site
- Produces: validated final docs rewrite

- [ ] **Step 1: Run reader questions manually**

From the published docs only, answer:

```text
Which install path should I use on macOS?
Which install path should I use in CI or a hosted Linux agent?
What command proves the backend is ready?
How do I resolve a Kotlin symbol before editing?
How do I plan and apply a safe rename?
Where do I look up mutation selectors?
What should I do when readiness reports install drift?
What artifacts must be verified before a release mirror trusts a bundle?
Why does Kast keep setup, runtime, semantic commands, and evidence separate?
```

- [ ] **Step 2: Fix smallest gaps**

Patch the page that should carry the missing answer.

- [ ] **Step 3: Run full docs verification**

Run:

```bash
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

Expected: all commands pass. If `zensical` is missing, report it and still run
the two contract scripts plus `git diff --check`.
