# KVP-038 clean-checkout proof policy

This package owns the graph-derived KVP-038 packet, predecessor admission, clean-checkout evidence
refinement, implementation-scope enforcement, and content-scoped receipt.

- Execute detached work only through this package's `prove-clean-checkout.sh` boundary.
- Admit the complete KVP-008 legacy receipt/report lineage plus KVP-036 and KVP-037 v2 outputs.
- Require KVP-036 at the observed exact head and reuse KVP-008/KVP-037 only by content evidence.
- Select implementation commits only through the packaging harness anchor and enforce graph writes.
- Refine shell evidence into generated Kotlin serialization documents before receipt issuance.
