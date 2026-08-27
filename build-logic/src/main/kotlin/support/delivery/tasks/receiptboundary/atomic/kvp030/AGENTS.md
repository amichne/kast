# KVP-030 atomic proof policy

This package owns the sole graph-derived KVP-030 task packet, named-case execution evidence,
dependency admission, content-scoped report, and v2 completion receipt.

- Revalidate and admit the KVP-029 v2 receipt plus its exact report digest before observing KVP-030
  work.
- Derive commands, case names, forbidden-work obligations, paths, and receipt identity only from
  the canonical Kotlin graph packet.
- Observe from the last task-owned KVP-029 implementation checkpoint, then retain the
  dependency-closed delta from the first KVP-030-exclusive path.
- Skip the named gate-evidence task only after the complete relevant-input and dependency closure
  admits.
- Preserve the report and receipt's original observed head when that closure is unchanged at a
  later unrelated repository head; require the two observations to match before reuse.
- Emit exactly one KVP-030 report and one KVP-030 completion receipt.
