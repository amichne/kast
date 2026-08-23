# Locked LikeC4 tooling

This directory owns the npm manifest and lockfile for public LikeC4 validation and web-component
generation. Keep `likec4` exact in `package.json`, commit every lockfile update, and use `npm ci`
before invoking the local executable. Do not replace the locked graph with `npx` resolution.

`generate_bundle.py` is the generation boundary. It records the lockfile and semantic-model
fingerprints in the committed asset. `canonicalize_bundle_model.mjs` requires complete layout
fields and non-empty tool-owned view hashes, then removes the layout fields. The generator compares
every remaining authored field with LikeC4's compute-only JSON export while excluding only the
derived view hashes from the cross-host comparison. It checks generated and committed wrapper
envelopes independently. Use the generator instead of invoking LikeC4 generation directly.

Run `python3 docs/tooling/likec4/generate_bundle.py --check` and
`python3 docs/test_public_docs.py` after changing this directory.
