# Delivery bundle verification

This directory owns the dependency-free verifier for the VFS-passive delivery bundle.

- `verify_bundle.py` uses only the Python standard library to validate exact-head identity, fingerprints, graph invariants, requirement trace identity, and the JSON Schema subset used by the checked-in documents.
- `verify_kvp017_report.py` independently checks the exact KVP-017 product report resource against
  its engineering page, including ordered cases, observation limits, and every zero-effect field.
- `verify_kvp019_delivery.py` independently checks the KVP-019 task scope, typed report schema,
  product artifacts, test consumption, and receipt registration chain.
- `verify_kvp020_delivery.py` independently checks KVP-020's direct predecessors, executable write
  scope, narrow runtime module, runtime-split architecture, and generated projections.
- `verify_kvp021_delivery.py` independently checks KVP-021's admitted-Project execution scope,
  exact predecessors, cycle-free proof ownership, read-only dependency direction, and generated
  projections before executor behavior exists.
- `verify_bundle.sh` compiles the canonical Kotlin authority in isolation, regenerates both projections in a temporary directory, and compares their bytes with the checked-in artifacts.

Do not add host-package installation as a prerequisite. Run `scripts/verify_bundle.sh` from the repository root.
