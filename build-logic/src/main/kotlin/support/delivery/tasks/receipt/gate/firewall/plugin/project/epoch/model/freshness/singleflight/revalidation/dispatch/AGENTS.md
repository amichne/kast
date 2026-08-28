# KVP-023 read-runtime receipt guide

This directory owns KVP-023's deterministic four-operation dispatch report, exact gate evidence,
and exact-head receipt progression.

- Bind the exact ordered `WORKSPACE_INSPECT`, `SYMBOL_DISCOVER`, `SYMBOL_RESOLVE`, and
  `SYMBOL_DESCRIBE` ports to their classifications from `CanonicalOperationDefinitions`.
- Reject every other canonical operation before dispatch. Mutation proof must retain explicit
  fifth-operation `RELATION_READ` and forbidden `:runtime:composition` witnesses.
- Re-admit KVP-009, KVP-016, and KVP-022 independently and preserve that order in report and
  receipt evidence.
- Run RED and GREEN through dedicated `Test` tasks whose fixed selectors derive from the unchanged
  canonical commands. GREEN also depends on the independently runnable default `test` task.
- Keep KVP-023 report, gate, and receipt dependencies out of the default `test` task; otherwise
  the KVP-020-to-KVP-023 receipt graph becomes cyclic.

Raw JSON, receipt bytes, paths, and Gradle properties stay at report, gate, or receipt boundaries.
Expected failures remain closed typed data until those boundaries render them.

The `endpoint/` child owns KVP-024's prepared-to-ready endpoint-publication report, dedicated
nonrecursive Test gates, direct KVP-013/KVP-023 binding, and exact-head completion closure. Keep
all KVP-024 dependencies out of the default `:ide-plugin:test` task.
