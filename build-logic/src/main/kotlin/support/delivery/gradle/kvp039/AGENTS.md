# KVP-039 exact-head CI proof policy

This package owns the graph-derived KVP-039 packet, exact-head KVP-038 predecessor admission,
pull-request-head workflow refinement, implementation-scope enforcement, and content receipt.

- Admit KVP-038 only when its clean-checkout report and receipt bind the observed repository head.
- Treat `.github/workflows/ci.yml` as raw boundary input and refine it into a closed CI capability.
- Exercise stale or merge-head workflow evidence only as an in-memory negative fixture.
- Bind the legal report to the workflow digest, predecessor receipt digest, and exact head.
