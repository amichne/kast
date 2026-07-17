# ADR 0011: Journey-first documentation operating model

Status: Accepted

Date: 2026-07-09

This ADR supersedes ADR 0003 for the public documentation structure. ADR 0003
still records why generated protocol and broad architecture pages were removed
from the published site.

## Context

The command-manual-only site kept Kast accurate and close to the CLI, but it
made every reader enter through command taxonomy. The Diataxis review found
that macOS developers, hosted-agent operators, agent authors, release
engineers, CLI operators, and stuck readers have different jobs and need
different page shapes.

The public site still needs to stay command-backed. Kast is a CLI and semantic
control plane, so public docs must keep readers close to commands, validation,
and concrete operating evidence. The change is the entry model: readers should
start from what they are trying to do, then land on command reference when they
need lookup accuracy.

## Decision

The published Zensical site is now journey-first CLI documentation. Navigation
is organized by reader journey, while each page has one primary Diataxis role:
guided first-run workflow, how-to guide, reference, explanation, or diagnostic
matrix.

Command reference remains public, but it no longer owns the whole site shape.
Public explanation is allowed when it helps readers operate Kast correctly.
Broad rationale and product-surface decisions remain in agent-only ADRs unless
a future ADR deliberately publishes them.

## Public Navigation

The public navigation is:

- Start
- Install
- Learn
- Use Kast
- Reference
- Troubleshoot
- Distribute
- Design Notes

The names are journey labels, not Diataxis taxonomy. A page under "Use Kast"
may be a how-to guide, while a page under "Reference" must stay lookup-shaped.

## Source Of Truth

| Surface | Source of truth | Validation |
| --- | --- | --- |
| Published site nav | `zensical.toml` | `.github/scripts/test-docs-navigation-contract.sh` |
| Reader journeys and page roles | `.agents/docs/documentation-journeys.md` | `.github/scripts/test-docs-content-contract.sh` |
| Published Markdown | `docs/` | `.github/scripts/test-docs-content-contract.sh`, `zensical build --clean` |
| CLI command shape | `kast --help`, `cli-rs/src/cli/`, `cli-rs/src/main.rs` | Cargo CLI tests and docs content contract |
| Internal command catalog | `cli-rs/protocol/source/commands.json` | `cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check` |
| Protocol artifacts | `cli-rs/protocol/` | `./gradlew :analysis-api:test`, `./gradlew :analysis-server:test` |
| Release OpenAPI copy | `dist/openapi.yaml` from `stageOpenApiSpec` | `./gradlew stageOpenApiSpec` |

Protocol artifacts may be linked from release packaging, generated checks, and
repo-local integration material. They must not be linked from the published
docs navigation or used as public reader destinations.

## Page Role Rules

Each published page must name one primary reader job and keep its shape aligned
with that job.

- Guided first-run workflow: one path through a first successful command
  sequence. Do not claim full tutorial reliability unless a fixture or captured
  command output proves the path.
- How-to guide: a concrete reader goal, practical sequence, and conditionals
  for real-world variation. Link to reference for option detail.
- Reference: neutral description, consistent structure, and source ownership.
  Reference pages describe what exists; they do not teach the domain model.
- Explanation: short public context that helps readers operate Kast. Keep broad
  architectural debate and product-surface decisions in ADRs.
- Diagnostic matrix: symptom, likely cause, read-only check, and fix path.

## Change Process

When a docs change alters reader flow, command coverage, or published
navigation:

1. Update `zensical.toml` and affected `docs/` pages together.
2. Keep each page tied to one primary reader job and Diataxis role.
3. Update `.agents/docs/documentation-journeys.md` when reader journeys or page
   roles change.
4. Keep developer-facing paths minimal. Put agent execution details, support
   commands, and copy-paste command sequences in collapsed blocks unless the
   page is explicitly a reference lookup page.
5. Prefer typed `kast agent` commands over raw transport or workflow helpers
   when command detail is needed.
6. Keep generated protocol docs outside `docs/`.
7. Update docs contract scripts so stale public paths and unsupported claims
   fail loudly.

## Validation

Run these checks for public documentation changes:

```console
.github/scripts/test-docs-navigation-contract.sh
.github/scripts/test-docs-content-contract.sh
zensical build --clean
git diff --check
```

Run protocol generation checks when RPC/OpenAPI artifacts move or drift:

```console
./gradlew :analysis-api:generateOpenApiSpec
./gradlew :analysis-api:generateDocPages
./gradlew :analysis-api:test
./gradlew :analysis-server:test
```
