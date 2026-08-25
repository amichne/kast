# VFS-passive delivery projections

This directory owns the checked-in machine projections of the typed program in `build-logic/src/main/kotlin/support/delivery`.

- `kast-vfs-passive-reused-index-program.json` is the complete exact-head program projection.
- `kast-vfs-passive-requirements.json` is the requirement-to-task and gate trace derived from the same validated program and fingerprint.
- `schema/` owns the closed JSON Schema contracts.
- `receipts/` owns admitted proof receipts; no receipt is completion state by itself.

Do not edit either projection by hand. Regenerate both atomically with `./gradlew generateKastVfsPassiveProjection`, then verify without mutation with `./gradlew verifyKastVfsPassiveProjection` and `scripts/verify_bundle.sh`.
