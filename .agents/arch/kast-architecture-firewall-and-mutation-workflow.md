# IntelliJ substrate architecture and delivery program

This file is the working reference for the dependency-ordered program. It
defines selectable nodes, dependencies, milestone exits, and invariant
boundaries. Root `AGENTS.md` owns the work-packet procedure; this reference does
not duplicate pseudo-ticket bodies or execution transcripts.

## Authorities

- Current repository HEAD is implementation authority. The source planning
  baseline is `amichne/kast@60ca538fd00d6c75c4c40140ec719bc531c9651e`.
- `build-logic/src/main/kotlin/support/architecture/` and generated
  `gradle/architecture/kast-architecture-policy.json` are executable authority
  for module, role, effect, cost, and migration policy. This Markdown graph is
  not a substitute for that policy.
- `hechtcarmel/jetbrains-index-mcp-plugin@7670d7202f43ab6d54433832d087316b69637f1b`
  is behavior evidence for native IntelliJ discovery, search scopes,
  cancellable smart reads, pagination, and liveness. It is not Kast's
  architecture authority.
- `amichne/slopsentral@672f169842174ee807cd28a12c8f4718e7272dff`
  is the design reference for proof-carrying transitions and boundary parsing.

If HEAD has moved, resolve only the selected node's renamed owners and current
consumers. Do not broaden its outcome to match an obsolete baseline.

## Program outcome

The first vertical journey is:

```text
native symbol discovery -> exact selector -> exact definition/description
```

The terminal journey is KIP-056: native bounded reads, explicit workspace and
long-operation execution, verified semantic and external mutation slices,
aggregate-backend retirement, and enterprise multi-module acceptance. Each
operation becomes usable when its own vertical slice is proven; the program is
not a horizontal platform rewrite.

## Milestones

| ID | Outcome | Exit condition |
| --- | --- | --- |
| M0 | Correct foundation | Policy expresses the intended architecture and shrink-only migration without a big-bang move. |
| M1 | Fast read path | One public read uses a narrow native IntelliJ capability and returns exact, bounded, generation-bound output. |
| M2 | Workspace execution | Transitions, long work, and resource admission are explicit and independent from ordinary reads. |
| M3 | Plan-only mutation | Add-declaration produces a detached durable plan with no source authority. |
| M4 | First verified mutation | Add-declaration ends Verified, Rejected, RolledBack, or RecoveryRequired truthfully. |
| M5 | Verified rename | Native multi-file rename uses the same plan, recovery, transition, and proof protocol. |
| M6 | Expansion and retirement | Operations migrate one at a time; raw semantic apply and the aggregate backend retire after their consumers move. |

## Task graph

The wave is topological depth, not a batch barrier. A node is ready as soon as
all of its own dependencies have mechanical proof.

