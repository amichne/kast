# Epoch revalidation tests

This directory owns KVP-022 retained-source epoch revalidation fixtures and selectors.

- Keep `EpochRevalidationNegativeTest` runnable by the default test task and prove movement,
  incomparability, phase-specific rejection, exact cancellation identity, and unchanged outer
  KVP-021 outcomes.
- Keep `EpochRevalidationTest` runnable by the default test task and prove stable detached
  completion plus exact continuation preservation.
- Count observations and semantic executions explicitly. A BEFORE rejection executes no semantic
  work; every admitted attempt executes at most once; no cancellation path retries or reuses an
  epoch.
