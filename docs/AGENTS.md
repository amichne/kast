# Docs agent guide

The `docs` unit is the source for the published Zensical site. In the current
repo shape, that source is a compact CLI-first how-to centered on
`docs/index.md` and `zensical.toml`.

## Ownership

Keep these docs tightly coupled to the implementation and the published CLI
workflow.

- Keep docs aligned with the code that exists today. Mark planned or missing
  behavior explicitly instead of implying it already works.
- Treat `docs/index.md` and `zensical.toml` as the live source of truth for the
  site. Add new source pages and nav entries together, and do not link to
  removed docs pages.
- Keep `README.md` and the published docs consistent when public CLI commands,
  daemon lifecycle, transport details, or packaging steps change.
- Prefer precise statements over broad claims. If evidence is partial, narrow
  the wording and make the uncertainty explicit.
- Document `call hierarchy` as available but bounded. Say plainly when results
  may truncate because of depth, timeout, or other traversal limits.
- Change `docs/` or `zensical.toml` when rendered content must move. Do not
  hand-edit the generated files under `site/`.

## Verification

Review documentation changes against the code and neighboring docs before
finishing.

- Re-read modified docs against `README.md`, `docs/index.md`, and the relevant
  implementation before finishing.
- Check for stale links or deleted-page references whenever you change the
  published docs surface.
- If navigation, layout, or rendered output changes matter, run
  `zensical build --clean`. Install the pinned docs toolchain with
  `pip install -r requirements-docs.txt` if needed.
