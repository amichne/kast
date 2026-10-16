---
type: Module Guide
title: "Kast docs"
description: "Contains the Mintlify site, contributor procedures, glossary policy, and revision-bound acceptance reports."
resource: "repo://docs"
tags: [kast, navigation]
timestamp: "2026-10-16T04:31:29Z"
source_revision: 4b879c5c5a78975e400ddcd95f64f3ff0b3e9cc8
code_sources:
  - path: docs/development.md
  - path: docs/documentation-structure.md
  - path: docs/glossary.json
  - path: docs/hosted-read-configuration.md
  - path: docs/installation-recovery.md
  - path: docs/public/docs.json
  - path: docs/public/reference/source-map.mdx
  - path: docs/tool-rpc.md
  - path: docs/topology-impact.md
  - path: docs/tooling/check_glossary.py
  - path: docs/tooling/glossary_json.py
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-16 | hash: c10553a2a6e2 -->

# docs

## Purpose

Contains the Mintlify site, contributor procedures, glossary policy, and revision-bound acceptance reports.

## Key Files

- [docs.json](public/docs.json) - site navigation and reader tracks.
- [documentation-structure.md](documentation-structure.md) - Diátaxis, source ownership, routes, and the local Vale procedure.
- [glossary.json](glossary.json) - canonical vocabulary, source evidence, and explicit document scope.
- [GLOSSARY.md](GLOSSARY.md) - generated reader view of the canonical vocabulary.
- [development.md](development.md) - development commands and focused test selection.
- [topology-impact.md](topology-impact.md) - opt-in declared dependency impact, report fields, and the disable switch.
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
- [tooling/](tooling/README.md) - local glossary grammar, reader projection, and Vale rule generation.

## Entry Points

- From `docs/public`, run `mint validate` and `mint broken-links`.
- Use the pinned Vale command and checked-in rules from the root guide.
- Run `./gradlew verifyDocumentationGlossary` for current terminology and generated projection parity.
- Read the nested public reference guide before changing generated callable contracts.

## Navigation Hints

- For a public claim, trace the page into its current protocol or source owner.
- Edit `docs/public` source. Keep rendered output separate.
- The glossary controls current documentation and agent instructions within its declared scope. Historical reports keep their recorded terms.
- Historical review reports describe their recorded revisions and evidence limits.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
