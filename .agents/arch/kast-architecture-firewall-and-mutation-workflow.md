# Implement the Kast architecture firewall and mutation workflow

This guide is the implementation contract for enforcing Kast's repository-wide platform topology
and for extracting mutation behavior from the four legacy Gradle projects. The platform module and
effect policy governs every flow. The runtime process and delivery graphs govern the mutation
workflow specifically. Follow the mutation delivery graph one bounded node at a time. Do not
reinterpret either scope while moving code.

The durable resources have two distinct responsibilities:

- Kotlin under `build-logic/src/main/kotlin/support/architecture/` is the sole executable authority
  for repository-wide module dependencies, compiled effects, lifecycle admission, and the exact
  migration baseline. It also represents the mutation-specific runtime and delivery graphs.
- This guide explains how contributors implement the mutation workflow without weakening the
  repository-wide architecture firewall.

`gradle/architecture/kast-architecture-policy.json` is a deterministic projection for review,
hooks, and CI. Generate it from Kotlin; never edit it as policy.

## Preserve these invariants

1. A semantic mutation is a transaction over one published workspace generation.
2. Planning retains stable values, never live IntelliJ objects.
3. Approval waits without a mutation lease, read action, write action, database transaction, PSI
   pointer, `Document`, `VirtualFile`, or `KaSession`.
4. Apply revalidates the approved plan against current identity, content, ownership, provenance,
   and writability before acquiring physical write authority.
5. Recovery evidence exists before the first source write.
6. Modeled Kotlin and Java source uses the IntelliJ semantic lane. Raw filesystem mutation cannot
   substitute for PSI or refactoring behavior.
7. The external lane accepts only a typed unmodeled-file target or explicit regeneration authority.
8. The IntelliJ command/write action contains only the modeled mutation. Search, index waiting,
   diagnostics, persistence, refresh, network work, and approval remain outside it.
9. Apply produces `AppliedUnverified`. Only verification against a newly published generation can
   produce `VerifiedMutationReceipt`.
10. Unknown, ambiguous, stale, incomplete, unsupported, or over-budget evidence fails closed.
11. Every validation is a type transition. Callers consume the stronger result and cannot discard
    or reconstruct its proof from primitives.
12. Expected failures are closed data. Do not use exceptions, nullable values, booleans, stage
    strings, transport success, or arbitrary text as lifecycle or failure protocols.

The type ratchet is:

```text
RawMutationRequest
  -> MutationIntent
  -> MutationPlan bound to source generation M0
  -> AdmittedMutation under WorkspaceMutationLease
  -> PreparedPsiMutation | PreparedExternalMutation
  -> ClosedAppliedMutation
  -> ResultingWorkspace M1
  -> VerifiedMutationReceipt
```

One owning transition constructs each stronger type. Raw extraction is permitted only at the
outer transport, IntelliJ, filesystem, SQLite, and Gradle adapters.

## Use the repository-wide policy as a firewall

Run the architecture gate before changing module membership:

```shell
./gradlew verifyKastArchitecture --configuration-cache
```

The root `kast.architecture` plugin observes every active project's direct production dependencies
and compiled main classes, regardless of which product flow uses them. `VerifyKastArchitectureTask`
admits those observations only after they have been parsed into canonical `ModuleId`,
`ProjectDependencyObservation`, and `EffectObservation` values. The gate rejects:

- an absent `ACTIVE` module;
- a materialized `PLANNED` module;
- a present `RETIRED` module;
- a direct project edge not listed for its consumer;
- a forbidden compiled JVM reference outside its owning adapter;
- a new violation absent from the exact migration baseline;
- a baseline entry whose exact violation has disappeared;
- a wildcard or pattern baseline entry;
- duplicate nodes, missing dependencies or owners, and graph cycles; and
- drift between Kotlin policy and the checked-in JSON projection.

Do not add an allowance to make a new change pass. The baseline is a frozen description of legacy
debt, keyed by exact module, effect, caller owner/name/descriptor, target owner/name/descriptor,
and retirement task. It can only shrink. When moving an allowed reference, delete its old
allowance in the same change. A moved or renamed reference is new debt unless it lands in the
permitted target adapter.

The scanner recognizes `INTELLIJ_WRITE`, `FILESYSTEM_WRITE`, `SOURCE_FILESYSTEM_WRITE`, `JDBC`,
`GRADLE_IMPORT`, and `ANALYSIS_BACKEND`. Adding a wrapper does not transfer authority: place the
wrapper in the module that owns the effect and expose a typed, effect-specific port.

After every policy edit, regenerate and inspect the projection:

