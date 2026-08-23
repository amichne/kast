# Public architecture assets

`specification.c4`, `model.c4`, and `views.c4` are the authored LikeC4
authority. Keep `runtime-flow` as the first, sparse view and
`module-ownership` as a separate in-flow disclosure. Do not connect the views
through LikeC4 browser navigation or another dialog surface.

`likec4-views.mjs` is generated. Do not edit it by hand. Regenerate and
validate it with `python3 docs/tooling/likec4/generate_bundle.py`, and keep the
web-component prefix `kast` stable for the explanation-page embed. Its first
two lines carry the exact npm lockfile and compute-model fingerprints. The
compute model is the stable generated boundary; layout geometry is validated
as complete presentation data but may vary across supported hosts.

The model may summarize module families, but every ownership and dependency
claim must remain consistent with the repository topology in the root
`AGENTS.md`.
