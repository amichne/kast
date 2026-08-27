# KVP-011 atomic proof policy

This package owns the sole graph-derived KVP-011 packet, named layout evidence, dependency
admission, write-scope enforcement, content-scoped reuse, and v2 completion receipt.

- Preserve the pinned KVP-010 legacy receipt without regenerating the admitted prefix.
- Re-admit KVP-025 and the current exact-head KVP-031 receipt plus their physical outputs.
- Derive commands, cases, forbidden work, paths, and receipt identity only from the Kotlin graph.
- Classify a checkpoint as KVP-011 implementation only when it changes a KVP-011-exclusive
  physical owner; a later shared graph/projection edit cannot recover KVP-011 ownership.
- Execute the fixed negative fixtures and legal archive scan before binding the physical layout
  report. Never infer classpath safety from filenames or size alone.
- Emit exactly one KVP-011 completion receipt; no RED/GREEN receipt pair is created.
