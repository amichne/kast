# Engineering evidence guidance

This directory owns internal, revision-bound engineering evidence. Zensical does not publish it.

## KVP-015 epoch ledger

`ide-read-epoch-ledger.md` is the reader projection of the executable KVP-015 report. Keep its
signal order, IDEA build, movement cases, and forbidden-work observations equal to
`workspace/intellij-read/build/reports/KVP-015-epoch-ledger.json`.

The ledger characterizes supported platform observations. It does not own `ProjectReadEpoch`,
freshness admission, read scheduling, or semantic operation behavior. Later KVP tasks own those
types and effects.

Run both module characterization tasks after changing this directory. Then run
`bash scripts/verify_bundle.sh` to prove that the document matches the typed delivery authority.
