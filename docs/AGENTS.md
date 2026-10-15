---
type: Module Guide
title: "Kast docs"
description: "Contains the Mintlify site, contributor procedures, glossary policy, and revision-bound acceptance reports."
resource: "repo://docs"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: docs/development.md
  - path: docs/documentation-structure.md
  - path: docs/glossary.json
  - path: docs/hosted-read-configuration.md
  - path: docs/installation-recovery.md
  - path: docs/public/docs.json
  - path: docs/public/reference/source-map.mdx
  - path: docs/tool-rpc.md
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 788b7fd77a84 -->

# docs

## Purpose

Contains the Mintlify site, contributor procedures, glossary policy, and revision-bound acceptance reports.

## Key Files

- [docs.json](public/docs.json) - site navigation and reader tracks.
- [documentation-structure.md](documentation-structure.md) - Diátaxis, source ownership, routes, and the local Vale procedure.
- [glossary.json](glossary.json) - provisional terms and explicit document scope.
- [development.md](development.md) - development commands and focused test selection.
- [tool-rpc.md](tool-rpc.md) - tool RPC and harness extension behavior.
- [source-map.mdx](public/reference/source-map.mdx) - source owners and their focused tests.
- [installation-recovery.md](installation-recovery.md) - offline installation recovery.
- [hosted-read-configuration.md](hosted-read-configuration.md) - read grants, retention, and limits.

## Subdirectories

- [public/](public/) - authored Mintlify source and generated reference assets.
- [public/tutorials/](public/tutorials/) - controlled exercises with expected evidence.
- [public/how-to/](public/how-to/) - procedures for specific tasks.
- [public/reference/](public/reference/AGENTS.md) - contracts, models, and authored generation rules.
- [public/explanation/](public/explanation/) - mechanisms and design rationale.
- [reviews/](reviews/) - historical reports with exact evidence scope.
- [vale-vocabulary/](vale-vocabulary/) - reviewed spellings for local Vale.

## Entry Points

- From `docs/public`, run `mint validate` and `mint broken-links`.
- Use the pinned local Vale procedure in `documentation-structure.md`.
- Read the nested public reference guide before changing generated callable contracts.

## Navigation Hints

- For a public claim, trace the page into its current protocol or source owner.
- Edit `docs/public` source. Keep rendered output separate.
- The glossary controls its declared document scope. It does not authorize terminology changes across the repository.
- Historical review reports describe their recorded revisions and evidence limits.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
