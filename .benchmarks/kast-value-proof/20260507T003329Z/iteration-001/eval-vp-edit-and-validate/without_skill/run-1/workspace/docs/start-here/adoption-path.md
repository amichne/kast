# Adoption path

The safest way to adopt Konditional is to grow it in layers. Start with the
typed evaluation path inside one team-owned module, then add rollout and JSON
loading when the local model already feels like a win.

## Phase 1: local namespace

Start with `konditional-engine` only. Define one namespace beside the feature
owner, keep the first caller nearby, and ship a small typed decision through
`evaluate(...)`.

This phase proves the most important question first: do typed feature values
and namespace-local ownership improve the code you already maintain
every day.

## Phase 2: rollout and diagnostics

Once one namespace is stable, add `rampUp { ... }` and `explain(...)` for the
flags that need rollout visibility. This keeps progressive delivery tied to a
stable ID and a deterministic decision path.

This phase is where the determinism story becomes operationally useful instead
of purely architectural.

## Phase 3: JSON boundary

Add `konditional-json` when you are ready to bring in external snapshots. Use
`fromJson(json)` at the boundary and branch on `ParseResult` so malformed
payloads fail without mutating live state.

This phase turns runtime delivery into an additive boundary, not a rewrite of
the namespace model.

## Phase 4: operational hardening

After the first namespace and first boundary are stable, expand the surface.
Common next steps are adding more namespaces, documenting module ownership, and
writing invariant-focused tests for determinism, isolation, and boundary
failures.

## Next steps

The quickstart follows the same sequence in a small working path.

- [Start the quickstart](../quickstart/index.md)
- [Read the guarantees before broader rollout](../guarantees/index.md)

