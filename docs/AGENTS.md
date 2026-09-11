<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 81f5da31e92f -->

# docs

## Purpose

Contains the public Mintlify documentation source plus scripts and styles for observer visual evidence.

## Key Files

- [public/docs.json](public/docs.json) - public documentation navigation and site configuration.
- [public/index.mdx](public/index.mdx) - product landing documentation.
- [public/start.mdx](public/start.mdx) - installation and connection guide.
- [public/search.mdx](public/search.mdx) - search workflow.
- [public/troubleshooting.mdx](public/troubleshooting.mdx) - support guidance.
- [render_kast_observer_snapshots.py](render_kast_observer_snapshots.py) - observer snapshot renderer.

## Subdirectories

- `public/concepts` - conceptual explanations.
- `public/reference` - generated and hand-authored reference pages.
- `public/images` - checked documentation assets.
- `tooling` - local documentation tooling; generated caches are not source authority.

## Entry Points

- Validate from `docs/public` with `mint validate`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/index.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For user-visible behavior, start with the relevant public page, then verify every claim against protocol/source contracts.
- Treat `site/` as rendered output; edit `docs/public` instead.
