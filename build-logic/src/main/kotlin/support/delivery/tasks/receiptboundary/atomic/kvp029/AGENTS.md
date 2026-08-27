# KVP-029 atomic proof policy

This package owns the sole graph-derived KVP-029 task packet, named-case execution evidence,
dependency admission, content-scoped report, and v2 completion receipt.

- Admit the preserved KVP-021 and KVP-023 v1 receipts plus the freshly revalidated KVP-028 v2
  receipt and exact report digest before observing KVP-029 work.
- Derive commands, case names, forbidden-work obligations, paths, and receipt identity only from
  the canonical Kotlin graph packet.
- Observe from the last task-owned KVP-028 implementation checkpoint, then retain the
  dependency-closed delta from the first KVP-029-exclusive path.
- Preserve structurally admitted historical implementation scope when dependency digests or
  relevant inputs change; replay only through its last admitted implementation commit.
- Skip the named gate-evidence task only after the complete relevant-input and dependency closure
  admits.
- Preserve the report and receipt's original observed head when that closure is unchanged at a
  later unrelated repository head; require the two observations to match before reuse.
- Emit exactly one KVP-029 report and one KVP-029 completion receipt.
