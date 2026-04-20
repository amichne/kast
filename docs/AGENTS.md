# Docs agent guide

The `docs` unit is the source for the published Zensical site. The site
uses a hierarchical structure organized by intent, with `zensical.toml`
defining navigation, extensions, and theme configuration.

## Site structure

The documentation is organized into six sections:

- `docs/index.md` — focused landing page: differentiators, `kast demo`,
  a 60-second quickstart, and navigation cards. No inline capability
  walkthroughs — those live under `what-can-kast-do/`.
- `docs/getting-started/` — install, quickstart, backends
- `docs/what-can-kast-do/` — intent-organized capability pages
  (understand-symbols, trace-usage, refactor-safely, validate-code,
  manage-workspaces). These are the canonical home for CLI/JSON-RPC
  examples.
- `docs/for-agents/` — agent-facing content (overview, talk-to-agent,
  install-skill, direct-cli)
- `docs/architecture/` — how-it-works, behavioral-model, kast-vs-lsp
- `docs/reference/` — generated pages. `api-reference` and
  `api-specification` appear in the nav, plus `error-codes`.
  `capabilities.md` is generated but intentionally excluded from the
  nav to avoid duplicating `api-reference.md`.

Generated reference pages under `docs/reference/` are produced by
`./gradlew :analysis-api:generateDocPages` and drift-tested by
`AnalysisDocsDocumentTest`. Do not hand-edit them.

## Ownership

Keep these docs tightly coupled to the implementation and the published
CLI workflow.

- Keep docs aligned with the code that exists today. Mark planned or
  missing behavior explicitly instead of implying it already works.
- Treat `zensical.toml` as the live source of truth for navigation.
  Add new source pages and nav entries together.
- Keep `README.md` and the published docs consistent when public CLI
  commands, daemon lifecycle, transport details, or packaging change.
- Prefer precise statements over broad claims. If evidence is partial,
  narrow the wording and make the uncertainty explicit.
- Document `call hierarchy` as available but bounded. Say plainly when
  results may truncate because of depth, timeout, or traversal limits.
- Change `docs/` or `zensical.toml` when rendered content must move.
  Do not hand-edit the generated files under `site/`.

## Authoring conventions

- Use content tabs (`=== "Tab"`) for CLI / JSON-RPC / Agent
  alternatives.
- Use `hl_lines` to highlight key fields in JSON response examples.
- Use Mermaid diagrams for architecture, sequences, and state machines.
- Use collapsible admonitions (`??? question`) for troubleshooting.
- Wrap text at 80 characters (except long links or tables).
- Every heading must be followed by at least one paragraph before any
  list or subheading.

## Verification

Review documentation changes against the code and neighboring docs
before finishing.

- Re-read modified docs against `README.md`, `docs/index.md`, and the
  relevant implementation before finishing.
- Check for stale links or deleted-page references whenever you change
  the published docs surface.
- If navigation, layout, or rendered output changes matter, run
  `zensical build --clean`. Install the pinned docs toolchain with
  `pip install -r requirements-docs.txt` if needed.
