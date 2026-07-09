# Published docs source guide

This file describes how agents should work in `docs/`. It intentionally lives
under `.agents/docs/` so Zensical does not publish agent-only guidance as site
content. The `docs` unit is the source for the published Zensical site, with
`zensical.toml` defining navigation, extensions, and theme configuration.

## Site structure

The published documentation is journey-first CLI documentation for developers,
agents, operators, and release engineers:

- `docs/index.md` — landing page for reader journeys and the operating model.
- `docs/install/` — install lanes for macOS developer machines and headless
  Linux or hosted-agent environments.
- `docs/learn/` — guided first-run and explanation pages for readers building
  a mental model.
- `docs/use/` — task-oriented how-to guides for choosing commands, inspecting
  Kotlin, planning safe edits, and automating with agents.
- `docs/reference/` — compact command, agent-command, selector, runtime, and
  output-mode reference pages.
- `docs/troubleshoot.md` — diagnostic matrix for install drift, backend state,
  indexing, semantic failures, and mutation planning.
- `docs/distribute/` — release, mirror, hosted-agent, bundle, and runtime
  artifact contract pages.
- `docs/design/` — short public explanations that help readers operate Kast.

Use `documentation-journeys.md` before creating, moving, or substantially
rewriting published docs. It records the intended reader journeys, Diataxis
page roles, known documentation gaps, and validation expectations for this
site.

RPC/OpenAPI material is generated and distributed from `cli-rs/protocol/`.
There is no separate `cli-rs` docs site. Generated protocol markdown and YAML
are drift-tested by the Kotlin docs tests. Do not hand-edit generated protocol
pages.

## Ownership

Keep these docs tightly coupled to the implementation and the published
CLI workflow.

- Keep docs aligned with the code that exists today. Mark planned or
  missing behavior explicitly instead of implying it already works.
- Keep broad product-story changes aligned with
  `.agents/adr/0011-journey-first-documentation-operating-model.md` or a
  superseding ADR.
- Keep agent resource, package, manifest-trust, and typed `kast agent`
  changes aligned with `.agents/adr/` records. Agent-only ADRs must not be
  added to `zensical.toml` or published under `docs/`.
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

- Use content tabs (`=== "Tab"`) for CLI alternatives.
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
