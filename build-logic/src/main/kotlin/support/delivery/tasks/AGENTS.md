# Delivery task-boundary guide

This directory owns Gradle effects for the VFS-passive delivery program.

- Projection tasks write or verify the two deterministic program projections.
- `DeliveryTaskBoundaries.kt` is the sole owner of Git metadata reads, bounded authority-source reads, source hashing, and atomic text replacement.
- `ProgramAuthorityJsonBoundary.kt` uses generated serializers for every closed JSON document.
- Authority generation observes exact bytes before writing the ledger and contradiction projection. Authority verification re-observes Git HEAD and source bytes and fails closed on movement or mismatch.
- The negative task uses in-memory fixtures and must not read or write canonical authority sources.
- `DeliveryReceiptJsonBoundary.kt` is the generated receipt codec.
  `Kvp001ReceiptTaskSupport.kt` refines the authority reports and exact artifact bytes into receipt
  expectations. `Kvp001ReceiptTasks.kt` owns the root RED, GREEN, completion, and re-admission
  boundaries. Every write is followed by exact-head revalidation and read-back admission.
- `Kvp002ReceiptTasks.kt` executes only the two fixed included-build model gates, owns the generated
  KVP-002 type-proof report, and closes RED, GREEN, and completion over the admitted KVP-001 receipt.

Do not add network access, process start, repository walks, source substitution, fallback, VFS refresh, or writes outside declared Gradle outputs.
