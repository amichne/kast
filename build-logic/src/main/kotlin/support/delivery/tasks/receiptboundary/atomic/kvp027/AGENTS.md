# KVP-027 atomic proof policy

This package owns the sole graph-derived KVP-027 task packet, named-case execution evidence,
dependency admission, content-scoped report, and v2 completion receipt.

- Admit the freshly revalidated KVP-026 v2 receipt and its exact output digest before observing
  KVP-027 work.
- Derive commands, case names, forbidden-work obligations, paths, and receipt identity only from
  the canonical Kotlin graph packet.
- Observe from the admitted KVP-026 implementation head and retain only commits with
  KVP-027-owned paths; a checkpoint touching any other path rejects.
- Skip the named gate-evidence task only after the complete relevant-input and dependency closure
  admits.
- Emit exactly one KVP-027 report and one KVP-027 completion receipt.
