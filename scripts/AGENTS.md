# Delivery bundle verification

This directory owns the dependency-free verifier for the VFS-passive delivery bundle.

- `verify_bundle.py` uses only the Python standard library to validate authority identity,
  fingerprints, graph invariants, the legacy/atomic proof seam, milestone head policy, requirement
  trace identity, and the JSON Schema subset used by the checked-in documents.
- `lib/proof_boundaries.py` owns the closed JSON subset, exact legacy-prefix manifest admission,
  KVP-024 historical seam check, and KVP-017 report/page contract used by public verifiers.
- `verify_kvp019_delivery.py` independently checks the KVP-019 task scope, typed report schema,
  product artifacts, test consumption, and receipt registration chain.
- `verify_kvp020_delivery.py` independently checks KVP-020's direct predecessors, executable write
  scope, narrow runtime module, runtime-split architecture, and generated projections.
- `verify_kvp021_delivery.py` independently checks KVP-021's admitted-Project execution scope,
  exact predecessors, cycle-free proof ownership, read-only dependency direction, and generated
  projections before executor behavior exists.
- `verify_kvp022_delivery.py` independently checks KVP-022's epoch-revalidation scope, exact
  predecessor and gates, read-only dependency direction, requirement trace, and generated
  projections without assuming whether product or receipt files exist yet.
- `verify_kvp023_delivery.py` independently checks KVP-023's exact three-predecessor scope,
  canonical graph gates, read-only runtime boundary, future dispatch ownership, and generated
  projections before the four-operation product or receipt closure exists.
- `verify_kvp024_delivery.py` independently checks KVP-024's endpoint-publication authority,
  canonical graph gates, hosted-plugin dependency boundary, product surface, generated report,
  and admitted completion receipt while allowing later endpoint-lifecycle files to refine the
  same owner.
- `verify_bundle.sh` compiles the canonical Kotlin authority in isolation, regenerates both projections in a temporary directory, and compares their bytes with the checked-in artifacts.

Do not add host-package installation as a prerequisite. Run `scripts/verify_bundle.sh` from the repository root.
