# Trade-offs and non-goals

Konditional is intentionally opinionated. The current repo optimizes for typed
Kotlin integration and trustworthy runtime behavior, not for being a complete
feature-management platform.

## Trade-offs you accept

These trade-offs are part of the design, not temporary omissions.

- **Kotlin-first integration.** The strongest guarantees show up when your
  features, contexts, and callers are Kotlin types in the same codebase.
- **Code-centric authoring.** Rules live with the namespace definition, so
  teams review flag logic the same way they review other application logic.
- **Incremental operations model.** Runtime loading is snapshot-based and
  namespace-scoped rather than a full multi-tenant control plane.
- **Honest boundary behavior.** External snapshots fail fast and explicitly
  instead of being partially tolerated.

## Things Konditional is not trying to be

The current repo is not trying to solve these problems directly.

- A hosted SaaS flag platform
- A GUI for product managers or operators
- A language-neutral industry standard
- A full experimentation and analytics suite
- A no-code migration layer for every legacy provider

## How to read those limits

If your main problem is code-level trust, typed results, deterministic
evaluation, and safe namespace-local runtime updates, these non-goals are
often acceptable. If your main problem is centralized operational tooling,
they are probably deal breakers.

## Next steps

The comparison page puts these trade-offs next to common alternatives so you
can decide whether Konditional is the right tool or just the right core
library inside a larger setup.

- [Compare Konditional to common alternatives](comparison.md)
- [Read the staged adoption path](adoption-path.md)

