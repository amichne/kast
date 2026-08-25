# Delivery model guide

This directory owns pure Kotlin representations for the VFS-passive delivery program, KVP-001
authority refinement, and proof-receipt admission.

- `DeliveryProgramModel.kt` owns checked delivery identities, closed refinement/outcome/progression
  types, graph validation, and deterministic program and requirement projections.
- `ProgramAuthorityModel.kt` refines raw configuration into expectations and admits complete exact-head authority documents.
- `ProgramAuthorityGeneration.kt` derives source identity from declared SHA-256 evidence. It must not read files, inspect paths, start processes, or choose identity by candidate order.
- `DeliveryReceipt.kt` owns receipt identities, expectations, documents, issuance, admission, and
  finite failures. `DeliveryReceiptRefinement.kt` is the sole constructor boundary for their typed
  field aggregates and canonical payload digests.

Expected authority failure stays in closed sealed data. Raw strings may reappear only in the Gradle task or JSON boundaries under `../tasks`.

Run `DeliveryReceiptTest` plus the focused authority admission and generation tests named by the
parent guide after changing these types.
