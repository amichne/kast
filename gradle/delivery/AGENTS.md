# VFS-passive delivery projections

This directory owns the checked-in machine projections of the typed program in `build-logic/src/main/kotlin/support/delivery`.

- `kast-vfs-passive-reused-index-program.json` is the complete typed program projection.
- `kast-vfs-passive-requirements.json` is the requirement-to-task and gate trace derived from the same validated program and fingerprint.
- `authority-sources/` owns immutable, digest-identified source bytes admitted by KVP-001; follow
  its guide and preserve significant terminal newlines.
- `schema/` owns the closed JSON Schema contracts. The proof-receipt v2 contract binds program and
  task-definition versions, predecessor/input/command/toolchain digests, complete observations,
  output digests, head policy, observed head, and self digest.
  The KVP-013 IDE endpoint schema closes the exact 14-field v2 descriptor projected by
  `:protocol:wire:generateIdeEndpointDescriptorReport`.

Live gate and task-completion receipts belong under `build/reports/delivery/receipts`. KVP-001..024
remain pinned v1 evidence; KVP-025+ receipts are content-scoped except the graph-declared exact-head
milestones. They must never be checked in.

Do not edit either projection by hand. Regenerate both atomically with `./gradlew generateKastVfsPassiveProjection`, then verify without mutation with `./gradlew verifyKastVfsPassiveProjection` and `scripts/verify_bundle.sh`.
