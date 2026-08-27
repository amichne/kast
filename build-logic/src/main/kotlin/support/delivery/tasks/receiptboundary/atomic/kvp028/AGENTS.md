# KVP-028 atomic proof policy

This package owns the sole graph-derived KVP-028 task packet, named-case execution evidence,
dependency admission, content-scoped report, and v2 completion receipt.

- Admit the preserved KVP-023 v1 receipt plus the freshly revalidated KVP-026 v2 receipt and its
  exact output digest before observing KVP-028 work.
- Derive commands, case names, forbidden-work obligations, paths, and receipt identity only from
  the canonical Kotlin graph packet.
- Observe from the last task-owned KVP-026 implementation checkpoint, skip admitted KVP-025 and
  KVP-027 checkpoints, then retain the dependency-closed delta from the first KVP-028-exclusive
  path.
- When a predecessor digest changes after successor work exists, structurally admit the prior
  report's static task authority and replay its exact Git scope. Rerun invalidated cases and issue
  fresh evidence without absorbing later task checkpoints into KVP-028.
- Skip the named gate-evidence task only after the complete relevant-input and dependency closure
  admits.
- Preserve the report and receipt's original observed head when that closure is unchanged at a
  later unrelated repository head; require the two observations to match before reuse.
- Emit exactly one KVP-028 report and one KVP-028 completion receipt.