| ID | Milestone | Wave | Depends on | Observable outcome |
| --- | --- | ---: | --- | --- |
| KIP-001 | M0 | 0 | - | Freeze the [source and reproducible performance ledger](kast-intellij-substrate-ledger.json) and its executable validator. |
| KIP-002 | M0 | 1 | KIP-001 | Correct alternative mutation branches, joins, and recovery interrupts. |
| KIP-003 | M0 | 1 | KIP-001 | Correct module dependency direction around contracts, ports, adapters, and persistence. |
| KIP-004 | M0 | 2 | KIP-003 | Introduce exact, shrink-only, retirement-bound migration edges. |
| KIP-005 | M0 | 2 | KIP-002, KIP-003 | Enforce module role, effect, cost, and exported-API boundaries. |
| KIP-010 | M1 | 2 | KIP-003 | Define typed operation lanes, capabilities, budgets, and closed outcomes. |
| KIP-011 | M1 | 3 | KIP-005, KIP-010 | Extract a canonical-root, generation-bound semantic read lease. |
| KIP-012 | M1 | 4 | KIP-011 | Compile typed IntelliJ search scopes before query execution. |
| KIP-013 | M1 | 5 | KIP-012 | Implement bounded native file, class, and symbol discovery. |
| KIP-014 | M1 | 6 | KIP-013 | Resolve detached discovery candidates into exact generation-bound selectors. |
| KIP-015 | M1 | 7 | KIP-011, KIP-012, KIP-014 | Implement bounded native relation reads with qualified coverage. |
| KIP-016 | M1 | 4 | KIP-010, KIP-011 | Implement detached generation-bound continuations. |
| KIP-017 | M1 | 4 | KIP-011 | Add fail-fast liveness and freshness admission. |
| KIP-018 | M1 | 7 | KIP-001, KIP-013, KIP-014, KIP-016, KIP-017 | Cut over and benchmark the first public fast read. |
| KIP-020 | M2 | 3 | KIP-003, KIP-004, KIP-005 | Extract the workspace transition port. |
| KIP-021 | M2 | 5 | KIP-010, KIP-017 | Add the registered long-operation protocol. |
| KIP-022 | M2 | 6 | KIP-001, KIP-020, KIP-021 | Add resource admission and staggered expensive work. |
| KIP-030 | M3 | 5 | KIP-001, KIP-011, KIP-012 | Characterize the add-declaration IntelliJ protocol. |
| KIP-031 | M3 | 6 | KIP-010, KIP-011, KIP-012, KIP-020, KIP-030 | Define and route plan-only add-declaration. |
| KIP-032 | M3 | 7 | KIP-004, KIP-031 | Persist the plan and establish the approval boundary. |
| KIP-033 | M4 | 8 | KIP-020, KIP-032 | Revalidate the approved plan and prepare recovery. |
| KIP-034 | M4 | 9 | KIP-005, KIP-030, KIP-033 | Apply one short IntelliJ mutation and prove write-set closure. |
| KIP-035 | M4 | 10 | KIP-015, KIP-020, KIP-034 | Publish the resulting generation and verify add-declaration. |
| KIP-036 | M4 | 11 | KIP-005, KIP-035 | Cut over public add-declaration and close bypasses. |
| KIP-040 | M5 | 5 | KIP-001, KIP-011, KIP-012 | Characterize the native rename protocol. |
| KIP-041 | M5 | 9 | KIP-015, KIP-033, KIP-040 | Extract a detached rename plan and native apply adapter. |
| KIP-042 | M5 | 11 | KIP-016, KIP-035, KIP-041 | Route verified rename and prove multi-file behavior. |
| KIP-050 | M6 | 12 | KIP-036 | Route verified add-file as its own slice. |
| KIP-051 | M6 | 12 | KIP-015, KIP-036 | Route exact replacement as its own slice. |
| KIP-052 | M6 | 12 | KIP-036 | Route optimize-imports as its own slice. |
| KIP-053 | M6 | 9 | KIP-002, KIP-020, KIP-033 | Implement the typed external-file lane. |
| KIP-054 | M6 | 13 | KIP-036, KIP-042, KIP-050, KIP-051, KIP-052, KIP-053 | Reduce mutation methods on `AnalysisBackend` to compatibility bindings. |
| KIP-055 | M6 | 14 | KIP-018, KIP-054 | Decompose remaining read capabilities and retire `AnalysisBackend`. |
| KIP-056 | M6 | 15 | KIP-022, KIP-042, KIP-050, KIP-051, KIP-052, KIP-053, KIP-055 | Prove the enterprise multi-module acceptance journey. |

## Topological waves

| Wave | Nodes |
| ---: | --- |
| 0 | KIP-001 |
| 1 | KIP-002, KIP-003 |
| 2 | KIP-004, KIP-005, KIP-010 |
| 3 | KIP-011, KIP-020 |
| 4 | KIP-012, KIP-016, KIP-017 |
| 5 | KIP-013, KIP-021, KIP-030, KIP-040 |
| 6 | KIP-014, KIP-022, KIP-031 |
| 7 | KIP-015, KIP-018, KIP-032 |
| 8 | KIP-033 |
| 9 | KIP-034, KIP-041, KIP-053 |
| 10 | KIP-035 |
| 11 | KIP-036, KIP-042 |
| 12 | KIP-050, KIP-051, KIP-052 |
| 13 | KIP-054 |
| 14 | KIP-055 |
| 15 | KIP-056 |

## Legacy node normalization

The S identifiers are no longer selectable nodes. They map into this graph as
follows; code and tests remain the durable evidence. A mapping is not a ticket
completion flag, and KIP-001 must bind the source and performance ledger before
mapped dependents are treated as complete under this adopted graph.

| Legacy unit | Program node | Disposition |
| --- | --- | --- |
| S00 | KIP-002 | Branch, join, and recovery correction. |
| S01 | KIP-003 | Dependency-center and planned-read boundaries. |
| S02 | KIP-004 | Retirement-bound migration admission. |
| S03 | KIP-005 | Role, effect, and cost enforcement. |
| S04 | - | Supporting kernel materialization; no parallel graph node. |
| S05 | KIP-010 | Typed operation registry and lanes. |
| S06 | KIP-011 | Generation-bound semantic read lease. |
| S07 | KIP-012 | Typed IntelliJ search-scope compilation. |
| S08 | KIP-013 | Use the KIP identifier for future discovery work. |
| S09 | KIP-014 | Use the KIP identifier for future selector work. |
| S10 | KIP-016 | Use the KIP identifier for future continuation work. |
| S11 | KIP-017 | Use the KIP identifier for future liveness work. |
| S12 | KIP-018 | Use the KIP identifier for future public cutover work. |

