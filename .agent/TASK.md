# Task Contract

## Goal

A client can use Kast's existing eleven-operation public surface end to end without ambiguous discovery completeness, class-only discovery, opaque or dead-end symbol evidence, session-only selectors, semantic-outcome process failures, undiscoverable single-use transport, or opaque change-authority rejection.

## Allowed Writes

- `.agent/TASK.md`
- `.github/workflows/agent-source-export.yml` (temporary; absent from the final tree)
- `protocol/contract/`
- `protocol/registry/`
- `protocol/wire/`
- `symbol/contract/`
- `symbol/service/`
- `symbol/intellij/`
- `relation/contract/`
- `relation/service/`
- `relation/intellij/`
- `traversal/contract/`
- `traversal/service/`
- `runtime/server/`
- `runtime/composition/`
- `cli/`
- `indexer/`
- `evidence/contract/`
- `evidence/sqlite/`
- `change/contract/`
- `change/plan/`
- `change/apply/`
- `change/verify/`
- `change/recovery/`
- `change/intellij/`
- `.github/scripts/`
- `scripts/`
- `AGENTS.md` files beneath the allowed module roots when ownership guidance must change with code

No other paths may be modified.

## Allowed Reads

- The complete repository.
- The supplied Kast Public Surface Redesign plan.
- Current `amichne/kast` history, open pull requests, and CI evidence.
- Published `amichne/slopsentral` process guidance relevant to proof-carrying types and parse-don't-validate design.

## Non-Goals

- Adding a twelfth public operation ID or changing the target Gradle topology.
- Reintroducing an aggregate backend, generic service locator, raw semantic edit endpoint, or hidden cost escalation.
- Implementing MCP, streaming, or unrelated product expansion.
- Reworking verified mutation beyond making authority admission and rejection diagnosable.
- Refactoring unrelated code.
- Fixing unrelated failures.
- Adding optional improvements unrelated to the supplied public-surface defects.

## Red Proof

Command:

```shell
./gradlew :symbol:intellij:test :runtime:composition:test :protocol:contract:test :protocol:wire:test :relation:service:test :cli:test
```

Expected failure:

Focused regression tests fail because discovery can exhaust work on unrelated names; members are not reliably discoverable; describe and relations discard structured location evidence; selectors require live authority maps; semantic rejections exit non-zero; transport has no supported descriptor or connection reuse; and change authority failures are opaque.

## Green Proof

Command:

```shell
./gradlew test verifyKastModuleGraph verifyForbiddenEffects verifyNoLegacyArchitecture runtimeDeliveryMvpAcceptance && python3 .github/scripts/check-repository-shape.py --root .
```

## Done When

- Discovery reports `Complete` only after terminal enumeration and distinguishes a true miss from bounded work.
- Public discovery accepts a closed kind and returns member declarations when requested.
- Describe and relation results preserve structured symbol and location evidence without caller parsing.
- Every emitted selector is self-describing, generation-bound, reconstructable after authority recreation, and rejects stale evidence with a closed failure.
- Location, file-structure, and bounded text discovery are available through the existing `symbol.discover` operation without adding a public operation ID.
- Semantic `Rejected` outcomes remain machine-readable data and do not become process or transport failure.
- A running indexer publishes a versioned endpoint descriptor and supports multiple framed requests on one connection.
- Change authority rejection names the missing proof or capability and its admission path.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- Baseline: `amichne/kast@729ad3e00cc5eba622d2a292eef36151e3643e01`.
- Implementation not started.

## Out-of-Scope Findings

- None.
