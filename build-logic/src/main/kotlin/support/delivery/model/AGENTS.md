# Delivery model guide

This directory owns pure Kotlin representations for the VFS-passive delivery program, KVP-001
authority refinement, and proof-receipt admission.

- `DeliveryProgramModel.kt` owns checked delivery identities, closed refinement/outcome/progression
  types, task/program structure, dependency-closed batch ownership, and graph validation.
- `ProgramAuthorityModel.kt` refines raw configuration into expectations and admits complete exact-head authority documents.
- `ProgramAuthorityGeneration.kt` derives source identity from declared SHA-256 evidence. It must not read files, inspect paths, start processes, or choose identity by candidate order.
- `proof/` owns legacy receipt admission, the pinned KVP-024 prefix, atomic task-proof derivation,
  and v2 content-scoped or milestone-exact receipts.

Expected authority failure stays in closed sealed data. Raw strings may reappear only in the Gradle task or JSON boundaries under `../tasks`.

`projection/` owns admitted task packets, the KVP-005 deterministic five-artifact bundle,
dedicated generated JSON Schema documents, and closed admission result. Generic JSON traversal is
restricted to applying those schema documents at the projection boundary.

`DeliveryGateGraph.kt` owns the KVP-006 bijection between the 89 typed gates and program-derived
Gradle receipt-task names, including exact predecessor inputs and unique receipt outputs.

`DeliveryState.kt` owns KVP-008's pure admitted-completion fold. It derives closed task and
requirement states, the deterministic critical path, and an unconstructable terminal proof only for
the complete one-head closure.

Run `DeliveryProofNegativeTest` and `DeliveryProofTest` plus the focused authority admission and
generation tests named by the parent guide after changing these types.
