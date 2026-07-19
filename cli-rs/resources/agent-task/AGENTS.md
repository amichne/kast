# Agent task resource guide

This directory owns the provider-neutral shell entrypoint, compact guidance,
strict workflow policy schema, and Gradle receipt adapter for `kast agent task`.

- `kast-agent-task` is a policy-free POSIX launcher. It may resolve only the
  executable `kast` beside itself and must forward arguments and stdin without
  parsing either.
- `workflow.schema.json` describes `.kast/workflow.toml` after TOML decoding.
  Keep it strict, versioned, and aligned with the typed Rust policy model.
- `guidance.md` owns the compact lifecycle wording embedded by setup and
  provider packages.
- `gradle-receipt.init.gradle` observes Gradle task outcomes and atomically
  writes the strict receipt requested by the task core. It must not select
  tasks or infer validation policy.
- Provider lifecycle decisions belong to the Rust task core. Do not add state,
  Gradle inference, provider envelopes, fallback lookup, or repair behavior here.

Verify launcher relocation, paths with spaces, stdin forwarding, and refusal to
use ambient `PATH`. Validate the schema and run the focused packaged-resource
tests after either resource changes.
