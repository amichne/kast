# KVP-022 epoch-revalidation receipt guide

This directory owns KVP-022's deterministic epoch-revalidation report, exact selector evidence,
and exact-head receipt progression. The report binds the sole KVP-021 completion digest, separate
BEFORE and AFTER observations, all three epoch relations, finite phase failures, at most one
semantic execution per attempt, and zero retry, prior-epoch reuse, or forbidden work.

Run RED and GREEN through dedicated `Test` tasks whose single include pattern is refined from the
unchanged canonical selector command. The default `test` task remains independent of this report
and does not exclude either selector. Each dedicated task records independently judgeable BEFORE
and AFTER head observations and emits canonical COMPLETE evidence without nested Gradle execution.

Keep raw JSON, paths, selector text, and Gradle properties at report, gate, or receipt boundaries.
Expected failures remain closed typed data until those boundaries render them.

The `dispatch/` child owns KVP-023's exact four-operation read-runtime report, dedicated
nonrecursive Test gates, direct KVP-009/KVP-016/KVP-022 re-admission, and completion closure. The
default runtime `test` task must remain independent of all KVP-023 report and receipt tasks.
