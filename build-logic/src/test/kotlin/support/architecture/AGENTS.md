# Architecture proof fixtures

This directory owns executable proof for architecture policy, admission, and compiled JVM effects.

## Local scope

- `IdeReadFirewallTest.kt` constructs bytecode for all fixed KVP-009 forbidden authorities and
  proves the active plugin and workspace-read modules plus the planned runtime module retain the
  exact narrow role, effects, dependency sets, and monotonic materialization stage. It also proves
  the generated report codec preserves the closed proof and rejects tampering.
- Keep negative fixtures synthetic and bounded; do not add forbidden production dependencies merely
  to make them compile.
- `HostedReadPathPolicyTest.kt` binds the independent 120-authority contract, passive controls,
  inventory rejection, and predecessor proof. `HostedReadProjectClasspathTest.kt` and
  `HostedReadClasspathFixtures.kt` own exact project-artifact admission, archive failures,
  immutable byte snapshots, and stronger-effect rejection.