```shell
./gradlew generateKastArchitectureProjection
git diff -- gradle/architecture/kast-architecture-policy.json
./gradlew verifyKastArchitecture --configuration-cache
```

## Materialize only the predetermined platform module graph

`settings.gradle.kts` is project-membership authority. A target project must be absent while its
policy lifecycle is `PLANNED`. Materialize it by adding the project to settings and changing that
same `ModulePolicy` to `ACTIVE` in one change. Never create an ungoverned intermediate project.

The arrow notation below means “consumer may depend directly on dependency.” Transitive access
does not authorize a new direct edge.

| Module | Role | Allowed direct project dependencies | Allowed effects |
| --- | --- | --- | --- |
| `:analysis-api` | legacy host | none | backend, ordinary filesystem output |
| `:index-store` | legacy host | `:analysis-api` | JDBC, ordinary filesystem output |
| `:analysis-server` | legacy host | `:analysis-api`, `:index-store` | backend, ordinary filesystem output |
| `:indexer` | legacy host | `:analysis-api`, `:analysis-server`, `:index-store` | ordinary filesystem output |
| `:kernel` | kernel | none | none |
| `:protocol:registry` | contract | `:kernel` | none |
| `:workspace:contract` | contract | `:kernel` | none |
| `:evidence:sqlite` | SQLite adapter | `:workspace:contract` | JDBC |
| `:workspace:service` | service | `:workspace:contract`, `:evidence:sqlite` | none |
| `:workspace:intellij` | workspace adapter | `:workspace:contract`, `:workspace:service` | Gradle import |
| `:change:contract` | contract | `:kernel`, `:protocol:registry` | none |
| `:change:plan:spi` | SPI | `:change:contract`, `:workspace:contract` | none |
| `:change:plan:intellij` | IntelliJ read adapter | `:change:contract`, `:change:plan:spi`, `:workspace:contract` | none |
| `:change:journal:contract` | contract | `:change:contract` | none |
| `:change:journal:sqlite` | SQLite adapter | `:change:journal:contract` | JDBC |
| `:change:plan:service` | service | `:change:contract`, `:change:plan:spi`, `:change:journal:contract`, `:workspace:contract` | none |
| `:workspace:mutation:contract` | contract | `:change:contract`, `:workspace:contract` | none |
| `:workspace:mutation:service` | service | `:workspace:contract`, `:workspace:service`, `:workspace:mutation:contract` | none |
| `:change:apply:spi` | SPI | `:change:contract`, `:workspace:mutation:contract` | none |
| `:change:recovery:contract` | contract | `:change:contract` | none |
| `:change:recovery:filesystem` | filesystem write adapter | `:change:recovery:contract` | filesystem and source-filesystem write |
| `:change:recovery:service` | service | `:change:recovery:contract`, `:change:recovery:filesystem`, `:change:journal:contract`, `:workspace:contract` | none |
| `:change:apply:service` | service | `:change:contract`, `:change:plan:spi`, `:change:apply:spi`, `:change:recovery:contract`, `:change:journal:contract`, `:workspace:mutation:contract` | none |
| `:change:apply:intellij` | IntelliJ write adapter | `:change:contract`, `:change:apply:spi`, `:workspace:contract` | IntelliJ write |
| `:change:apply:filesystem` | filesystem write adapter | `:change:contract`, `:change:apply:spi` | filesystem and source-filesystem write |
| `:change:verify:spi` | SPI | `:change:contract`, `:workspace:contract` | none |
| `:change:verify:intellij` | IntelliJ read adapter | `:change:contract`, `:change:verify:spi`, `:workspace:contract` | none |
| `:change:verify:service` | service | `:change:contract`, `:change:verify:spi`, `:change:recovery:contract`, `:change:journal:contract` | none |
| `:runtime:bindings:contract` | contract | `:change:contract` | none |
| `:runtime:server` | transport | `:protocol:registry`, `:change:contract`, `:runtime:bindings:contract` | compatibility backend |
| `:runtime:composition` | composition | every target module above, but no legacy host | compatibility backend |

Do not add lateral convenience edges. In particular:

- planning modules cannot depend on apply modules or IntelliJ write APIs;
- the IntelliJ apply adapter cannot reach the filesystem apply adapter;
- the filesystem adapter cannot reach PSI or Kotlin Analysis APIs;
- verification cannot depend on apply implementations or repair by reapplying;
- transport cannot reach IntelliJ, Gradle, JDBC, source writers, or the complete runtime graph;
- only `:workspace:intellij` may import Gradle projects;
- only `:change:apply:intellij` may mutate modeled PSI;
- only filesystem apply and recovery adapters may mutate source through raw file APIs;
- only the two SQLite adapters may use JDBC; and
- only composition may see the complete implementation graph.

