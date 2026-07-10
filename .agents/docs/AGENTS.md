# Published docs source guide

This file describes how agents should work in `docs/`. It intentionally lives
under `.agents/docs/` so Zensical does not publish agent-only guidance as site
content. The `docs` unit is the source for the published Zensical site, with
`zensical.toml` defining navigation, extensions, and theme configuration.

## Site structure

The published documentation is a CLI command manual for developers:

- `docs/index.md` — landing page for the command manual and install path.
- `docs/getting-started/install.md` — macOS developer-machine install, managed
  repository files, repair commands, and source checkout notes.
- `docs/getting-started/headless-linux.md` — Linux server, CI, hosted-agent,
  mirror, and image-build install path.
- `docs/getting-started/quickstart.md` — first lifecycle and `kast agent`
  semantic commands.
- `docs/commands/` — command documentation for lifecycle, install and repair,
  the repo-native demo, agent automation, metrics, and LSP.
- `docs/recipes.md` — copy-paste command sequences.
- `docs/troubleshooting.md` — command-oriented diagnosis.
- `docs/distribution/runtime-artifact-contract.md` — distribution commands,
  bundle activation, and release verification.

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
  `.agents/adr/0003-cli-command-documentation-operating-model.md` or a
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
