# KVP-024 endpoint-publication receipt guide

This directory owns KVP-024's canonical endpoint-publication report, exact selector evidence, and
exact-head receipt progression.

- Bind direct KVP-013 then KVP-023 completion receipts at one live authority snapshot.
- Admit exact `PREPARED -> SOCKET_BOUND -> READY` transitions. PREPARED already retains the
  admitted Project root, constructed read runtime, and descriptor-v2 inputs.
- Bind all fourteen descriptor-v2 fields to typed sources, one UDS bind, one atomic same-parent
  descriptor publication, and one endpoint per Project. No non-atomic move fallback is allowed.
- Reject wrong-root, partial-runtime, duplicate, occupied-path, bind, and publication cases before
  READY. Roll back only the owned bound socket and owned temporary descriptor.
- Run the fixed canonical selectors through dedicated `Test` tasks. The default
  `:ide-plugin:test` task must remain independent of every KVP-024 report, gate, and receipt task.

Raw JSON, receipt bytes, paths, and Gradle properties stay at report, gate, or receipt boundaries.
Expected failures remain closed typed data until those boundaries render them.
