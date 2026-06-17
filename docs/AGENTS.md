# Docs agent guide

The `docs` unit is the source for the published Zensical site. The site
uses a hierarchical structure organized by intent, with `zensical.toml`
defining navigation, extensions, and theme configuration.

## Site structure

The documentation is organized around the shortest user path first:

- `docs/index.md` — landing page for the two-scope install model:
  machine-level global binary plus repository-local Copilot integration.
- `docs/getting-started/install.md` — primary install guide for macOS
  developer machines. Keep repair, plugin repair, shell, and development
  details behind collapsible sections.
- `docs/getting-started/headless-linux.md` — separate install guide for Linux
  CI runners, hosted agents, server images, mirrors, and headless release
  artifact verification.
- `docs/for-agents/` — agent-facing content. The overview explains the
  global binary vs repository integration split, `install-the-skill.md`
  documents Copilot integrations first and skill-only fallback second,
  `talk-to-your-agent.md` covers prompting, and `direct-cli.md` covers
  raw CLI fallback.
- `docs/supported-use-cases.md` — concise value and fit page for supported
  agent workflows.
- `docs/adr/` — durable ADR/specification records for the current product
  story and documentation operating model. Add a superseding ADR when the
  first reader path, install posture, or agent-facing delivery contract
  changes.
- `docs/troubleshooting.md` — task-oriented support page that stays visible
  as a top-level sidebar entry.
- `docs/reference/` plus reference-nav pages — detailed API, CLI, backend,
  recipe, and capability material. `capabilities.md` is generated but
  intentionally excluded from the nav to avoid duplicating
  `api-reference.md`.
- `docs/architecture/` — how-it-works, behavioral-model, kast-vs-lsp,
  and ADT boundaries.

Generated reference pages under `docs/reference/` are produced by
`./gradlew :analysis-api:generateDocPages` and drift-tested by
`AnalysisDocsDocumentTest`. Do not hand-edit those generated pages.
`docs/reference/api-specification.md` is hand-authored, but its
JSON-RPC suite block is generated from
`cli-rs/resources/kast-skill/references/commands.json` by
`.github/scripts/render-rpc-contract-summary.py` and checked by
`.github/scripts/test-docs-content-contract.sh`.

## Ownership

Keep these docs tightly coupled to the implementation and the published
CLI workflow.

- Keep docs aligned with the code that exists today. Mark planned or
  missing behavior explicitly instead of implying it already works.
- Keep broad product-story changes aligned with
  `docs/adr/0001-agent-first-install-and-docs-operating-model.md` or a
  superseding ADR.
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
