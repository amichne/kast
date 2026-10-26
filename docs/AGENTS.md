---
type: Module Guide
title: "Kast docs"
description: "This directory owns the Mintlify site, contributor guides, and glossary rules. Reports keep the source revision and scope of each result."
resource: "repo://docs"
tags: [kast, navigation]
timestamp: "2026-10-26T13:43:05Z"
source_revision: 3ff8661a429fa510692345f08db80c9554fadee5
code_sources:
  - path: docs/development.md
  - path: docs/documentation-structure.md
  - path: docs/glossary.json
  - path: docs/hosted-read-configuration.md
  - path: docs/installation-recovery.md
  - path: docs/public/docs.json
  - path: docs/public/sidebar-toggle.js
  - path: docs/public/snippets/deeper-reading.mdx
  - path: docs/public/reference/source-map.mdx
  - path: docs/tool-rpc.md
  - path: docs/topology-impact.md
  - path: docs/tooling/check_glossary.py
  - path: docs/tooling/glossary_json.py
  - path: docs/tooling/diagrams/render.mjs
  - path: docs/tooling/diagrams/diagrams.json
  - path: docs/tooling/value-journey/build.mts
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-16 | hash: c10553a2a6e2 -->

# docs

## Purpose

This directory owns the Mintlify site, contributor guides, and glossary rules. Reports keep the source revision and scope of each result.

## Key Files

- [docs.json](public/docs.json) - site navigation and reader tracks.
- [documentation-structure.md](documentation-structure.md) - doc types, source owners, routes, and Vale commands.
- [glossary.json](glossary.json) - terms, their source evidence, and the pages they govern.
- [GLOSSARY.md](GLOSSARY.md) - generated view of the terms.
- [development.md](development.md) - commands to change code and select a focused test.
- [topology-impact.md](topology-impact.md) - rules for dependency impact, report fields, and the off switch.
- [tool-rpc.md](tool-rpc.md) - tool RPC and harness extension behavior.
- [source-map.mdx](public/reference/source-map.mdx) - source owners and their focused tests.
- [installation-recovery.md](installation-recovery.md) - offline installation recovery.
- [hosted-read-configuration.md](hosted-read-configuration.md) - read grants, retention, and limits.

## Subdirectories

- [public/](public/) - site source and generated files.
- [public/tutorials/](public/tutorials/) - exercises with expected results.
- [public/how-to/](public/how-to/) - procedures for specific tasks.
- [public/reference/](public/reference/AGENTS.md) - contracts, models, and authored generation rules.
- [public/explanation/](public/explanation/) - how Kast works and why.
- [reviews/](reviews/) - reports with the scope of each result.
- [tooling/](tooling/README.md) - tools that turn glossary terms into pages and Vale rules.

## Entry Points

- From `docs/public`, run `mint validate` and `mint broken-links`.
- Public diagrams use `docs/public/images/architecture/*.mmd` as source. Run `npm run render --prefix docs/tooling/diagrams`. Then run `npm run check --prefix docs/tooling/diagrams`.
- Keep generated diagram snippets, SVG files, and `diagram-theme.css` with their source. Edit the shared renderer for SVG layout and colors. Edit `style.css` for figure layout.
- The value explorer and README preview share `docs/tooling/value-journey` as their source. Run `npm run build --prefix docs/tooling/value-journey`. Commit both generated files.
- Keep core menus focused on reader goals. Keep Architecture and Reference hidden from menus but searchable.
- Use `snippets/deeper-reading.mdx` for bottom links from core pages. `sidebar-toggle.js` owns the desktop toggle for Maple.
- After a navigation edit, examine group controls, sidebar state, keyboard access, and the native mobile menu.
- Use the pinned Vale command and checked-in rules from the root guide.
- Run `./gradlew verifyDocumentationGlossary` to examine terms and generated files.
- Run `python3 .github/scripts/ci/verify_documentation.py` for parser, glossary, example, and knowledge-link obligations without Gradle.
- `code_kb.py check-documentation-inputs` examines source paths and links. Full `check` also examines Kotlin declarations.
- Before an edit to a generated callable contract, read the nested public reference guide.

## Navigation Hints

- For a public claim, trace the page into its current protocol or source owner.
- Edit `docs/public` source. Keep rendered output separate.
- The glossary governs current docs and agent instructions within its stated scope. Historical reports keep their recorded terms.
- Review reports describe the code at their recorded revisions. Their evidence keeps its stated limits.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