KIP-015 and KIP-020 through KIP-056 are explicit additions from the end-to-end
program. They replace the former undifferentiated deferred backlog.

## Substrate boundaries

- IntelliJ owns live indexes, PSI, smart reads, search scopes, refactoring, and
  write commands. Kast owns exact selectors, published generations, detached
  evidence, continuations, plans, recovery, and verified receipts.
- Ordinary reads never import Gradle, build a graph, write SQLite, mutate
  source, refresh the whole workspace, control processes, or reacquire
  aggregate `AnalysisBackend` authority.
- File discovery uses `ChooseByNameContributor.FILE_EP_NAME`; class discovery
  uses `CLASS_EP_NAME`; symbol discovery uses `GotoSymbolModel2` and the current
  supported Choose-by-Name provider stack.
- Project-model ownership becomes a typed `GlobalSearchScope` before PSI or
  index work. Result limits apply before expensive PSI conversion.
- Reads are cancellable suspending smart read actions that yield to writes.
  Runtime, EDT, index, workspace, generation, cancellation, and provider
  failures are closed outcomes, never implicit refresh or fallback triggers.
- Candidates, selectors, descriptions, relation facts, coverage, and
  continuation state are bounded and detached. No `Project`, `PsiElement`,
  `KaSession`, `VirtualFile`, `Document`, search scope, pointer, or closure may
  survive a request boundary.
- Exact counts require terminal enumeration. A cap, cancellation, stale cursor,
  dumb-mode transition, unsupported element, or provider failure produces a
  qualified minimum or rejection, never fabricated completeness.
- A continuation binds root, generation, normalized request, scope, ordering,
  resume position, TTL, and resource limits.

## Mutation and recovery boundaries

- Planning retains stable evidence and no source authority. Approval waits hold
  no mutation lease, read/write action, database transaction, live IDE object,
  document, or filesystem capability.
- Semantic and external apply are alternative lanes. Services consume contracts
  and SPIs; adapters implement SPIs; persistence implements evidence contracts;
  the registry imports feature contracts.
- Apply revalidates selector, content, ownership, provenance, and writability,
  then prepares durable recovery before the first source write.
- The IntelliJ command contains only the admitted modeled mutation and its
  local formatting or reference shortening. Search, waiting, diagnostics,
  persistence, refresh, approval, and network work remain outside it.
- External mutation accepts only typed unmodeled-file or regeneration authority
  and cannot substitute for modeled PSI/refactoring behavior.
- Apply produces `AppliedUnverified`. Only targeted transition, publication of
  a distinct generation, obligation evaluation, and reconciliation can produce
  a verified receipt. Every failure remains truthfully Rejected, RolledBack, or
  RecoveryRequired.
- Migration edges are exact, temporary, target-directed, retirement-bound, and
  shrink-only. Mutation compatibility can retire before the aggregate backend;
  complete backend retirement waits for all read and evidence families.

## Selection and completion contract

- Select exactly one node whose direct dependencies have landed with mechanical
  proof. Reconcile legacy-mapped implementation against the adopted KIP
  contract before treating it as a proven predecessor.
- Derive one ignored `.agent/TASK.md` packet with exact writes, non-goals, one
  focused RED, the smallest GREEN, and objective completion conditions.
- Completion requires the observable outcome, positive and negative proof,
  stronger representations for established facts, no new bypass or fallback,
  architecture projection and exact-baseline coherence, direct-consumer proof,
  repository shape, and changed-path hygiene.
- Independent ready nodes may use isolated worktrees, but each node lands
  independently. Parallel nodes cannot share a source owner, policy row, or
  baseline entry without one named integration owner.
- An unlisted edge, effect owner, graph cycle, raw escape hatch, wildcard
  migration permission, or baseline expansion is a new policy decision, not an
  implementation detail.

## Program completion

KIP-056 is complete only when enterprise-shaped multi-module fixtures prove
bounded native reads, resource and long-operation admission, verified rename,
verified add-file, exact replacement, optimize-imports, typed external writes,
truthful recovery terminals, and complete `AnalysisBackend` retirement. A green
architecture firewall with retained legacy allowances or compatibility owners
is partial migration, not terminal success.
