# Public reader site

## Reader journey

This directory is the source root for the public Zensical site. `index.md`
establishes the compiler-evidence distinction and routes readers to
`start.md`, the question-led pages, the evidence boundary, the generated CLI
surface, and the architecture explanation. Keep that order intact.
The landing page hides its local table of contents because the global site
navigation already exposes the same reader routes. Keep local outlines on
longer pages where their headings add page-specific navigation.

Only pages named in the root `zensical.toml` navigation are public routes.
`docs/build_public_site.py` keeps repository guidance and authored diagram
sources out of the built site.

## Authoring boundary

Keep the primary journey outcome-led. Introduce commands only after the reader
understands the decision they answer. Keep reference tables in `reference/`
and implementation mechanics in `explanation/`.

Use relative Markdown links between authored pages. Preserve the semantic
color roles defined in `stylesheets/extra.css`: blue for discovery, violet for
exact identity, green for established evidence, and amber for effects or
limits. Keep surfaces flat and use in-flow disclosure instead of dialog-style
navigation. Keep page-shell accessibility behavior in `javascripts/`; the
LikeC4 bundle remains local to the architecture explanation.
