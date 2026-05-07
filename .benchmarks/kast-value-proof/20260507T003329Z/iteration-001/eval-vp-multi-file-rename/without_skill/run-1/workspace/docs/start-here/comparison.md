# Comparison

Konditional is best understood as a Kotlin library with strong architectural
guarantees, not as a hosted feature-management platform. This comparison is
intended to clarify the shape of the trade-offs rather than claim market
victory.

## At a glance

The biggest question is where you want the center of gravity to live: in typed
application code, in a Java framework, in a vendor-neutral standard, or in a
hosted control plane.

| Option | Best at | What you gain over Konditional | What you give up relative to Konditional |
| --- | --- | --- | --- |
| Konditional | Typed Kotlin integration with deterministic evaluation | Compile-time namespace and value typing, local ownership, explicit parse boundary | No GUI, no hosted control plane, Kotlin-first scope |
| Togglz | Java feature toggles with activation strategies and an admin console | Mature Java ecosystem fit and built-in admin UI patterns | Less Kotlin-specific type design and less emphasis on namespace-local typed contracts |
| FF4J | Java feature toggles with stores, audit, and a web console | Operational features and a management console | More framework surface, less emphasis on Kotlin-native typed call sites |
| OpenFeature | Standardized flag API and provider abstraction | Vendor-neutral integration model across backends | It is a standard layer, not a typed flag model or management system by itself |
| LaunchDarkly | Hosted feature management and operational control | UI, governance, experimentation, integrations, and control-plane workflows | Less code-local ownership and a different cost and dependency model |

## How to choose

Choose Konditional when your hardest problem is keeping flag behavior
trustworthy inside Kotlin code. Choose a hosted platform when your hardest
problem is multi-team operational control, non-engineer workflows, or
cross-language rollout management.

## A practical split

Some teams use these approaches together. For example, OpenFeature can be the
standardization layer for a broader platform strategy, while Konditional can
still be the local typed model inside Kotlin-owned code. The key is to be
clear about which layer owns type guarantees and which layer owns operations.

## Next steps

If you are still leaning toward Konditional, the adoption page shows the
smallest rollout path that keeps those trade-offs manageable.

- [Read the adoption path](adoption-path.md)
- [Start the quickstart](../quickstart/index.md)

