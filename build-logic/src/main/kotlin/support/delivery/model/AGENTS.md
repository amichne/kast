# Delivery model guide

This directory owns pure Kotlin representations for the VFS-passive delivery program and KVP-001 authority refinement.

- `DeliveryProgramModel.kt` validates graph structure and derives deterministic program and requirement projections.
- `ProgramAuthorityModel.kt` refines raw configuration into expectations and admits complete exact-head authority documents.
- `ProgramAuthorityGeneration.kt` derives source identity from declared SHA-256 evidence. It must not read files, inspect paths, start processes, or choose identity by candidate order.

Expected authority failure stays in closed sealed data. Raw strings may reappear only in the Gradle task or JSON boundaries under `../tasks`.

Run the focused admission and generation tests named by the parent guide after changing these types.
