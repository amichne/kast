# KVP-020 single-flight receipt guide

This directory owns the deterministic KVP-020 single-flight report and exact-head receipt
progression. The report binds the `READ_RUNTIME` authority, `ProjectReadPermit`, exact project and
epoch scope, the four closed controller states, one active permit, one queued request, finite
cancellation and retirement causes, the exact 31-transition projection, and zero forbidden work
or retention.

Re-admit KVP-014 and KVP-019 completion at the same Git head before generating or admitting the
report. Keep KVP-014 first in every predecessor projection. Report generation and mutation checks
read only those completion receipts and write only the declared generated report.

Keep raw JSON, receipt bytes, paths, and Gradle properties at report or receipt boundaries.
Expected failures remain closed typed data until a Gradle task renders them.

The `cancellable/` child owns KVP-021's product-claim report, dedicated exact-selector Test
evidence, direct KVP-019/KVP-020 re-admission, and completion closure. Its Test tasks must not
become dependencies of the default `test` task used by KVP-020 receipt execution.
