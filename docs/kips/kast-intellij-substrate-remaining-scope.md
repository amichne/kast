# Kast IntelliJ substrate: remaining execution scope

**Status:** approved execution roadmap

**Prepared:** 2026-08-14

**Audience:** implementation agents and reviewers continuing the IntelliJ
substrate program

**Baseline:** `09750a68e43d5f01a4b1a0c5ede5add54b6ca67f`

This document replaces the original program and first-actionable-task pack as
the queue authority. It contains only work that remains after verified
add-declaration became publicly reachable. Historical plans remain evidence for
why the architecture exists, but their completed tasks must not be scheduled
again.

## Start state and execution authority

At the baseline, 24 of the original 34 KIPs are complete: KIP-001 through
KIP-005, KIP-010 through KIP-018, KIP-020 through KIP-022, and KIP-030 through
KIP-036. The S00-S12 task pack is historical. Native symbol reads, workspace
execution, durable add-declaration planning, and verified add-declaration are
available in immutable snapshot `snapshot-09750a68e43d`.

[PR #609](https://github.com/amichne/kast/pull/609) contains that baseline. As
of this document's preparation, it is open, mergeable, and green, but branch
protection reports it blocked without an approving review. Live GitHub state is
authoritative for its current merge status.

Execution authority, from strongest to weakest, is:

1. `AGENTS.md`, the nearest module `AGENTS.md`, and the active
   `.agent/TASK.md` govern each implementation task.
2. The typed architecture model under
   `build-logic/src/main/kotlin/support/architecture/` governs dependencies,
   effects, migrations, and retirement.
3. `gradle/architecture/kast-architecture-policy.json` is the generated review
   projection; it is not an independent policy source.
4. This document governs remaining scope, order, release gates, and exclusions.
5. The original program and handoffs explain completed work but cannot reopen
   it.

Before implementing any KIP below, create a new closed `.agent/TASK.md` for
that single KIP. Do not copy a completed S00-S12 contract or combine KIPs into
one implementation task.

## Remaining delivery roadmap

The only remaining program work is merge gate R0 followed by KIP-040 through
KIP-042 and KIP-050 through KIP-056.

```text
R0 ─┬─> KIP-040 -> KIP-041 -> KIP-042 ──────────────────────┐
    ├─> KIP-050 ─────────────────────────────────────────────┤
    ├─> KIP-051 ─────────────────────────────────────────────┤
    ├─> KIP-052 ─────────────────────────────────────────────┤
    └─> KIP-053 ─────────────────────────────────────────────┘
                                                              v
                                                         KIP-054
                                                              v
                                                         KIP-055
                                                              v
                                                         KIP-056
```

R0 is a delivery gate, not a new KIP. The rename chain and KIP-050 through
KIP-053 may advance independently after R0. KIP-054 cannot begin until all five
public mutation slices are complete.

| Work | Exact prerequisites |
| --- | --- |
| KIP-040 | R0; completed KIP-001, KIP-011, and KIP-012 |
| KIP-041 | KIP-040; completed KIP-015 and KIP-033 |
| KIP-042 | KIP-041; completed KIP-016 and KIP-035 |
| KIP-050 | R0; completed KIP-036 |
| KIP-051 | R0; completed KIP-015 and KIP-036 |
| KIP-052 | R0; completed KIP-036 |
| KIP-053 | R0; completed KIP-002, KIP-020, and KIP-033 |
| KIP-054 | KIP-042 and KIP-050 through KIP-053 |
| KIP-055 | KIP-054; completed KIP-018 |
| KIP-056 | KIP-042, KIP-050 through KIP-053, and KIP-055; completed KIP-022 |

### R0 — Land the completed baseline

Obtain the required approval and merge PR #609. Do not change the proven head
unless a focused merge blocker requires a correction. If the head changes,
repeat exact-head CI and public installation proof before merge. After merge,
require the merge commit's `main` checks to pass and begin remaining KIPs from
that admitted `main` state.

Preserve `snapshot-09750a68e43d` as immutable evidence. Do not republish,
retag, or rewrite it. R0 is complete when PR #609 is merged, post-merge `main`
is green, and M0 through M4 are recorded as closed.

### KIP-040 — Characterize the native rename protocol

Establish supported-build evidence for IntelliJ `RenameProcessor` target
selection, related renames, override behavior, conflicts, affected files,
cancellation, command duration, and silent-abort detection.

The characterization must cover private or file-local declarations, public
multi-file declarations, overloads, override families, properties and
accessors, and same-named unrelated declarations. It must produce a closed
supported-or-unsupported result for each strategy. It grants no public mutation
authority and freezes no plan before actual processor behavior is known.

Acceptance requires real IntelliJ fixtures that identify search work occurring
before and during `RenameProcessor.run`, exact affected-file evidence, and
negative proof for undeclared related renames, same-name collisions, and silent
abort.

### KIP-041 — Extract a detached rename plan and native apply adapter

Introduce operation-specific rename intent, `PlannedRename`, revalidated
target and family evidence, exact occurrences, declared write set,
before-images, strategy, obligations, expected semantic delta, recovery
capability, and native apply capability.

Planning must release all live IntelliJ authority before returning. Apply must
consume the persisted plan, revalidate its proofs, execute only a KIP-040
supported processor strategy, and compare the actual write set with the
declared write set. Raw text edits, search-and-replace fallback, and unrelated
automatic renames are closed failures.

Acceptance requires deterministic plan replay on the same state, stale-plan
rejection, exact write-set closure, retained recovery authority after every
post-prepare non-success, and no terminal success before G1 verification.

### KIP-042 — Route and verify public multi-file rename

Route the public rename journey through durable plan, approval, revalidation,
recovery preparation, native apply, targeted transition, and operation-specific
verification. Remove or mechanically block the old direct rename path.

Verification must prove old-identity removal, new-identity existence, exact
reference retargeting, intended family behavior, diagnostics, and preservation
of unrelated declarations against one resulting generation. It must return a
verified receipt or a finite rejected, rolled-back, or recovery-required state.

Acceptance requires an installed public journey over enterprise-shaped
multi-file fixtures plus crash, cancellation, movement, and concurrency faults
at every durable boundary. Truncated or unsupported relationship evidence must
never authorize apply or verification.

### KIP-050 — Route verified add-file

Deliver one authored Kotlin file creation under exact Gradle source-root
ownership. Define operation-specific intent, target-absence proof, plan,
revalidation, recovery, apply, transition, verification, and receipt types.

The operation must prove canonical containment, authored provenance,
writability, package and declaration identity, VFS admission, and a resulting
generation. Generated or ambiguously owned targets are rejected. No general
filesystem writer or Java mutation support is introduced.

Acceptance requires positive creation, existing-target, generated-target,
symlink escape, VCS prompt, publication failure, and PSI non-admission tests
through the installed public route.

### KIP-051 — Route one exact replacement kind

Choose exactly one supported replacement kind: an expression or a declaration
body. Define dedicated intent, exact selector input, replacement value, plan,
expected delta, write set, revalidation, recovery, apply, verification, and
receipt types.

A raw range plus replacement string cannot authorize semantic source write.
Preflight must use copied PSI or another supported platform mechanism.
Verification must detect binding, diagnostic, and semantic changes outside the
expected delta. Additional replacement kinds and regex replacement remain
future scope.

Acceptance requires same-name and overload fixtures, stale selectors and
plans, syntax and binding failures, undeclared changes, and exact G1 semantic
verification through the public route.

### KIP-052 — Route verified optimize-imports

Characterize the supported IntelliJ import optimizer, then define exact file
scope, planned import delta, recovery evidence, short-command apply, resulting
generation, and operation-specific verification.

Only planned import sections may change. The operation cannot widen to the
repository, format unrelated code, or combine import optimization with rename
or replacement.

Acceptance requires unused, conflicting, aliased, wildcard, and already-stable
import fixtures; actual affected-file comparison; diagnostics reconciliation;
and proof that non-import semantics remain unchanged.

### KIP-053 — Implement the typed external-file lane

Define a separate descriptor-relative lane for proven unmodeled files or
explicit regeneration authority. Introduce `UnmodeledFileTarget` or an
equivalent stronger type carrying canonical containment, no-symlink-escape,
classification, and operation authority.

The lane uses atomic replacement and exact before/after identities, then joins
the shared recovery, transition, and verification suffix. It must be
mechanically unable to target modeled Kotlin or Java source. Generated status
cannot be inferred from a path, and generator execution requires a separate
future operation contract.

Acceptance requires adversarial containment and classification fixtures,
selected-lane execution proof, recovery faults, and architecture proof that no
semantic plan can obtain external filesystem authority.

### KIP-054 — Remove aggregate mutation authority

After KIP-042 and KIP-050 through KIP-053 are complete, route every migrated
mutation method through its narrow operation binding. Remove mutation
implementations, source-write authority, internal service-location, and exact
legacy allowances from `AnalysisBackend`.

A transport compatibility facade may remain temporarily, but it must only
delegate and must not constitute a second implementation. Architecture
verification must reject every internal `AnalysisBackend` mutation consumer.

Acceptance requires public compatibility tests for every migrated operation,
negative bytecode or dependency fixtures for internal consumption, and proof
that only the server compatibility boundary can still name aggregate mutation
methods.

### KIP-055 — Decompose reads and retire `AnalysisBackend`

Migrate each remaining symbol, relation, traversal, graph, diagnostic,
workspace, and evidence family through its own vertical contract, binding, and
adapter. Execute these as separate task contracts even though they share this
program KIP.

Graph read stays separate from graph build. Traversal remains bounded
composition over one-hop relations and receives no IntelliJ adapter. Cheap
reads cannot reach stronger work through a shared container. Delete
`AnalysisBackend` only after mechanical search and architecture checks prove
that no internal consumer remains.

Acceptance requires parity or stronger typed results for every migrated public
read, explicit completeness and generation behavior, no hidden refresh/import
or graph build on cheap paths, and compilation failure for aggregate backend
consumption.

### KIP-056 — Prove the enterprise acceptance journey

Run one multi-module, multi-source-set journey covering discovery, planning,
mutation, transition, verification, recovery, scale, and tamper behavior.
Fixtures must include overloads, implementations, non-trivial calls,
generated/read-only evidence, same-named unrelated declarations, and at least
one declared semantic boundary.

Inject movement, cancellation, crash, stale selector, stale plan, undeclared
write, publication failure, verification failure, and rollback failure. Record
comparable warm and cold traces with operation, corpus, build, machine, stage,
work, and byte identities. Deterministic plan and proof replay must hold on the
same admitted state.

Acceptance requires all positive, negative, fault, adversarial, scale, and
benchmark gates to pass together. Removing required evidence, exceeding a
bound, or moving the workspace must make completeness structurally
unrepresentable.

## Public contracts and architecture

Every remaining mutation introduces operation-specific intent, plan, prepared
attempt, result, receipt, and finite failure types. Successful transitions
preserve exact root, generation, selector, ownership, provenance, declared
write set, recovery, and verification evidence. Construction authority belongs
to the transition that proves each value.

IntelliJ `Project`, PSI, K2 sessions, search scopes, documents, virtual files,
and processor objects remain inside adapter calls. Only detached evidence may
cross request, approval, module, persistence, or process boundaries. Modeled
semantic writes and external filesystem writes remain mutually exclusive
capability lanes.

Temporary compatibility edges must be exact, legacy-to-target,
retirement-bound, projected, and mechanically invalid after their retirement
task completes. KIP-054 removes aggregate mutation authority; KIP-055 removes
the remaining aggregate read authority and the `AnalysisBackend` type itself.
No generic mutation engine, raw semantic apply path, or second operation
implementation is permitted.

## Verification protocol

For every KIP:

1. Create and read its closed `.agent/TASK.md` before investigation, the first
   source change, verification, and completion.
2. Establish a focused executable RED for the missing behavior.
3. Use real IntelliJ PSI/K2 fixtures for semantic claims. Strings, rendered
   signatures, exception text, serialized substrings, equal counts, and
   `toString()` output are not semantic proof.
4. Run the focused test, owning module `check`, direct consumers when contracts
   change, and `verifyKastArchitecture` with a regenerated projection.
5. Run `python3 .github/scripts/check-repository-shape.py --root .` and the
   owning Rust, installer, protocol, or publication checks when the boundary is
   crossed.
6. At a public release boundary, require exact-head CI, an immutable snapshot,
   checked assets and checksums, a fresh isolated installation, and the public
   journey rather than an internal helper.

Mutation verification must cover stable success, stale generation, ambiguous
identity, collision, cancellation, undeclared write, publication failure,
verification failure, and recovery disposition. Expected failures are closed
data and must retain the strongest authority proven at the failure point.

## Program completion

The program is complete only when KIP-056 passes and all of these statements
are mechanically true:

- Ordinary reads use scoped native IntelliJ APIs without hidden refresh,
  import, graph build, or source-write work.
- Exact selectors, coverage, continuations, plans, attempts, and receipts are
  detached, generation-bound, and proof-carrying.
- Only registered long operations outlive a request.
- Only workspace adapters refresh or import, only operation-specific semantic
  adapters mutate modeled source, and only the external lane mutates admitted
  unmodeled files.
- Add-declaration, rename, add-file, exact replacement, and optimize-imports
  return verified receipts or truthful terminal failures.
- Raw semantic apply is unavailable, the server depends on contracts and
  bindings only, and no internal implementation consumes `AnalysisBackend`.
- Partial, stale, truncated, ambiguous, unsupported, or unverified evidence
  cannot be represented as complete.

## Explicit exclusions

Completed KIPs are not cleanup opportunities. Do not reopen them unless a
failure directly blocks one remaining Done When condition. Cold-start
optimization, additional languages, more replacement kinds, arbitrary text or
regex editing, universal mutation abstractions, unrelated refactoring, and
general repository cleanup are outside this program.
