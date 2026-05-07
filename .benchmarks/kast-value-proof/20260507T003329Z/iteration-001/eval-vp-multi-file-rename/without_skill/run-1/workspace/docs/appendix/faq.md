# FAQ

This page answers the short questions evaluators tend to ask after the first
pass through the site.

## Does Konditional work only with Kotlin?

The strongest story is Kotlin-first. The current repo is designed around typed
Kotlin namespaces, contexts, and feature values. If you need one shared flag
surface across many non-Kotlin stacks, Konditional is likely only one part of
the solution.

## Is there a GUI or hosted control plane?

Not in the current repo. Konditional is a library with a strict JSON boundary,
not a hosted feature-management platform.

## Do I need JSON to start?

No. You can begin with `konditional-engine` only, declare a namespace in code,
and call `evaluate(...)` directly.

## What is the easiest first adoption?

One namespace in one team-owned module, one typed feature value, and one
caller. Keep the first slice local and small.

## How does it compare to LaunchDarkly or other platforms?

Konditional optimizes for typed Kotlin integration and code-local ownership.
Hosted platforms optimize for control-plane workflows, UI, and broader
operational tooling. Neither shape replaces the other automatically.

## Can I use custom structured values?

Yes. Use `custom<T, C>(...)` with a type that implements `Konstrained`.
Object-backed values can also expose a schema for strict JSON validation.

## Next steps

If the FAQ answered the short version and you now want the full version, move
back into the evaluator, guide, or guarantee pages.

- [Return to start here](../start-here/index.md)
- [Return to the guides](../guides/index.md)
