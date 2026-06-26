# ADR 0003: CLI command documentation operating model

Status: Accepted

Date: 2026-06-25

This ADR supersedes the public documentation portions of
ADR 0001. ADR 0001 still records the install-scope split, but the published
site no longer owns agent-first product essays, architecture pages, or
protocol reference pages.

## Context

Kast still distributes generated RPC and OpenAPI artifacts for release,
package, and integration consumers. Publishing those artifacts in the docs site
made the site feel like a protocol reference instead of a developer command
manual. The intended public reader is now a developer who needs to install
Kast, run `kast` commands, script advanced CLI flows, and troubleshoot the
active install.

## Decision

The published Zensical site under `docs/` is CLI command documentation only.
It may include short context that helps a reader choose or run a command, but
it must not publish standalone RPC/OpenAPI references, broad architecture
essays, or use-case pages detached from commands.

The public navigation is:

- Overview
- Install
- Quickstart
- Commands
- Recipes
- Troubleshooting
- Distribution

`kast agent` and `kast agent workflow` are documented as advanced CLI
commands. Raw `kast rpc` remains a hidden debug escape hatch and should not be
the public path for agent or script examples.

## Source of truth

| Surface | Source of truth | Validation |
|---------|-----------------|------------|
| Published site nav | `zensical.toml` | `.github/scripts/test-docs-navigation-contract.sh` |
| Published CLI docs | `docs/` | `.github/scripts/test-docs-content-contract.sh`, `zensical build --clean` |
| CLI command shape | `cli-rs/src/cli.rs` and `kast help` | Cargo CLI tests and docs content contract |
| RPC/tool catalog | `cli-rs/resources/kast-skill/references/commands.json` | `cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- release generate contract --check` |
| Protocol artifacts | `cli-rs/protocol/` | `./gradlew :analysis-api:test`, `./gradlew :analysis-server:test` |
| Release OpenAPI copy | `dist/openapi.yaml` from `stageOpenApiSpec` | `./gradlew stageOpenApiSpec` |

Protocol artifacts may be linked from release packaging, generated checks, and
repo-local integration material. They must not be linked from the published
docs navigation or used as public reader destinations.

## Change process

When a docs change alters command coverage or reader flow:

1. Update `zensical.toml` and the affected `docs/` pages together.
2. Keep examples command-first and prefer `kast agent` over raw `kast rpc`.
3. Move generated protocol docs outside `docs/` instead of hiding them from the
   sidebar.
4. Update `.agents/docs/AGENTS.md` when published-doc ownership changes.
5. Update docs contract scripts so stale public API, architecture, or use-case
   links fail loudly.

## Validation

Run these checks for public documentation changes:

```console
.github/scripts/test-docs-navigation-contract.sh
.github/scripts/test-docs-content-contract.sh
zensical build --clean
git diff --check
```

Run the protocol generation checks when RPC/OpenAPI artifacts move or drift:

```console
./gradlew :analysis-api:generateOpenApiSpec
./gradlew :analysis-api:generateDocPages
./gradlew :analysis-api:test
./gradlew :analysis-server:test
```
