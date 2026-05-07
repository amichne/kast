# When it fits

Konditional is strongest when the flag model is part of application design,
not just release plumbing. This page helps you decide whether the current
repo's shape lines up with your needs.

## Good fit signals

These signals usually point toward a productive first adoption.

- You are already building Kotlin services or Kotlin application modules.
- You want feature values that are more expressive than a fleet of booleans.
- You care that the same context and the same snapshot always evaluate the same
  way.
- You want local namespace ownership before you introduce a shared control
  surface.
- You can start in one team-owned module and expand gradually.

## Weak fit signals

These signals usually mean you should either defer adoption or treat
Konditional as a smaller piece of a broader flag strategy.

- You need a hosted control plane, GUI workflows, or non-engineer operators on
  day one.
- You need a single cross-language flag contract that multiple non-Kotlin
  stacks evaluate directly.
- You need experimentation, approval workflows, or governance features that
  live outside the application repository.
- You want a drop-in replacement for a vendor platform without owning the code
  integration yourself.

## Practical decision rule

The cleanest first use case is a Kotlin team that owns one domain decision and
is willing to start with code-defined flags. If that describes your current
state, Konditional can be valuable even before you introduce remote config.

## Next steps

If the fit looks promising, read the trade-offs directly. That page is the
fastest way to decide whether the current limits are acceptable.

- [Read the trade-offs](trade-offs-and-non-goals.md)
- [See the adoption path](adoption-path.md)

