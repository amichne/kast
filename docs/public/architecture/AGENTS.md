# Public architecture assets

`specification.c4`, `model.c4`, and `views.c4` are the authored LikeC4
authority. Keep `runtime-flow` as the first, sparse view and
`module-ownership` as a separate in-flow disclosure. Do not connect the views
through LikeC4 browser navigation or another dialog surface.

`likec4-views.mjs` is generated. Do not edit it by hand. Regenerate and
validate it with the commands in `docs/AGENTS.md`, and keep the web-component
prefix `kast` stable for the explanation-page embed.

The model may summarize module families, but every ownership and dependency
claim must remain consistent with the repository topology in the root
`AGENTS.md`.
