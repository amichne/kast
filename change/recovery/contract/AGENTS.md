# Change recovery contract guide

`:change:recovery:contract` owns detached proof that exact add-declaration recovery material has
been durably prepared. It depends only on the mutation contract and owns no filesystem, database,
IntelliJ, workspace, source-write, or rollback effect.

## Invariants

- A durable recovery proof is PlanId-, target-, and exact-before-image-bound.
- Preparation proof carries no source-write, rollback, file-handle, or live host capability.
- Expected preparation and assembly failures remain finite typed data.

## Verification

Run `./gradlew :change:recovery:contract:test`.
