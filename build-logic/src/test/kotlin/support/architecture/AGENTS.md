# Architecture proof fixtures

This directory owns executable proof for architecture policy, admission, and compiled JVM effects.

## Local scope

- `IdeReadFirewallTest.kt` constructs bytecode for all fixed KVP-009 forbidden authorities and
  proves the three planned modules retain the exact narrow role, effects, and dependency sets. It
  also proves the generated report codec preserves the closed proof and rejects tampering.
- Keep negative fixtures synthetic and bounded; do not add forbidden production dependencies merely
  to make them compile.
