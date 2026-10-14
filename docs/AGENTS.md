<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: d3b12ef1de9c -->

# docs

## Purpose

Contains the Mintlify site with tutorials, how-to guides, reference, and explanation. It also contains local prose policy and historical acceptance evidence.

## Key Files

- [public/docs.json](public/docs.json) - public documentation navigation and site configuration.
- [public/index.mdx](public/index.mdx) - product landing documentation.
- [public/reference.mdx](public/reference.mdx) - entry point for technical reference material.
- [documentation-structure.md](documentation-structure.md) - reader tracks, source ownership, route preservation, and local Vale procedure.
- [glossary.json](glossary.json) - provisional terminology source with explicit document scope.
- [GLOSSARY.md](GLOSSARY.md) - generated glossary reader view.
- [public/tutorials/first-query.mdx](public/tutorials/first-query.mdx) - controlled query exercise with expected caller and absence evidence.
- [public/reference/source-map.mdx](public/reference/source-map.mdx) - contract owners, focused tests, and OpenWiki concepts.
- [public/start.mdx](public/start.mdx) - installation and connection guide.
- [public/search.mdx](public/search.mdx) - search workflow.
- [public/reference/change.mdx](public/reference/change.mdx) - one-call source change, verification, and recovery workflow.
- [public/troubleshooting.mdx](public/troubleshooting.mdx) - support guidance.
- [render_kast_observer_snapshots.py](render_kast_observer_snapshots.py) - observer snapshot renderer.

- [development.md](development.md) - build, local installation, docs preview, and behavior-sized testing.
- [installation-recovery.md](installation-recovery.md) - offline recovery of damaged installations.
- [tool-rpc.md](tool-rpc.md) - bounded tool RPC, compact query delivery and Copilot/Pi extension setup.

- [hosted-read-configuration.md](hosted-read-configuration.md) - hosted read grants, retention configuration, and reported limitations.
- [reviews/representation-impact-proof.md](reviews/representation-impact-proof.md) - exact installed representation-impact qualification, retained evidence, and proof limits.
- [reviews/persistent-topology-snapshots.md](reviews/persistent-topology-snapshots.md) - historical contracts, SQLite and offline oracles, work counts, and native capture limits.
- [reviews/direct-tool-startup-2026-10-05.md](reviews/direct-tool-startup-2026-10-05.md) - paired synthetic and final-byte startup measurements, avoided discovery work, and timing limits.
- [reviews/selected-jbr-startup-2026-10-05.md](reviews/selected-jbr-startup-2026-10-05.md) - selected IDEA JBR admission, regression coverage, and completed Claim refresh.
- [public/reference/schemas.mdx](public/reference/schemas.mdx) - generated schema and authored sample entry points.
- [public/reference/models/query-item.mdx](public/reference/models/query-item.mdx) - query item documentation backed by generated canonical schema.

## Subdirectories

- `public/tutorials` - exercises with expected evidence.
- `public/how-to` - diagnostic and retained-evidence procedures.
- `public/explanation` - query lifecycle explanation.
- `public/reference/internals` - runtime implementation details.
- `public/reference` - generated and hand-authored reference pages.
- `public/images` - checked documentation assets.
- `reviews` - source and runtime acceptance review evidence.
- `vale-vocabulary` - reviewed spellings for the local Vale consumer.

## Entry Points

- From `docs/public`, run `mint validate` and `mint broken-links`.
- Use the root `.vale.ini` with the pinned local consumer described in [documentation-structure.md](documentation-structure.md).

## Navigation Hints

- Start with the [repository knowledge](../openwiki/quickstart.md) and read its `code_sources` for source evidence. Root engineering rules remain authoritative. This map adds navigation only.

- For user-visible behavior, start with the selected public page. Examine its claims against protocol and source contracts.
- Treat `site/` as rendered output; edit `docs/public` instead.
