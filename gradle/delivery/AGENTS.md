# VFS-passive delivery projections

This directory owns the checked-in machine projections of the typed program in `build-logic/src/main/kotlin/support/delivery`.

- `kast-vfs-passive-reused-index-program.json` is the complete exact-head program projection.
- `kast-vfs-passive-requirements.json` is the requirement-to-task and gate trace derived from the same validated program and fingerprint.
- `authority-sources/` owns immutable, digest-identified source bytes admitted by KVP-001; follow
  its guide and preserve significant terminal newlines.
- `schema/` owns the closed JSON Schema contracts. The proof-receipt contract requires receipt and
  base identities and string-valued open observation entries matching the generated Kotlin codec.
  The KVP-013 IDE endpoint schema closes the exact 14-field v2 descriptor projected by
  `:protocol:wire:generateIdeEndpointDescriptorReport`.

Live gate and task-completion receipts belong under `build/reports/delivery/receipts`. They bind the
commit observed after checkout and must never be checked in.

Do not edit either projection by hand. Regenerate both atomically with `./gradlew generateKastVfsPassiveProjection`, then verify without mutation with `./gradlew verifyKastVfsPassiveProjection` and `scripts/verify_bundle.sh`.
