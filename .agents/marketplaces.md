# Agent Marketplaces

This repository consumes agent tooling from configured Codex marketplaces.
Installed plugin payloads, generated marketplace snapshots, and runtime cache
directories are observations, not source-of-truth files for Kast.

## Marketplace Sources

| Provider | Marketplace | Source | Ref | Entrypoint | Scope |
|---|---|---|---|---|---|
| Codex | personal | local user marketplace rooted at `~` | local-only | `.agents/plugins/marketplace.json` | all configured plugins |
| Codex | openai-bundled | bundled Codex runtime marketplace under `$CODEX_HOME` | local runtime snapshot | `.agents/plugins/marketplace.json` | all configured plugins |
| Codex | amichne-intelligence | `git@github.com:amichne/intelligence.git` | `codex` | `.agents/plugins/marketplace.json` | all configured plugins |
| Codex | openai-curated | bundled Codex curated marketplace under `$CODEX_HOME` | local runtime snapshot | `.agents/plugins/marketplace.json` | all configured plugins |

## Expected Plugins

| Plugin | Marketplace | Purpose | Status |
|---|---|---|---|
| `typed-design-discipline` | `amichne-intelligence` | Repository onboarding, schema-backed boundaries, and type-driven design discipline. | installed, enabled |
| `kotlin-correctness` | `amichne-intelligence` | Kotlin standards, package cohesion, and Gradle validation support for Kast's JVM code. | installed, enabled |
| `evidence-driven-delivery` | `amichne-intelligence` | TDD, git hygiene, CI triage, and PR lifecycle workflows. | installed, enabled |
| `agentic-development` | `personal` | Local agentic-development workflow guidance and proof-loop hooks. | installed, enabled |
| `browser` | `openai-bundled` | In-app browser checks for local docs or UI surfaces when a change needs visual verification. | installed, enabled |
| `github` | `openai-curated` | GitHub issue, PR, review, and CI operations that complement the repo's `gh` CLI guidance. | installed, enabled |

## Exclusions

No configured marketplaces are excluded. Setup applies to the full configured
marketplace set above unless this file is updated with a concrete repo policy
or security reason.

## Local Setup Notes

- Start with `AGENTS.md`, then any deeper `AGENTS.md` for the unit being
  changed.
- `.github/skill-shadowing.json` maps repo-local skills to Copilot extensions;
  `kast` is required-read and shadows `cli-rs/resources/kast-skill/SKILL.md`
  when the `.github/extensions/kast/extension.mjs` extension is loaded.
- Packaged Copilot resources for this repo live under
  `.github/extensions/kast`. Keep workflow guidance in instructions, skills,
  or packaged extension agent material, not in retired hook paths or vendored
  marketplace payloads.
- `workspace.repos.toml` records sibling repositories that move with Kast. Treat
  those entries as checkouts, not vendored marketplace or plugin source.

## Refresh

- Run `codex plugin marketplace upgrade` to refresh configured Git marketplace
  snapshots.
- Run `codex plugin marketplace list` to list marketplaces Codex is considering.
- Run `codex plugin list` to verify expected plugin installation and enablement.
- Re-run the narrow repo validation for any source files changed alongside this
  reference.

## Validation

- `test -f .agents/marketplaces.md`
- `git ls-files .agents/marketplaces.md`
- `codex plugin marketplace list`
- `codex plugin list`
- `find . -path '*/plugins/typed-design-discipline' -o -path '*/plugins/kotlin-correctness' -o -path '*/plugins/evidence-driven-delivery' -o -path '*/plugins/github'`
