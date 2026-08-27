# Delivery task-boundary guide

This directory owns Gradle effects for the VFS-passive delivery program.

- `projection/` writes or verifies the two deterministic data projections and three generated JSON
  Schemas, and owns the in-memory KVP-005 negative fixtures.
- `DeliveryTaskBoundaries.kt` is the sole owner of Git metadata reads, bounded authority-source reads, source hashing, and atomic text replacement.
- `ProgramAuthorityJsonBoundary.kt` uses generated serializers for every closed JSON document.
- Authority generation observes exact bytes before writing the ledger and contradiction projection. Authority verification re-observes Git HEAD and source bytes and fails closed on movement or mismatch.
- The negative task uses in-memory fixtures and must not read or write canonical authority sources.
- `receiptboundary/` owns generated v1/v2 receipt codecs, pinned legacy-prefix admission,
  graph-derived KVP-025/KVP-026/KVP-027 task packets and named-case evidence, and content-scoped v2
  issuance. Each atomic task revalidates its v2 predecessor before using that observed head as the
  next write baseline.
  `Kvp001ReceiptTaskSupport.kt` refines the authority reports and exact artifact bytes into receipt
  expectations. `Kvp001ReceiptTasks.kt` owns the root RED, GREEN, completion, and re-admission
  boundaries. `receipt/ReceiptIssuanceBoundary.kt` reuses a fully admitted same-expectation receipt;
  replacement writes are followed by read-back admission and exact-head revalidation.
- `receipt/` owns typed post-authority progression through KVP-010 and for KVP-012 through KVP-018.
  Each task executes only fixed gate argument vectors, owns or decodes generated proof reports, and
  closes over every direct predecessor receipt.

Do not add network access, process start, repository walks, source substitution, fallback, VFS refresh, or writes outside declared Gradle outputs.
