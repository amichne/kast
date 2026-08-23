# Locked LikeC4 tooling

This directory owns the npm manifest and lockfile for public LikeC4 validation and web-component
generation. Keep `likec4` exact in `package.json`, commit every lockfile update, and use `npm ci`
before invoking the local executable. Do not replace the locked graph with `npx` resolution.

Run `python3 docs/test_public_docs.py` after changing this directory.
