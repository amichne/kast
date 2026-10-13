<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-03 | hash: e787d26e9c74 -->

# docs

## Purpose

Contains the Mintlify site with Guide and Reference tabs plus scripts and styles for observer visual evidence.

## Key Files

- [public/docs.json](public/docs.json) - public documentation navigation and site configuration.
- [public/index.mdx](public/index.mdx) - product landing documentation.
- [public/reference.mdx](public/reference.mdx) - entry point for technical reference material.
- [documentation-structure.md](documentation-structure.md) - route and content rules.
- [public/start.mdx](public/start.mdx) - installation and connection guide.
- [public/search.mdx](public/search.mdx) - search workflow.
- [public/reference/change.mdx](public/reference/change.mdx) - one-call change, internal exact-plan signing, application, and recovery workflow.
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

- `public/reference/internals` - runtime implementation details.
- `public/reference` - generated and hand-authored reference pages.
- `public/images` - checked documentation assets.
- `reviews` - source and runtime acceptance review evidence.

## Entry Points

- Validate from `docs/public` with `mint validate`.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/quickstart.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For user-visible behavior, start with the relevant public page, then verify every claim against protocol/source contracts.
- Treat `site/` as rendered output; edit `docs/public` instead.
