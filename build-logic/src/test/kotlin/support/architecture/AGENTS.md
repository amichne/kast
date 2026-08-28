# Architecture proof fixtures

This directory owns executable proof for architecture policy, admission, and compiled JVM effects.

## Local scope

- `IdeReadFirewallTest.kt` constructs bytecode for all fixed KVP-009 forbidden authorities and
  proves the active plugin, workspace-read, and runtime-read modules retain the exact narrow role,
  effects, dependency sets, and `RUNTIME_SPLIT` materialization stage. It also owns the focused
  KVP-024 endpoint-effect authority fixture, proves the generated report codec preserves the closed
  proof, and rejects tampering.
- Keep negative fixtures synthetic and bounded; do not add forbidden production dependencies merely
  to make them compile.
- `HostedReadPathPolicyTest.kt` binds the independent 120-authority contract, passive controls,
  inventory rejection, and predecessor proof. `HostedReadProjectClasspathTest.kt` and
  `HostedReadClasspathFixtures.kt` own exact project-artifact admission, archive failures,
  immutable byte snapshots, and stronger-effect rejection.
- `gradle/` owns executable proof for raw hosted-input refinement and finite task failures.
