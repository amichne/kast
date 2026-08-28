# KVP-021 cancellable-read receipt guide

This directory owns the deterministic KVP-021 cancellable-read report, exact selector evidence,
and exact-head receipt progression. The product report records the canonical KVP-019 and KVP-020
completion identities and exact self-derived receipt digests; receipt tasks independently re-admit
both complete predecessor closures against the same live head.

Run RED and GREEN through dedicated `Test` tasks whose single include pattern is refined from the
unchanged canonical selector command. Each task records its exact Git head before execution,
revalidates it after execution, and emits canonical COMPLETE gate evidence. Receipt tasks admit
that evidence and never start a nested Gradle invocation.

Keep raw JSON, paths, selector text, and Gradle properties at report, gate, or receipt boundaries.
Expected failures remain closed typed data until those boundaries render them.
