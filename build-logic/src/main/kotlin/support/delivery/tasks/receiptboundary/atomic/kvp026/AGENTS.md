# KVP-026 atomic proof policy

This package owns the sole graph-derived KVP-026 task packet, named-case execution evidence,
dependency admission, content-scoped report, and v2 completion receipt.

- Admit the pinned KVP-013 and KVP-024 legacy receipts and the freshly revalidated KVP-025 v2
  receipt before observing KVP-026 work.
- Derive selectors, case names, forbidden-work obligations, paths, and receipt identity only from
  the canonical Kotlin graph packet.
- Observe from the admitted KVP-024 historical head, retain only commits with KVP-026-owned paths,
  and admit mixed checkpoint paths only through the graph-derived KVP-025/KVP-026/KVP-027 scope
  union.
- Skip the named test task only after the complete relevant-input and dependency closure admits.
- Emit exactly one KVP-026 report and one KVP-026 completion receipt.