Foundation task F02 must provide module-role convention plugins before general extraction begins.
Each plugin is an orthogonal role marker and build convention, not a second dependency-policy
authority. At minimum distinguish kernel, contract, SPI, service, IntelliJ-read,
IntelliJ-write, filesystem-write, SQLite, workspace, transport, and composition roles. Keep the
allowed graph and effect sets in `KastPlatformModules`; a convention plugin may configure or expose
the declared role but must not carry a divergent allowlist.

## Implement modules according to their role

### Contracts and kernel

Use contracts for identities, invariant-carrying values, closed lifecycle states, commands,
receipts, failures, and narrow ports. They have no IntelliJ, Gradle, JDBC, filesystem, service
locator, or implementation dependencies. Constructors that could manufacture unproven state stay
private or internal to the transition owner.

The mutation contract must distinguish semantic and external plans. A plan includes its source
manifest, non-empty expected-file set, declared write set, obligations, expected semantic delta,
and verification contract. Each expected file carries a workspace-relative path, before-image
identity, Gradle owner, source-root provenance, and writability requirement.

### Services

Services are pure coordinators over typed ports. They select legal transitions but cannot import
physical APIs. A service receives proof-carrying capabilities, calls one narrower owner, and
returns a stronger state or a closed failure. It cannot unpack a capability into raw paths or
handles for downstream revalidation.

### IntelliJ read adapters

Planning and verification adapters run cancellable smart read actions. Resolve PSI, Kotlin
Analysis, indexes, scopes, and live file identities locally, then detach the result into stable
contract values before returning. Never retain or serialize `Project`, `PsiElement`, `KaSession`,
`VirtualFile`, `Document`, or a search scope.

### IntelliJ write adapter

Prepare the ephemeral apply context before the write command. Re-resolve exact targets and prove
writability. On EDT, perform only the supported PSI/refactoring mutation, formatting or reference
shortening that belongs to that operation, and capture affected document identities. Save only the
affected documents after the command. Do not search, wait for smart mode, refresh, diagnose,
persist journal state, call an agent, or perform network work inside the command.

### Filesystem write and recovery adapters

Accept only typed, descriptor-relative targets with proven containment and operation authority.
Use atomic replacement, fsync, exact file identities, and before/after hashes as required by the
operation. Reject modeled source and unproven generated targets. Recovery storage and restoration
are physical mechanisms; rollback policy remains in the recovery service.

### SQLite adapters

Implement compare-and-set lifecycle persistence and evidence publication from typed records.
Transactions make decisions durable but do not decide semantic truth. Do not expose SQL,
connections, tables, or database handles to contracts or services.

### Transport and composition

Transport parses envelopes, dispatches declared operations through `KastOperationBindings`, and
projects canonical results. Transport success cannot mean semantic success. Composition is the
only owner of the full object graph; it returns narrow bindings and never exports a backend
aggregate or service locator.

## Implement the mutation state machine

Represent runtime state as a product, not as one readiness flag:

```text
SystemState = [WorkspaceState, PlanState, MutationState, RecoveryState]
```

| Axis | Closed states |
| --- | --- |
| Workspace | `W0 READY(M0)`, `W1 DIRTY(base=M0)`, `W2 RECONCILING`, `W3 READY(M1)`, `WB BLOCKED` |
| Plan | `P0 NONE`, `P1 PLANNING`, `P2 PLANNED(planId, source=M0)`, `PX EXPIRED_OR_REJECTED` |
| Mutation | `M0 IDLE`, `M1 LEASED`, `M2 APPLIED_UNVERIFIED`, `M3 VERIFYING`, `M4 VERIFIED`, `MR ROLLED_BACK`, `MX RECOVERY_REQUIRED` |
| Recovery | `R0 NONE`, `R1 PREPARED`, `R2 RETAINED`, `R3 RELEASED` |

Encode legal transitions as state-specific functions or capabilities. Persist the expected prior
state with every journal transition so a stale actor receives a typed conflict. `WB`, `PX`, and
`MX` are terminal claims about failed proof; no fallback may relabel them as success.

Implement the mutation runtime processes in this order. The state column shows the required
transition; the owner is fixed by the platform module graph.

