# KVP-031 atomic proof policy

This package owns the sole graph-derived KVP-031 task packet, named-case execution evidence,
dependency admission, exact-head report closure, and v2 completion receipt.

- Revalidate and admit the KVP-030 v2 receipt plus its exact report digest before observing KVP-031
  work.
- Derive commands, case names, forbidden-work obligations, paths, and receipt identity only from
  the canonical Kotlin graph packet.
- Observe from the last task-owned KVP-030 implementation checkpoint, then retain the
  dependency-closed delta from the first KVP-031-exclusive path.
- Skip the named gate-evidence task only when the complete relevant-input and dependency closure
  admits and the existing receipt binds the current exact head.
- A later repository head invalidates the receipt even when its content closure is unchanged; rerun
  the named cases and issue a new exact-head closure.
- Emit exactly one KVP-031 report and one KVP-031 completion receipt.
