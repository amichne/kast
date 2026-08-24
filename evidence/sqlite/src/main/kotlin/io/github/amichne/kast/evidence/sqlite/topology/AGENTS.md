# SQLite topology persistence guide

This package owns the topology v2 tables, atomic content writer, row admission, snapshot store, and
snapshot-backed relation compiler.

## Local invariants

- Persist symbols under positive snapshot-local row IDs and bind edge endpoints to those IDs.
  Declarations may share a compiler identity only when their exact file/range identities differ.
- Write the manifest, files, symbols, and edges in one transaction and admit the same complete
  counts and digest before reuse or traversal.
- Re-admit exact paths, ranges, row references, counts, and digests on read. Map malformed topology
  rows to `CORRUPT_SNAPSHOT`; do not expose a partial graph.

## Focused verification

Run `./gradlew :evidence:sqlite:test`.