| Process | Required transition | Implementation constraint |
| --- | --- | --- |
| RP01 Parse intent | `[W0,P0,M0,R0] -> [W0,P1,M0,R0]` | Parse all raw paths and strings; expose no physical authority. |
| RP02 Planning lease | state unchanged | Bind exact root, worktree, compiler environment, scope, and M0. |
| RP03 Resolve semantic scope | state unchanged | Use bounded smart reads; return detached evidence. |
| RP04 Capture preconditions | state unchanged | Bind hashes, identities, owner, provenance, writability, and declared scope. |
| RP05 Persist plan | `P1 -> P2` | Persist stable, tamper-evident values safe for an approval wait. |
| RP06 Await approval | state unchanged | Retain no live or physical resource. |
| RP07 Logical mutation lease | `M0 -> M1` | Serialize Kast writers only; do not hold an IntelliJ lock. |
| RP08 Revalidate | remain `M1` or `P2 -> PX, M1 -> M0` | Resolve every selector and authority predicate again. |
| RP09 Prepare recovery | `R0 -> R1` | Durably capture exact before-images before source write. |
| RP10 Prepare apply context | state unchanged | Create only brief, process-local PSI or external capabilities. |
| RP11S Semantic apply | `W0 -> W1, M1 -> M2` | Run only modeled PSI mutation in the short write command. |
| RP11E External apply | `W0 -> W1, M1 -> M2` | Reject modeled source; write only the admitted external target. |
| RP12 Persist/capture after-images | remain `W1,M2,R1` | Save affected documents or verify external atomic commit. |
| RP13 Prove write-set closure | remain `M2` or `M2 -> MX, R1 -> R2` | Any undeclared change blocks success and retains recovery. |
| RP14 Targeted transition | `W1 -> W2` | Use targeted VFS refresh; never ordinary whole-workspace refresh. |
| RP15 Publish generation | `W2 -> W3, M2 -> M3` or `W2 -> WB, M2 -> MX, R1 -> R2` | Atomically publish an M1 distinct from M0 and bound to exact source/compiler state. |
| RP16 Evaluate postconditions | remain `W3,M3,R1` | Run bounded diagnostics and semantic checks in background smart reads. |
| RP17 Reconcile result | state unchanged | Require operation proof; compilation or tests alone are insufficient. |
| RP18 Issue receipt | `M3 -> M4, R1 -> R3` | Only this step emits semantic success and releases the logical lease durably. |
| RP19 Recover | after `R1`, produce rollback `MR,R3` or failure `MX,R2` | Roll back safely by default; an unsafe or failed rollback stays explicit. |

The semantic and external apply processes are alternative RP11 lanes. RP12 must join only the lane
selected by the admitted plan; it must not require both lanes to execute.

## Follow the mutation delivery DAG

Choose a delivery node only after every listed predecessor has mechanical completion evidence.
Independent nodes with satisfied predecessors may run in parallel in isolated worktrees. They may
not edit the same source owner, policy row, or baseline entries without an explicit integration
owner.

| Task | Outcome | Required predecessors |
| --- | --- | --- |
| F01 | Freeze mutation lifecycle and canonical contracts | none |
| F02 | Create module-role convention plugins | F01 |
| F03 | Enforce dependency graph and forbidden effects | F02 |
| F04 | Reduce `AnalysisBackend` to compatibility transport | F01, F03 |
| P01 | Classify semantic and external operations | F01 |
| P02 | Define stable plan and expected-file proof | P01 |
| P03 | Create durable plan journal | F03, P02 |
| P04 | Extract IntelliJ semantic planning adapter | F03, P02 |
| P05 | Assemble deterministic plans | P03, P04 |
| A01 | Establish logical workspace mutation lease | F03, P05 |
| A02 | Revalidate selector, hashes, ownership, and provenance | A01, P04 |
| A03 | Prepare writable-target and recovery capabilities | A02 |
| A04 | Validate supported IntelliJ refactoring APIs | A03 |
| A05 | Implement short IntelliJ write-command executor | A04 |
| A06 | Implement typed external-file writer | A03 |
| A07 | Persist affected documents and capture after-images | A05, A06 |
| A08 | Prove declared write-set closure | A07 |
| V01 | Route targeted post-write workspace transition | A08 |
| V02 | Publish one resulting semantic generation | V01 |
| V03 | Evaluate diagnostics and operation postconditions | V02 |
| V04 | Reconcile expected and observed semantic delta | V03 |
| V05 | Issue terminal verified receipt | V04 |
| R01 | Implement automatic rollback policy | A07, V04 |
| R02 | Reconcile and prove rollback generation | R01, V01, V02 |
| R03 | Resume or recover after crash | A03, P03, R01 |
| M01 | Route rename through plan/apply/verify | R03, V05 |
| M02 | Route replace, add, implementation, body, and import operations | M01 |
| M03 | Remove semantic access to generic raw apply-edits | F03, M02 |
| T01 | Contract and state-machine suite | F01, P02, R03, V05 |
| T02 | IntelliJ write-protocol integration suite | A05, A07, V03 |
| T03 | Concurrency, movement, and recovery fault suite | R03, V02 |
| T04 | Performance and UI-safety suite | T02, T03 |
| T05 | Enterprise multi-module mutation demonstration | M03, T01, T02, T03, T04 |

