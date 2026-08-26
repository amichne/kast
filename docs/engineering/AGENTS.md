# Engineering evidence guidance

This directory owns internal, revision-bound engineering evidence. Zensical does not publish it.

## KVP-015 epoch ledger

`ide-read-epoch-ledger.md` is the reader projection of the executable KVP-015 report. Keep its
signal order, IDEA build, movement cases, and forbidden-work observations equal to
`workspace/intellij-read/build/reports/KVP-015-epoch-ledger.json`.

The ledger characterizes supported platform observations. It does not own `ProjectReadEpoch`,
freshness admission, read scheduling, or semantic operation behavior. Later KVP tasks own those
types and effects.

## KVP-016 detached model

`ide-detached-model.md` is the reader projection of the executable KVP-016 report. Keep its IDEA
build, capture mode, count and UTF-8 bounds, retained facets, rejected capabilities, forbidden-
effect observations, and focused selectors equal to
`workspace/intellij-read/build/reports/KVP-016-detached-model.json` and the canonical task.

The page records detached existing-project model evidence only. KVP-017 owns production epoch
identity and freshness admission.

Run the focused task named by each evidence page after changing that page. Then run
`bash scripts/verify_bundle.sh` to prove that the document matches the typed delivery authority.