Representation in `KastMutationDelivery` does not prove a task complete. Record completion only
when its types, module boundaries, behavior, negative proofs, and consumer integration exist.

## Execute one extraction work packet

1. Select the smallest delivery node whose predecessors are proven. Name the source owner, target
   module, policy rows, baseline entries, and direct consumers before editing.
2. Start from a green `verifyKastArchitecture` result and record the exact baseline count relevant
   to the owner being moved.
3. Add focused red tests for the invariant or transition. Include a negative test demonstrating
   that the old primitive, effect, edge, or invalid state is rejected.
4. Introduce the target contract and stronger output type first. Keep raw input at the boundary and
   make every caller consume the proof-bearing result.
5. Materialize the target module only when it can compile under its final direct dependency and
   role constraints. Update settings and `PLANNED -> ACTIVE` together.
6. Move the implementation behind its narrow port. Do not leave a second implementation or
   compatibility path unless its removal belongs to a later named migration node.
7. Migrate callers inward from contracts to services to adapters. The composition root is the only
   place allowed to bind implementations.
8. Delete every exact legacy allowance retired by the move. Do not rewrite or broaden an allowance
   to follow relocated debt.
9. Generate the projection and run the architecture gate. Treat an obsolete allowance as evidence
   of progress that must be removed, not as a reason to preserve stale baseline data.
10. Run focused tests, the owning module check, direct consumers, and the repository widening proof
    required by the nearest `AGENTS.md`.
11. Commit only when the old owner no longer possesses the moved authority and the target module
    cannot compile with an undeclared dependency or effect.

If one work packet requires an unlisted edge, new effect owner, graph cycle, raw escape hatch, or
baseline expansion, stop. That is an architecture change requiring an explicit policy decision,
not an extraction implementation detail.

## Prove each boundary mechanically

Maintain positive and negative proof for all of these surfaces:

- policy validation: duplicates, missing references, owner references, cycles, and non-exact
  allowances return closed `ArchitecturePolicyFailure` values;
- lifecycle admission: active, planned, and retired membership is enforced;
- project graph: an undeclared direct edge fails its owning Gradle fixture;
- bytecode effects: fixtures fail for IntelliJ write, source-filesystem write, JDBC, Gradle import,
  and backend authority outside their permitted modules;
- baseline subtraction: exact observations pass, disappearance is obsolete, additions are
  unbaselined, and pattern entries are invalid policy;
- projection: two renders are byte-for-byte identical and parse as JSON;
- Gradle integration: the root task reports structured findings and reuses configuration cache;
- mutation contracts: illegal lifecycle transitions and stale compare-and-set writes cannot be
  constructed or persisted;
- IntelliJ integration: planning/verification retain no live objects and the write command performs
  no blocking or unrelated work;
- fault behavior: external edits, movement, stale hashes, crashes, undeclared writes, publication
  failure, verification failure, and rollback failure preserve truthful terminal state; and
- scale/performance: bounded scopes and budgets hold on multi-module repositories without EDT
  waits, repository-wide refresh, or unbounded relation search.

Use this minimum architecture proof after any extraction:

```shell
./gradlew -p build-logic test --tests 'support.architecture.*'
./gradlew generateKastArchitectureProjection
./gradlew verifyKastArchitecture --configuration-cache
./gradlew verifyKastArchitecture --configuration-cache
git diff --check
python3 .github/scripts/check-repository-shape.py --root .
```

The second verifier run must reuse configuration cache. Widen to the owning module, direct
consumers, `./gradlew test`, or `./gradlew build` according to the root and nearest module guides.

## Completion conditions

The repository-wide architecture firewall is installed when every active project is checked against
the predetermined platform topology, compiled-effect ownership, lifecycle rules, exact baseline,
and policy projection. That enforcement applies to every flow now and to every target module as it
is materialized.

The mutation workflow is fully implemented only when all target modules are active, legacy hosts
no longer own mutation authority, the exact baseline is empty, raw semantic apply-edits are
unavailable to semantic callers, every mutation reaches a verified, rolled-back, rejected, or
recovery-required terminal state, and T05 proves the full protocol on an enterprise multi-module
workspace. A green firewall with retained legacy allowances proves only that migration has not
regressed; it does not prove the mutation extraction complete.
