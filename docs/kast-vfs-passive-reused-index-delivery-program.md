# Kast best-case VFS-passive reused-index delivery program

**Status:** Normative program definition. No task is complete until its exact-head receipts are admitted.
**Tooling authority:** `amichne/kast@78262728313c90bb847e73425dc1a76d704397db`
**Delivery authority digest:** `de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c`
**Program fingerprint:** `da8a350e09f24df15640dd26e3f3b4af0ede56dfd46275113aacb76a909f965f`

## Terminal outcome

The terminal type is `BestCaseVfsPassiveReusedIndex`. It proves that the installed `kast` command reached `workspace.inspect -> symbol.discover -> symbol.resolve -> symbol.describe` through the already-running IDE process and existing Project, with zero second Project opens, Gradle imports, VFS refreshes, Kast-caused indexing cycles, runtime archive reads, or `kast-indexer` processes.

The terminal type has no public constructor. `KVP-043` derives it only from the complete receipt closure.

## Corrections to prior plans

- The originally pinned delivery-authority bytes (`55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e`) are unavailable. The active authority is the exact persisted user goal from the Kast conversation, including its terminal newline; its digest is `de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c`. These identities are intentionally distinct and the authority contradiction set records the replacement.
- The old clean-slate plan and graph require exactly two runtime processes and packaged-indexer acceptance. This program supersedes that runtime assumption for the MVP.
- The prior IntelliJ substrate program correctly identifies IntelliJ as the live index/PSI/VFS authority, but its machine projection is explicitly non-executable. This program makes the graph executable through Kotlin and Gradle.
- The current repository already has a Kotlin architecture policy, generated JSON projection, and Gradle architecture gates. This program extends that authority instead of creating a parallel status system.

## Best-case execution path

```text
Raw CLI args
-> parsed command
-> canonical root
-> parsed endpoint descriptor
-> compatible exact-root IDE endpoint
-> single-flight Project read permit
-> read epoch before
-> cancellable smart read against existing VFS/index/PSI
-> detached result
-> read epoch after
-> equal-epoch revalidation
-> Complete | Qualified | Rejected
```

An IDE-observed movement advances the epoch. It invalidates the in-flight read; it does not trigger refresh or repair.

## Module boundary

The terminal read product adds `:workspace:intellij-read`, `:runtime:ide-read`, `:ide-plugin`, `:distribution:release`, and `:acceptance:ide-hosted`. The plugin cannot depend on `:runtime:composition`, `:change:*`, `:topology:*`, `:evidence:sqlite`, `:indexer`, or `:distribution:managed`. No module in the terminal default read graph owns `PROCESS_START`, `GRADLE_IMPORT`, `VFS_REFRESH`, `SOURCE_WRITE`, `JDBC`, `TOPOLOGY_BUILD`, `NETWORK_READ`, or `RUNTIME_ARCHIVE_READ`.

## Mechanically derived waves

| Wave | Tasks |
|---:|---|
| 0 | `KVP-001` |
| 1 | `KVP-002` |
| 2 | `KVP-003` |
| 3 | `KVP-004` |
| 4 | `KVP-005` |
| 5 | `KVP-006` |
| 6 | `KVP-007`, `KVP-009` |
| 7 | `KVP-008`, `KVP-010` |
| 8 | `KVP-012` |
| 9 | `KVP-013`, `KVP-014` |
| 10 | `KVP-015` |
| 11 | `KVP-016`, `KVP-017` |
| 12 | `KVP-018` |
| 13 | `KVP-019` |
| 14 | `KVP-020` |
| 15 | `KVP-021` |
| 16 | `KVP-022` |
| 17 | `KVP-023` |
| 18 | `KVP-024` |
| 19 | `KVP-025`, `KVP-026` |
| 20 | `KVP-027`, `KVP-028` |
| 21 | `KVP-029` |
| 22 | `KVP-030` |
| 23 | `KVP-031` |
| 24 | `KVP-011` |
| 25 | `KVP-032` |
| 26 | `KVP-033` |
| 27 | `KVP-034` |
| 28 | `KVP-035` |
| 29 | `KVP-036` |
| 30 | `KVP-037` |
| 31 | `KVP-038` |
| 32 | `KVP-039` |
| 33 | `KVP-040` |
| 34 | `KVP-041` |
| 35 | `KVP-042` |
| 36 | `KVP-043` |

## Atomic task graph

### KVP-001: Freeze program authorities and contradictions

**Goal.** Bind the exact current head, requirement fingerprint, source digests, obsolete assumptions, and unproven claims before implementation work begins.

**Dependencies.** None. Computed wave: `0`.

**Allowed reads.** `settings.gradle.kts`, `build.gradle.kts`, `build-logic`, `indexer`, `cli`, `runtime`, `workspace`, `symbol`, `gradle/delivery/authority-sources/persisted-goal.txt`, `gradle/delivery/authority-sources/superseded-clean-slate-task-graph.json`, `gradle/delivery/authority-sources/superseded-clean-slate-plan.md`, `gradle/delivery/authority-sources/intellij-substrate-program.html`.

**Allowed writes.** `build/reports/delivery/KVP-001-authority-ledger.json`, `build/reports/delivery/KVP-001-contradictions.md`, `build/reports/delivery/KVP-001-authority.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `requirement:KVP-REQ-001`, `requirement:KVP-REQ-002`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.001.proof` at `build/reports/delivery/KVP-001-authority.json`.

**Public interface.** `ProgramAuthority`.

**Internal implementation.** Typed authority ledger and contradiction set generated from exact inputs.

**Effect and cost.** `METADATA_READ`, `BUILD_POLICY_WRITE`; `METADATA`, `BUILD_POLICY`.

**Forbidden work.** Production behavior changes; Manual progress fields; Silently retaining the old two-process authority.

**RED.** `./gradlew verifyKastVfsPassiveAuthorityNegative`. Expected failure: Rejects a stale head, changed requirement digest, omitted contradiction, or obsolete two-process assumption.

**GREEN.** `./gradlew verifyKastVfsPassiveAuthority`. Expected proof: Emits one authority ledger bound to current head and all source digests.

**Review boundary.** Only authority and contradiction artifacts may change.

**Completion receipt.** `KVP-001-COMPLETE` at `build/reports/delivery/receipts/KVP-001-COMPLETE.receipt.json`. It consumes `KVP-001-RED`, `KVP-001-GREEN`, and all predecessor completion receipts.

### KVP-002: Define proof-preserving delivery types

**Goal.** Define distinct types for identity, generation, dependency, authority, effect, cost, evidence, gate, receipt, progression, and closed outcomes.

**Dependencies.** `KVP-001`. Computed wave: `1`.

**Allowed reads.** `build/reports/delivery/KVP-001-authority-ledger.json`, `build-logic/src/main/kotlin/support/architecture`, `build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt`, `build-logic/src/test/kotlin/support/delivery/DeliveryProgramModelTest.kt`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.001.proof`, `requirement:KVP-REQ-002`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.002.proof` at `build/reports/delivery/KVP-002-types.json`.

**Public interface.** `DeliveryProgram domain types`.

**Internal implementation.** Value classes, closed variants, smart constructors, and exhaustive transitions.

**Effect and cost.** `PURE`, `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Boolean completion fields; Nullable authority; Stringly typed edge kinds; Unchecked constructors.

**RED.** `./gradlew :build-logic:test --tests "*DeliveryProgramModelNegativeTest"`. Expected failure: Invalid IDs, empty evidence, illegal status fields, or primitive authority remain representable.

**GREEN.** `./gradlew :build-logic:test --tests "*DeliveryProgramModelTest"`. Expected proof: Illegal states fail construction and closed outcomes are exhaustive.

**Review boundary.** No Gradle task registration or product code changes.

**Completion receipt.** `KVP-002-COMPLETE` at `build/reports/delivery/receipts/KVP-002-COMPLETE.receipt.json`. It consumes `KVP-002-RED`, `KVP-002-GREEN`, and all predecessor completion receipts.

### KVP-003: Encode dependency, join, retirement, invalidation, and recovery edges

**Goal.** Represent all-of, one-of, selected-lane joins, retirement edges, invalidation edges, and recovery transitions as typed graph semantics.

**Dependencies.** `KVP-002`. Computed wave: `2`.

**Allowed reads.** `build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/model/DeliveryGraph.kt`, `build-logic/src/test/kotlin/support/delivery/DeliveryGraphTest.kt`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.002.proof`, `requirement:KVP-REQ-002`, `requirement:KVP-REQ-003`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.003.proof` at `build/reports/delivery/KVP-003-graph.json`.

**Public interface.** `TypedGraph`.

**Internal implementation.** Acyclic task graph plus explicit non-ordering lifecycle edges.

**Effect and cost.** `PURE`, `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Flattening alternatives into dependsOn; Manual waves; Unbound retirement target; Recovery as a final sequential node.

**RED.** `./gradlew :build-logic:test --tests "*DeliveryGraphNegativeTest"`. Expected failure: Cycles, impossible joins, missing retirement targets, and recovery dead ends are accepted.

**GREEN.** `./gradlew :build-logic:test --tests "*DeliveryGraphTest"`. Expected proof: All graph semantics validate and topological waves derive mechanically.

**Review boundary.** Only graph semantics and fixtures may change.

**Completion receipt.** `KVP-003-COMPLETE` at `build/reports/delivery/receipts/KVP-003-COMPLETE.receipt.json`. It consumes `KVP-003-RED`, `KVP-003-GREEN`, and all predecessor completion receipts.

### KVP-004: Encode the canonical Kotlin delivery graph

**Goal.** Materialize every atomic task, module boundary, authority owner, effect owner, process transition, and gate in one Kotlin program object.

**Dependencies.** `KVP-002`, `KVP-003`. Computed wave: `3`.

**Allowed reads.** `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/KVP-001-authority-ledger.json`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveReusedIndexProgram.kt`, `build-logic/src/test/kotlin/support/delivery/KastVfsPassiveReusedIndexProgramTest.kt`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.002.proof`, `taskOutput:kvp.003.proof`, `requirement:KVP-REQ-002`, `requirement:KVP-REQ-003`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.004.proof` at `build/reports/delivery/KVP-004-program.json`.

**Public interface.** `KastVfsPassiveReusedIndexProgram`.

**Internal implementation.** Repository-local DSL definition with no status or wave literals.

**Effect and cost.** `PURE`, `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Duplicate authority owners; Missing task contract fields; Untraced requirement; Manually assigned wave.

**RED.** `./gradlew :build-logic:test --tests "*KastVfsPassiveProgramNegativeTest"`. Expected failure: The incomplete or contradictory graph is accepted.

**GREEN.** `./gradlew :build-logic:test --tests "*KastVfsPassiveReusedIndexProgramTest"`. Expected proof: The canonical graph validates, is connected, and reaches exactly one terminal proof.

**Review boundary.** No generated JSON may be hand-edited as authority.

**Completion receipt.** `KVP-004-COMPLETE` at `build/reports/delivery/receipts/KVP-004-COMPLETE.receipt.json`. It consumes `KVP-004-RED`, `KVP-004-GREEN`, and all predecessor completion receipts.

### KVP-005: Generate deterministic projections and JSON Schemas

**Goal.** Project the Kotlin authority deterministically to task, module, process, gate, and requirement JSON and validate each artifact against its schema.

**Dependencies.** `KVP-004`. Computed wave: `4`.

**Allowed reads.** `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveReusedIndexProgram.kt`.

**Allowed writes.** `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `gradle/delivery/schema/delivery-program.schema.json`, `gradle/delivery/schema/proof-receipt.schema.json`, `gradle/delivery/schema/requirement-trace.schema.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.004.proof`, `requirement:KVP-REQ-003`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.005.proof` at `build/reports/delivery/KVP-005-projection.json`.

**Public interface.** `DeterministicProgramProjection`.

**Internal implementation.** Canonical key ordering, stable task ordering, derived waves, and SHA-256 program fingerprint.

**Effect and cost.** `PURE`, `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Timestamps in projection; Unsorted map iteration; Status fields; Projection-only facts.

**RED.** `./gradlew verifyKastVfsPassiveProjectionNegative`. Expected failure: A reordered input or repeated generation changes bytes or schema-invalid output passes.

**GREEN.** `./gradlew generateKastVfsPassiveProjection verifyKastVfsPassiveProjection`. Expected proof: Two generations are byte-identical and every JSON artifact validates.

**Review boundary.** Generated projections and schemas only.

**Completion receipt.** `KVP-005-COMPLETE` at `build/reports/delivery/receipts/KVP-005-COMPLETE.receipt.json`. It consumes `KVP-005-RED`, `KVP-005-GREEN`, and all predecessor completion receipts.

### KVP-006: Register the Gradle delivery-program gate graph

**Goal.** Add the convention plugin that registers structural checks, per-gate proof tasks, receipt tasks, state derivation, and the terminal proof task.

**Dependencies.** `KVP-003`, `KVP-005`. Computed wave: `5`.

**Allowed reads.** `build-logic/src/main/kotlin/support/delivery`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `build-logic/src/main/kotlin/kast.architecture.gradle.kts`.

**Allowed writes.** `build-logic/src/main/kotlin/kast.vfs-passive-delivery.gradle.kts`, `build-logic/src/main/kotlin/support/delivery/gradle/DeliveryProgramTasks.kt`, `build.gradle.kts`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.003.proof`, `taskOutput:kvp.005.proof`, `requirement:KVP-REQ-002`, `requirement:KVP-REQ-003`, `requirement:KVP-REQ-004`.

**Outputs.** `kvp.006.proof` at `build/reports/delivery/KVP-006-gradle-gates.json`.

**Public interface.** `Gradle gate graph`.

**Internal implementation.** Task registration driven only by the typed program definition.

**Effect and cost.** `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Hard-coded manual completion; Gate without declared inputs; Receipt output shared by two gates; Gradle task not represented by a gate.

**RED.** `./gradlew verifyKastVfsPassiveGateGraphNegative`. Expected failure: Missing predecessor receipt inputs or duplicate receipt outputs are accepted.

**GREEN.** `./gradlew verifyKastVfsPassiveGateGraph`. Expected proof: Every gate consumes declared predecessor receipts and owns one output receipt.

**Review boundary.** Build policy and root plugin application only.

**Completion receipt.** `KVP-006-COMPLETE` at `build/reports/delivery/receipts/KVP-006-COMPLETE.receipt.json`. It consumes `KVP-006-RED`, `KVP-006-GREEN`, and all predecessor completion receipts.

### KVP-007: Bind and validate proof-carrying gate receipts

**Goal.** Define receipt creation and admission over program fingerprint, exact head, dependency receipt digests, input digest, command digest, observations, and artifact digests.

**Dependencies.** `KVP-006`. Computed wave: `6`.

**Allowed reads.** `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery/schema/proof-receipt.schema.json`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM0M1.kt`, `build-logic/src/main/kotlin/support/delivery/model/DeliveryReceipt.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/DeliveryReceiptJsonBoundary.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/Kvp001ReceiptTaskSupport.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/ReceiptIssuanceBoundary.kt`, `build-logic/src/test/kotlin/support/delivery/DeliveryTaskOwnershipTest.kt`, `build-logic/src/test/kotlin/support/delivery/proof/AGENTS.md`, `build-logic/src/test/kotlin/support/delivery/proof/DeliveryReceiptTest.kt`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `gradle/delivery/kast-vfs-passive-requirements.json`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/schema/proof-receipt.schema.json`, `scripts/verify_bundle.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.006.proof`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-023`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.007.proof` at `build/reports/delivery/KVP-007-receipts.json`. Same-head replay preserves receipt bytes; every changed head, requirement, command, dependency, input, or artifact invalidates proof.

**Public interface.** `ProofReceipt`.

**Internal implementation.** Canonical receipt codec, digest, dependency closure, and invalidation rules.

**Effect and cost.** `PURE`, `FILESYSTEM_READ`, `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Receipt without exact head; Receipt without command digest; Trusting filename as identity; Editable PASS field.

**RED.** `./gradlew :build-logic:test --tests "*DeliveryProofNegativeTest"`. Expected failure: Forged, stale, dependency-mismatched, or artifact-mismatched receipts are admitted.

**GREEN.** `./gradlew :build-logic:test --tests "*DeliveryProofTest"`. Expected proof: Same-head replay preserves receipt bytes; every changed head, requirement, command, dependency, input, or artifact invalidates proof.

**Review boundary.** Receipt model, issuance boundary, schema, and fixtures only.

**Completion receipt.** `KVP-007-COMPLETE` at `build/reports/delivery/receipts/KVP-007-COMPLETE.receipt.json`. It consumes `KVP-007-RED`, `KVP-007-GREEN`, and all predecessor completion receipts.

### KVP-008: Derive progression and terminal completion

**Goal.** Derive blocked, ready, invalid, and proven tasks, requirement PASS states, critical path, and terminal completion solely from admitted receipts.

**Dependencies.** `KVP-007`. Computed wave: `7`.

**Allowed reads.** `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `build/reports/delivery/receipts`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/model/DeliveryState.kt`, `build-logic/src/test/kotlin/support/delivery/DeliveryStateTest.kt`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.007.proof`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-025`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.008.proof` at `build/reports/delivery/KVP-008-derived-state.json`.

**Public interface.** `DerivedProgramState`.

**Internal implementation.** Pure receipt fold with no writable status or completion flag.

**Effect and cost.** `PURE`, `FILESYSTEM_READ`, `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Manual task completion; Partial requirement success; Completion with stale receipt; Completion before final revalidation.

**RED.** `./gradlew :build-logic:test --tests "*DeliveryStateNegativeTest"`. Expected failure: A manual flag, missing receipt, or stale receipt can report complete.

**GREEN.** `./gradlew :build-logic:test --tests "*DeliveryStateTest"`. Expected proof: Only a complete exact-head receipt closure derives terminal completion.

**Review boundary.** Progression model and reports only.

**Completion receipt.** `KVP-008-COMPLETE` at `build/reports/delivery/receipts/KVP-008-COMPLETE.receipt.json`. It consumes `KVP-008-RED`, `KVP-008-GREEN`, and all predecessor completion receipts.

### KVP-009: Enforce the IDE-read module and effect firewall

**Goal.** Add module roles and forbidden-reference checks that make the plugin unable to recover project-open, import, refresh, write, topology, JDBC, or isolated-runtime authority.

**Dependencies.** `KVP-001`, `KVP-006`. Computed wave: `6`.

**Allowed reads.** `build-logic/src/main/kotlin/support/architecture`, `settings.gradle.kts`, `workspace`, `runtime`, `symbol`, `indexer`.

**Allowed writes.** `build-logic/src/main/kotlin/support/architecture`, `gradle/architecture/kast-architecture-policy.json`, `settings.gradle.kts`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.001.proof`, `taskOutput:kvp.006.proof`, `requirement:KVP-REQ-016`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.009.proof` at `build/reports/delivery/KVP-009-firewall.json`.

**Public interface.** `IDE_READ_ONLY module role`.

**Internal implementation.** Allowed dependency and bytecode reference policy for workspace:intellij-read, runtime:ide-read, and ide-plugin.

**Effect and cost.** `BUILD_POLICY_WRITE`; `BUILD_POLICY`.

**Forbidden work.** Adding allowlist exceptions for implementation convenience; Service locator; Generic Project or database handle crossing boundaries.

**RED.** `./gradlew verifyKastVfsPassiveFirewallNegative`. Expected failure: Fixtures importing openProject, Gradle refresh, VFS refresh, change, topology, JDBC, or indexer process APIs compile.

**GREEN.** `./gradlew verifyKastModuleGraph verifyForbiddenEffects verifyKastVfsPassiveFirewall`. Expected proof: All invalid fixtures reject and the target read graph remains physically narrow.

**Review boundary.** Architecture policy, module declarations, and negative fixtures only.

**Completion receipt.** `KVP-009-COMPLETE` at `build/reports/delivery/receipts/KVP-009-COMPLETE.receipt.json`. It consumes `KVP-009-RED`, `KVP-009-GREEN`, and all predecessor completion receipts.

### KVP-010: Split a standalone IntelliJ plugin module

**Goal.** Move the private plugin payload out of the isolated indexer distribution into a first-class ide-plugin module without changing semantic behavior.

**Dependencies.** `KVP-009`. Computed wave: `7`.

**Allowed reads.** `indexer/build.gradle.kts`, `indexer/src/main`, `runtime`, `workspace`, `symbol`.

**Allowed writes.** `ide-plugin`, `settings.gradle.kts`, `indexer/build.gradle.kts`, `build-logic/src/main/kotlin`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.009.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-007`, `requirement:KVP-REQ-016`.

**Outputs.** `kvp.010.proof` at `ide-plugin/build/reports/KVP-010-plugin.json`.

**Public interface.** `Kast IDE plugin artifact`.

**Internal implementation.** Plugin module, descriptor, service registration point, and private library configuration.

**Effect and cost.** `PACKAGE_WRITE`, `BUILD_POLICY_WRITE`; `PACKAGE_BUILD`.

**Forbidden work.** Copying idea-home; Bundling IntelliJ/Kotlin/Gradle platform JARs; Changing operation semantics.

**RED.** `./gradlew :ide-plugin:standalonePluginNegativeProof`. Expected failure: No standalone plugin exists or the artifact still depends on private idea-home layout.

**GREEN.** `./gradlew :ide-plugin:buildPlugin`. Expected proof: Produces a standalone plugin ZIP from Kast classes and private non-platform libraries.

**Review boundary.** Only module split and packaging ownership.

**Completion receipt.** `KVP-010-COMPLETE` at `build/reports/delivery/receipts/KVP-010-COMPLETE.receipt.json`. It consumes `KVP-010-RED`, `KVP-010-GREEN`, and all predecessor completion receipts.

### KVP-012: Define IDE host compatibility identity

**Goal.** Parse IDE build, Kotlin plugin build, Kast plugin version, runtime protocol identity, registry digest, and wire-schema digest into one admitted compatibility value.

**Dependencies.** `KVP-002`, `KVP-010`. Computed wave: `8`.

**Allowed reads.** `protocol`, `ide-plugin`, `gradle/libs.versions.toml`, `build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt`.

**Allowed writes.** `AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM0M1.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptProgression.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptTasks.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/ReceiptProgressionRegistration.kt`, `build-logic/src/main/kotlin/support/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/plugin/IdeHostedPluginLayoutTasks.kt`, `build-logic/src/main/kotlin/support/tasks/VerifyGeneratedSerializationSourcesTask.kt`, `build-logic/src/main/kotlin/support/tasks/control/AGENTS.md`, `build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt`, `build-logic/src/test/kotlin/support/plugin/AGENTS.md`, `build-logic/src/test/kotlin/support/plugin/GenerateIdeHostCompatibilityReportTaskTest.kt`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `gradle/delivery/kast-vfs-passive-requirements.json`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/libs.versions.toml`, `ide-plugin/AGENTS.md`, `ide-plugin/build.gradle.kts`, `ide-plugin/src/main/kotlin`, `ide-plugin/src/main/kotlin/io/github/amichne/kast/ide/compatibility/AGENTS.md`, `ide-plugin/src/main/kotlin/io/github/amichne/kast/ide/compatibility/IdeHostCompatibilityMetadata.kt`, `ide-plugin/src/test`, `ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/compatibility/AGENTS.md`, `ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/compatibility/IdeHostCompatibilityNegativeTest.kt`, `ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/compatibility/IdeHostCompatibilityTest.kt`, `protocol/contract`, `protocol/contract/AGENTS.md`, `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AGENTS.md`, `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/compatibility/AGENTS.md`, `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/compatibility/IdeHostCapability.kt`, `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/compatibility/IdeHostCompatibility.kt`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.002.proof`, `taskOutput:kvp.010.proof`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-018`.

**Outputs.** `kvp.012.proof` at `ide-plugin/build/reports/KVP-012-compatibility.json`.

**Public interface.** `AdmittedIdeHostCompatibility`.

**Internal implementation.** Closed compatibility parser and mismatch failures.

**Effect and cost.** `PURE`, `METADATA_READ`, `BUILD_POLICY_WRITE`; `METADATA`.

**Forbidden work.** Loose version comparison; Ignoring Kotlin plugin build; Boolean compatible flag; Unknown capability acceptance.

**RED.** `./gradlew :ide-plugin:test --tests "*IdeHostCompatibilityNegativeTest"`. Expected failure: Wrong IDE, Kotlin, plugin, runtime protocol, registry, wire, or capability identity is accepted.

**GREEN.** `./gradlew :ide-plugin:generateIdeHostCompatibilityReport :ide-plugin:test --tests "*IdeHostCompatibilityTest"`. Expected proof: Only one exact supported compatibility tuple is admitted and projected from declared artifacts.

**Review boundary.** Compatibility contract, hosted build pins, generated report, and tests only.

**Completion receipt.** `KVP-012-COMPLETE` at `build/reports/delivery/receipts/KVP-012-COMPLETE.receipt.json`. It consumes `KVP-012-RED`, `KVP-012-GREEN`, `KVP-002-COMPLETE`, and `KVP-010-COMPLETE`.

### KVP-013: Define the project endpoint descriptor schema

**Goal.** Create endpoint schema v2 carrying exact root, host kind, PID, compatibility identity, socket, framing, epoch capability, and exact operation set.

**Dependencies.** `KVP-005`, `KVP-012`. Computed wave: `9`.

**Allowed reads.** `indexer/src/main/kotlin/io/github/amichne/kast/indexer/IndexerEndpointDescriptor.kt`,
`protocol/contract`, `protocol/wire`, `ide-plugin`, `build/reports/delivery/receipts`,
`build-logic/src/main/kotlin/support/delivery/tasks/Kvp001ReceiptTaskSupport.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/DeliveryReceiptJsonBoundary.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/ReceiptIssuanceBoundary.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/Kvp005ReceiptProgression.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/Kvp005ReceiptTasks.kt`,
`build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptProgression.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptTasks.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/DeliveryReceiptRegistrationModel.kt`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt`,
`build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM0M1.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptRegistration.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/endpoint/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/endpoint/Kvp013EndpointDescriptorReport.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/endpoint/Kvp013ReceiptProgression.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/endpoint/Kvp013ReceiptRegistration.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/endpoint/Kvp013ReceiptTasks.kt`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`,
`build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/ReceiptProgressionRegistration.kt`,
`protocol/wire`,
`gradle/delivery/AGENTS.md`, `gradle/delivery/kast-vfs-passive-requirements.json`,
`gradle/delivery/kast-vfs-passive-reused-index-program.json`,
`gradle/delivery/schema/AGENTS.md`, `gradle/delivery/schema/ide-endpoint.schema.json`,
`docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.005.proof`, `taskOutput:kvp.012.proof`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-018`.

**Outputs.** `kvp.013.proof` at `protocol/wire/build/reports/KVP-013-endpoint-schema.json`.

**Public interface.** `IdeEndpointDescriptorV2`.

**Internal implementation.** Generated serialization, strict parsing, and canonical descriptor/report bytes.

**Effect and cost.** `PURE`, `BUILD_POLICY_WRITE`; `METADATA`.

**Forbidden work.** Reusing v1 without host proof; Unknown fields; Missing capability set; Raw string validation downstream.

**RED.** `./gradlew :protocol:wire:test --tests "*IdeEndpointDescriptorNegativeTest"`. Expected failure: Malformed, ambiguous, stale, or under-specified endpoint documents are admitted.

**GREEN.** `./gradlew :protocol:wire:generateIdeEndpointDescriptorReport :protocol:wire:test --tests "*IdeEndpointDescriptorTest"`. Expected proof: Descriptor round-trips exactly and every mismatch remains a closed rejection.

**Review boundary.** Endpoint contract, codec, schema, proof report, generated authority projections, and exact receipt progression only.

**Completion receipt.** `KVP-013-COMPLETE` at `build/reports/delivery/receipts/KVP-013-COMPLETE.receipt.json`. It consumes `KVP-013-RED`, `KVP-013-GREEN`, and all predecessor completion receipts.

### KVP-014: Admit the existing open IntelliJ Project

**Goal.** Refine the Project supplied by IntelliJ into one exact-root, non-disposed, Gradle-model-ready, K2-capable read authority without opening or importing anything.

**Dependencies.** `KVP-009`, `KVP-012`. Computed wave: `9`.

**Allowed reads.** `AGENTS.md`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `workspace/intellij/src/main`, `workspace/contract`, `protocol/contract`, `ide-plugin`, `build-logic/src/main/kotlin/support/architecture`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/architecture`, `gradle/delivery`, `scripts/verify_bundle.py`.

**Allowed writes.** `AGENTS.md`, `settings.gradle.kts`, `workspace/intellij-read`, `build-logic/src/main/kotlin/kast.architecture.gradle.kts`, `build-logic/src/main/kotlin/support/architecture/AGENTS.md`, `build-logic/src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt`, `build-logic/src/main/kotlin/support/architecture/policy/AGENTS.md`, `build-logic/src/test/kotlin/support/architecture/IdeReadFirewallTest.kt`, `build-logic/src/test/kotlin/support/architecture/policy/KastCleanSlatePolicyTest.kt`, `gradle/architecture/kast-architecture-policy.json`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramFoundation.kt`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.009.proof`, `taskOutput:kvp.012.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`.

**Outputs.** `kvp.014.proof` at `workspace/intellij-read/build/reports/KVP-014-project-admission.json`.

**Public interface.** `AdmittedIdeProject`.

**Internal implementation.** Project admission adapter with closed root, disposal, model, and plugin failures.

**Effect and cost.** `IDE_PROJECT_READ`; `METADATA`.

**Forbidden work.** ProjectManagerEx.openProject; InstalledIntellijWorkspace.open; ExternalSystemUtil link or refresh; Waiting for indexing.

**RED.** `./gradlew :workspace:intellij-read:test --tests "*ExistingProjectAdmissionNegativeTest"`. Expected failure: Wrong-root, disposed, unimported, dumb, or incompatible Projects are admitted or trigger repair.

**GREEN.** `./gradlew :workspace:intellij-read:test --tests "*ExistingProjectAdmissionTest"`. Expected proof: Only the already-open exact Project yields AdmittedIdeProject and no stronger effect occurs.

**Review boundary.** New read adapter, its exact module authority, and KVP-014 receipt progression only; existing bootstrap remains untouched.

**Completion receipt.** `KVP-014-COMPLETE` at `build/reports/delivery/receipts/KVP-014-COMPLETE.receipt.json`. It consumes `KVP-014-RED`, `KVP-014-GREEN`, and all predecessor completion receipts.

### KVP-015: Characterize model and epoch signals

**Goal.** Produce executable evidence for the supported project-model, PSI, VFS, root-model, and dumb-mode signals that can define a stable read epoch without refresh or repository scanning.

**Dependencies.** `KVP-014`. Computed wave: `10`.

**Allowed reads.** `AGENTS.md`, `workspace/intellij-read`, `gradle/libs.versions.toml`, `com.jetbrains.intellij.idea:ideaIC:262.9437.185@zip`, `fixtures`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery`, `docs/AGENTS.md`, `docs/engineering`, `scripts/verify_bundle.py`.

**Allowed writes.** `workspace/intellij-read/build.gradle.kts`, `workspace/intellij-read/AGENTS.md`, `workspace/intellij-read/src/test`, `docs/AGENTS.md`, `docs/engineering`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/Kvp012ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/Kvp014ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.014.proof`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.015.proof` at `workspace/intellij-read/build/reports/KVP-015-epoch-ledger.json`.

**Public interface.** `IdeEpochSignalLedger`.

**Internal implementation.** Supported-build tests and selected signal set.

**Effect and cost.** `IDE_PROJECT_READ`, `NATIVE_INDEX_READ`; `SEMANTIC_READ`.

**Forbidden work.** Freezing an epoch contract from undocumented assumptions; VFS refresh; Gradle import or repair; Recursive filesystem or VFS walk; Source-content hashing; Event-triggered semantic jobs; Global Boolean readiness; Production epoch policy; Product behavior assigned to KVP-016 or KVP-017.

**RED.** `./gradlew :workspace:intellij-read:characterizeEpochNegative`. Expected failure: Signal gaps, movement, dumb-mode transitions, and root-model changes are not detected.

**GREEN.** `./gradlew :workspace:intellij-read:characterizeEpoch`. Expected proof: The selected signals detect every fixture movement without refresh or source-tree walk.

**Review boundary.** Characterization fixtures, ledger/report generation, the minimum task-authority correction, and KVP-015 receipt progression only.

**Completion receipt.** `KVP-015-COMPLETE` at `build/reports/delivery/receipts/KVP-015-COMPLETE.receipt.json`. It consumes `KVP-015-RED`, `KVP-015-GREEN`, and all predecessor completion receipts.

### KVP-016: Capture a detached existing-project model

**Goal.** Read and detach root, modules, source roots, Gradle ownership, SDK, classpath identity, and compatibility from the admitted Project in a short cancellable read; KVP-017 owns epoch observation and identity, while KVP-019 owns freshness policy.

**Dependencies.** `KVP-014`, `KVP-015`. Computed wave: `11`.

**Allowed reads.** `AGENTS.md`, `workspace/intellij-read`, `workspace/contract`, `runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/platform`, `gradle/libs.versions.toml`, `com.jetbrains.intellij.idea:ideaIC:262.9437.185@zip`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery`, `docs/AGENTS.md`, `docs/engineering`, `scripts/verify_bundle.py`.

**Allowed writes.** `workspace/intellij-read`, `workspace/contract`, `docs/AGENTS.md`, `docs/engineering`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/Kvp014ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/Kvp015ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.014.proof`, `taskOutput:kvp.015.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-016`.

**Outputs.** `kvp.016.proof` at `workspace/intellij-read/build/reports/KVP-016-detached-model.json`.

**Public interface.** `DetachedIdeWorkspaceModel`.

**Internal implementation.** Bounded cancellable read adapter, primitive-only raw observation, immutable refinement, deterministic report, and exact-head receipt admission.

**Effect and cost.** `IDE_PROJECT_READ`; `SEMANTIC_READ`.

**Forbidden work.** Leaking Project, VirtualFile, Module, SearchScope, PSI, Gradle DataNode, callback, or mutable collection; Gradle import, link, prepare, or repair; Blocking read action or synchronous nonblocking execution; Waiting for smart mode; VFS refresh; Recursive filesystem or VFS walk; Source-content hashing; Unbounded model traversal; Operation routing; Production epoch identity or freshness policy.

**RED.** `./gradlew :workspace:intellij-read:test --tests "*DetachedModelNegativeTest"`. Expected failure: Live platform objects escape or capture repairs missing state.

**GREEN.** `./gradlew :workspace:intellij-read:test --tests "*DetachedModelTest"`. Expected proof: Capture is detached, bounded, cancellable, exact-root, and model-complete.

**Review boundary.** Detached model capture, the minimum task-authority correction, and KVP-016 receipt progression only; no epoch policy or operation routing.

**Completion receipt.** `KVP-016-COMPLETE` at `build/reports/delivery/receipts/KVP-016-COMPLETE.receipt.json`. It consumes `KVP-016-RED`, `KVP-016-GREEN`, and all predecessor completion receipts.

### KVP-017: Define the project read epoch

**Goal.** Convert the characterized platform signals into one proof-carrying ProjectReadEpoch that can be observed before and after a semantic read.

**Dependencies.** `KVP-015`. Computed wave: `11`.

**Allowed reads.** `AGENTS.md`, `workspace/intellij-read`, `workspace/contract`, `gradle/libs.versions.toml`, `com.jetbrains.intellij.idea:ideaIC:262.9437.185@zip`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery`, `docs/AGENTS.md`, `docs/engineering`, `scripts/verify_bundle.py`, `scripts/verify_kvp017_report.py`.

**Allowed writes.** `workspace/contract`, `workspace/intellij-read`, `docs/AGENTS.md`, `docs/engineering`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp017_report.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.015.proof`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.017.proof` at `workspace/intellij-read/build/reports/KVP-017-read-epoch.json`.

**Public interface.** `ProjectReadEpoch`.

**Internal implementation.** Compiler-confined source-bound epoch, typed adapter-private signal state, pure bounded VFS classification, short cancellable IDEA observation, deterministic report, and exact-head receipt admission.

**Effect and cost.** `PURE`, `IDE_PROJECT_READ`; `METADATA`.

**Forbidden work.** Primitive counters crossing modules; Epoch recreated by callers; Treating dumb mode as an epoch value; VFS refresh; Gradle import or repair; Repository or VFS traversal; Source-content hashing; Blocking wait; Per-event semantic job; Semantic work on the EDT; Live Project, listener, counter, callback, or signal-state escape.

**RED.** `./gradlew :workspace:contract:test --tests "*ProjectReadEpochNegativeTest"`. Expected failure: Malformed or incomparable epochs and repeated validation remain possible.

**GREEN.** `./gradlew :workspace:contract:test :workspace:intellij-read:test --tests "*ProjectReadEpochTest"`. Expected proof: Epochs compare only within one admitted Project/runtime and movement changes identity.

**Review boundary.** Epoch contract and adapter, the minimum KVP-017 authority correction, and KVP-017 receipt progression only.

**Completion receipt.** `KVP-017-COMPLETE` at `build/reports/delivery/receipts/KVP-017-COMPLETE.receipt.json`. It consumes `KVP-017-RED`, `KVP-017-GREEN`, and all predecessor completion receipts.

### KVP-018: Remove source-tree hashing from the hosted read path

**Goal.** Ensure the hosted model and epoch path performs no Files.walk, physical source scan, source-content hash, network access, blocking wait, repository traversal, refresh/import, or stronger read-only classpath effect.

**Dependencies.** `KVP-016`, `KVP-017`. Computed wave: `12`.

**Allowed reads.** `AGENTS.md`, `workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleModelCapture.kt`, `workspace/intellij-read`, `kernel`, `protocol/contract`, `workspace/contract`, `gradle/libs.versions.toml`, `org.jetbrains.kotlin:kotlin-stdlib:2.4.10`, `org.jetbrains:annotations:13.0`, `build-logic/src/main/kotlin/support/architecture`, `build-logic/src/test/kotlin/support/architecture`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery`, `docs/AGENTS.md`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`.

**Allowed writes.** `workspace/intellij-read`, `build-logic/src/main/kotlin/support/architecture`, `build-logic/src/test/kotlin/support/architecture`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/Kvp015ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/verify_bundle.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.016.proof`, `taskOutput:kvp.017.proof`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-014`, `requirement:KVP-REQ-016`.

**Outputs.** `kvp.018.proof` at `workspace/intellij-read/build/reports/KVP-018-no-walk.json`.

**Public interface.** `VfsPassiveHostedModelCapture`.

**Internal implementation.** Whole-module compiled-class refinement, exact project and external runtime-artifact byte admission, finite hosted effect classification, deterministic all-zero report, and exact-head receipt admission; the hosted product API remains the admitted detached-model and epoch observations.

**Effect and cost.** `IDE_PROJECT_READ`, `BUILD_POLICY_WRITE`; `SEMANTIC_READ`.

**Forbidden work.** Moving Files.walk outside the read but retaining it on each request; Hashing every source file; Using LocalFileSystem refreshAndFind; Network access; Blocking waits; Compiled-class allowlist; Changing the legacy isolated fixture; Adding KVP-019 freshness policy.

**RED.** `./gradlew :workspace:intellij-read:verifyNoHostedRepositoryWalkNegative`. Expected failure: One or more injected traversal, physical source read, source hash, VFS refresh, network, or blocking-wait JVM families are not detected.

**GREEN.** `./gradlew :workspace:intellij-read:verifyNoHostedRepositoryWalk`. Expected proof: The complete hosted production inventory contains zero repository traversal, source hashing, network access, blocking waits, refresh/import, or stronger forbidden authorities.

**Review boundary.** Hosted whole-module effect policy, the minimum KVP-018 authority correction, deterministic report, and KVP-018 receipt progression only; the isolated fixture may retain its old capture and KVP-019 owns freshness.

**Completion receipt.** `KVP-018-COMPLETE` at `build/reports/delivery/receipts/KVP-018-COMPLETE.receipt.json`. It consumes `KVP-018-RED`, `KVP-018-GREEN`, and all predecessor completion receipts.

### KVP-019: Issue a VFS-passive freshness capability

**Goal.** Admit current IDE-visible state without refresh, listener-driven semantic work, import, or repair and bind it to ProjectReadEpoch.

**Dependencies.** `KVP-017`, `KVP-018`. Computed wave: `13`.

**Allowed reads.** `AGENTS.md`, `workspace/contract`, `workspace/intellij-read`, `build-logic/src/main/kotlin/support/architecture`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery`, `docs/AGENTS.md`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/AGENTS.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp019_delivery.py`.

**Allowed writes.** `workspace/contract`, `workspace/intellij-read`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/Kvp015ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/Kvp018ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/AGENTS.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp019_delivery.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.017.proof`, `taskOutput:kvp.018.proof`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.019.proof` at `workspace/intellij-read/build/reports/KVP-019-vfs-passive.json`.

**Public interface.** `VfsPassiveReadCapability`.

**Internal implementation.** Admission from exact Project plus epoch with closed dumb, disposed, unavailable, and moved failures.

**Effect and cost.** `IDE_PROJECT_READ`; `METADATA`.

**Forbidden work.** VFS refresh; Gradle import; Background repair; Per-event semantic job; Event-triggered semantic work from a VFS listener.

**RED.** `./gradlew :workspace:intellij-read:test --tests "*VfsPassiveAdmissionNegativeTest"`. Expected failure: Dirty or moved state causes refresh, repair, listener work, or false readiness.

**GREEN.** `./gradlew :workspace:intellij-read:test --tests "*VfsPassiveAdmissionTest"`. Expected proof: Admission reads only the IDE snapshot and returns a typed capability or closed rejection.

**Review boundary.** Freshness admission only.

**Completion receipt.** `KVP-019-COMPLETE` at `build/reports/delivery/receipts/KVP-019-COMPLETE.receipt.json`. It consumes `KVP-019-RED`, `KVP-019-GREEN`, and all predecessor completion receipts.

### KVP-020: Enforce single-flight project read admission

**Goal.** Issue at most one active semantic read permit per Project with at most one bounded queued request and lifecycle cancellation.

**Dependencies.** `KVP-014`, `KVP-019`. Computed wave: `14`.

**Allowed reads.** `runtime/ide-read`, `workspace/contract`, `ide-plugin`.

**Allowed writes.** `runtime/ide-read`, `runtime/ide-read/src/test`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.014.proof`, `taskOutput:kvp.019.proof`, `requirement:KVP-REQ-013`, `requirement:KVP-REQ-019`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.020.proof` at `runtime/ide-read/build/reports/KVP-020-single-flight.json`.

**Public interface.** `ProjectReadPermit`.

**Internal implementation.** Project-scoped permit state machine and Busy rejection.

**Effect and cost.** `PURE`; `SEMANTIC_READ`.

**Forbidden work.** Unbounded Channel; Global lock across projects; Holding permit after disconnect or disposal; Parallel semantic reads by default.

**RED.** `./gradlew :runtime:ide-read:test --tests "*SingleFlightNegativeTest"`. Expected failure: Concurrent requests exceed one active read or queue without bound.

**GREEN.** `./gradlew :runtime:ide-read:test --tests "*SingleFlightTest"`. Expected proof: Active and queued bounds hold and cancellation releases authority exactly once.

**Review boundary.** Admission state machine only.

**Completion receipt.** `KVP-020-COMPLETE` at `build/reports/delivery/receipts/KVP-020-COMPLETE.receipt.json`. It consumes `KVP-020-RED`, `KVP-020-GREEN`, and all predecessor completion receipts.

### KVP-021: Execute cancellable smart reads

**Goal.** Run semantic work through write-priority cancellable smart read actions, propagate platform cancellation, and fail fast during dumb mode or disposal.

**Dependencies.** `KVP-019`, `KVP-020`. Computed wave: `15`.

**Allowed reads.** `AGENTS.md`, `gradle/libs.versions.toml`, `runtime/ide-read`, `symbol/intellij`, `workspace/contract`, `workspace/intellij-read`, `build-logic/src/main/kotlin/support/architecture`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/architecture`, `gradle/delivery`, `docs/AGENTS.md`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/AGENTS.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp021_delivery.py`.

**Allowed writes.** `AGENTS.md`, `runtime/ide-read`, `symbol/intellij/src/test`, `workspace/intellij-read/AGENTS.md`, `workspace/intellij-read/build.gradle.kts`, `workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/AGENTS.md`, `workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/ExistingProjectAdmission.kt`, `workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/AGENTS.md`, `workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/epoch/execution`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/Kvp019ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/singleflight/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/singleflight/Kvp020ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/singleflight/cancellable`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/AGENTS.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp021_delivery.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.019.proof`, `taskOutput:kvp.020.proof`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-013`, `requirement:KVP-REQ-016`.

**Outputs.** `kvp.021.proof` at `runtime/ide-read/build/reports/KVP-021-cancellable-read.json`.

**Public interface.** `CancellableProjectReadExecutor`.

**Internal implementation.** Permit-scoped smart read wrapper with typed terminal outcomes.

**Effect and cost.** `IDE_PROJECT_READ`, `NATIVE_INDEX_READ`, `SEMANTIC_READ`; `SEMANTIC_READ`.

**Forbidden work.** ReadAction.computeBlocking; waitForSmartMode; Thread.sleep polling; Swallowing ProcessCanceledException; EDT execution.

**RED.** `./gradlew :runtime:ide-read:test --tests "*CancellableReadNegativeTest"`. Expected failure: Write priority, cancellation, dumb mode, disposal, or timeout can hang or appear empty.

**GREEN.** `./gradlew :runtime:ide-read:test --tests "*CancellableReadTest"`. Expected proof: Reads cancel for writes, reject dumb/disposed state, and release permits.

**Review boundary.** Read executor only; no operation semantics.

**Completion receipt.** `KVP-021-COMPLETE` at `build/reports/delivery/receipts/KVP-021-COMPLETE.receipt.json`. It consumes `KVP-021-RED`, `KVP-021-GREEN`, and all predecessor completion receipts.

### KVP-022: Revalidate the epoch before accepting a result

**Goal.** Observe ProjectReadEpoch before the read and after detached projection; accept only equality and reject every moved-state result.

**Dependencies.** `KVP-021`. Computed wave: `16`.

**Allowed reads.** `AGENTS.md`, `gradle/libs.versions.toml`, `runtime/ide-read`, `workspace/contract`, `workspace/intellij-read`, `build-logic/src/main/kotlin/support/delivery`, `build/reports/delivery/receipts`, `gradle/delivery`, `docs/AGENTS.md`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/AGENTS.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp022_delivery.py`.

**Allowed writes.** `AGENTS.md`, `runtime/ide-read`, `build-logic/src/main/kotlin/support/delivery/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM2.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/registration/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/singleflight/AGENTS.md`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/singleflight/cancellable/Kvp021ReceiptRegistration.kt`, `build-logic/src/main/kotlin/support/delivery/tasks/receipt/gate/firewall/plugin/project/epoch/model/freshness/singleflight/revalidation`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`, `gradle/delivery/kast-vfs-passive-requirements.json`, `docs/kast-vfs-passive-reused-index-delivery-program.md`, `scripts/AGENTS.md`, `scripts/verify_bundle.py`, `scripts/verify_kvp022_delivery.py`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.021.proof`, `requirement:KVP-REQ-010`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.022.proof` at `runtime/ide-read/build/reports/KVP-022-epoch-revalidation.json`.

**Public interface.** `RevalidatedIdeReadResult`.

**Internal implementation.** Proof transition from detached result plus equal before/after epochs.

**Effect and cost.** `IDE_PROJECT_READ`, `SEMANTIC_READ`; `SEMANTIC_READ`.

**Forbidden work.** Accepting stale output with a warning; Retry loop without budget; Reusing prior epoch after cancellation.

**RED.** `./gradlew :runtime:ide-read:test --tests "*EpochRevalidationNegativeTest"`. Expected failure: Movement during query or projection can return Complete.

**GREEN.** `./gradlew :runtime:ide-read:test --tests "*EpochRevalidationTest"`. Expected proof: Stable reads complete and moved reads return closed WorkspaceMoved rejection.

**Review boundary.** Epoch revalidation only.

**Completion receipt.** `KVP-022-COMPLETE` at `build/reports/delivery/receipts/KVP-022-COMPLETE.receipt.json`. It consumes `KVP-022-RED`, `KVP-022-GREEN`, and all predecessor completion receipts.

### KVP-023: Assemble the physically read-only IDE runtime

**Goal.** Construct exactly four operation bindings from read-only workspace and symbol ports without full composition, persistence, change, topology, bootstrap, or runtime acquisition.

**Dependencies.** `KVP-009`, `KVP-016`, `KVP-022`. Computed wave: `17`.

**Allowed reads.** `runtime/server`, `runtime/composition`, `workspace/service`, `symbol/service`, `protocol`.

**Allowed writes.** `runtime/ide-read`, `runtime/ide-read/src/test`, `settings.gradle.kts`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.009.proof`, `taskOutput:kvp.016.proof`, `taskOutput:kvp.022.proof`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-016`, `requirement:KVP-REQ-018`.

**Outputs.** `kvp.023.proof` at `runtime/ide-read/build/reports/KVP-023-read-runtime.json`.

**Public interface.** `IdeReadRuntimeDispatch`.

**Internal implementation.** Capability-scoped typed dispatch for the exact four MVP operations.

**Effect and cost.** `IDE_PROJECT_READ`, `NATIVE_INDEX_READ`, `SEMANTIC_READ`; `SEMANTIC_READ`.

**Forbidden work.** Depending on runtime:composition; Depending on evidence:sqlite or change or topology; Service locator; Operation not in capability set.

**RED.** `./gradlew :runtime:ide-read:verifyReadOnlyGraphNegative`. Expected failure: An injected stronger dependency or fifth operation is accepted.

**GREEN.** `./gradlew :runtime:ide-read:test :runtime:ide-read:verifyReadOnlyGraph`. Expected proof: The graph contains exactly four operations and only read effects.

**Review boundary.** New read composition only.

**Completion receipt.** `KVP-023-COMPLETE` at `build/reports/delivery/receipts/KVP-023-COMPLETE.receipt.json`. It consumes `KVP-023-RED`, `KVP-023-GREEN`, and all predecessor completion receipts.

### KVP-024: Publish the exact-root project endpoint

**Goal.** Start a Project-level service, bind one UDS endpoint, and atomically publish descriptor v2 only after read runtime construction succeeds.

**Dependencies.** `KVP-013`, `KVP-023`. Computed wave: `18`.

**Allowed reads.** `ide-plugin`, `indexer/src/main/kotlin/io/github/amichne/kast/indexer/InstalledIndexerTransport.kt`, `protocol/wire`.

**Allowed writes.** `ide-plugin/src/main/kotlin`, `ide-plugin/src/test`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.013.proof`, `taskOutput:kvp.023.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-019`.

**Outputs.** `kvp.024.proof` at `ide-plugin/build/reports/KVP-024-endpoint.json`.

**Public interface.** `ReadyIdeEndpoint`.

**Internal implementation.** Prepared endpoint to bound socket to atomically published descriptor transition.

**Effect and cost.** `UDS_BIND`, `ENDPOINT_DESCRIPTOR_WRITE`; `METADATA`.

**Forbidden work.** Publishing readiness before runtime construction; Deleting occupied non-socket path; Binding wrong root; Multiple endpoints per Project.

**RED.** `./gradlew :ide-plugin:test --tests "*IdeEndpointPublicationNegativeTest"`. Expected failure: Wrong-root, occupied, partial, or duplicate endpoint can advertise ready.

**GREEN.** `./gradlew :ide-plugin:test --tests "*IdeEndpointPublicationTest"`. Expected proof: One exact endpoint becomes reachable only after complete runtime construction.

**Review boundary.** Endpoint publication only.

**Completion receipt.** `KVP-024-COMPLETE` at `build/reports/delivery/receipts/KVP-024-COMPLETE.receipt.json`. It consumes `KVP-024-RED`, `KVP-024-GREEN`, and all predecessor completion receipts.

### KVP-025: Bind endpoint retirement to Project and plugin lifecycle

**Goal.** Retire descriptor and socket on Project close, plugin unload, bind failure, runtime failure, and service cancellation with idempotent cleanup.

**Dependencies.** `KVP-024`. Computed wave: `19`.

**Allowed reads.** `ide-plugin/src/main/kotlin`, `protocol/wire`.

**Allowed writes.** `ide-plugin/src/main/kotlin`, `ide-plugin/src/test`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.024.proof`, `requirement:KVP-REQ-019`.

**Outputs.** `kvp.025.proof` at `ide-plugin/build/reports/KVP-025-retirement.json`.

**Public interface.** `RetiredIdeEndpoint`.

**Internal implementation.** Project-scoped cleanup and recovery transitions.

**Effect and cost.** `UDS_BIND`, `ENDPOINT_DESCRIPTOR_WRITE`; `METADATA`.

**Forbidden work.** Global application lifetime; Stale descriptor retention; Deleting unrelated paths; Non-idempotent cleanup.

**RED.** `./gradlew :ide-plugin:test --tests "*IdeEndpointRetirementNegativeTest"`. Expected failure: Failure or disposal leaves reachable stale readiness or unsafe cleanup.

**GREEN.** `./gradlew :ide-plugin:test --tests "*IdeEndpointRetirementTest"`. Expected proof: Every lifecycle termination retires owned artifacts exactly once.

**Review boundary.** Lifecycle and cleanup only.

**Completion receipt.** `KVP-025-COMPLETE` at `build/reports/delivery/receipts/KVP-025-COMPLETE.receipt.json`. It consumes `KVP-025-RED`, `KVP-025-GREEN`, and all predecessor completion receipts.

### KVP-026: Admit the compatible exact-root IDE endpoint in the CLI

**Goal.** Parse descriptor v2 once, prove compatibility, root, capabilities, reachability, and runtime identity, then issue one endpoint capability.

**Dependencies.** `KVP-007`, `KVP-013`, `KVP-024`. Computed wave: `19`.

**Allowed reads.** `cli/src/main/kotlin`, `protocol/wire`, `gradle/delivery/schema/ide-endpoint.schema.json`.

**Allowed writes.** `cli/src/main/kotlin`, `cli/src/test`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.007.proof`, `taskOutput:kvp.013.proof`, `taskOutput:kvp.024.proof`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-018`.

**Outputs.** `kvp.026.proof` at `cli/build/reports/KVP-026-cli-admission.json`.

**Public interface.** `AdmittedIdeEndpoint`.

**Internal implementation.** Raw descriptor to parsed to compatible to exact-root to reachable endpoint transition.

**Effect and cost.** `FILESYSTEM_READ`, `UDS_CONNECT`; `METADATA`.

**Forbidden work.** Scanning arbitrary sockets; First-match endpoint selection; Ignoring capability set; Revalidating raw strings downstream.

**RED.** `./gradlew :cli:test --tests "*IdeEndpointAdmissionNegativeTest"`. Expected failure: Wrong root, build, schema, PID, runtime, capability, or unreachable endpoint is admitted.

**GREEN.** `./gradlew :cli:test --tests "*IdeEndpointAdmissionTest"`. Expected proof: Only one compatible exact-root endpoint yields dispatch capability.

**Review boundary.** CLI endpoint admission only.

**Completion receipt.** `KVP-026-COMPLETE` at `build/reports/delivery/receipts/KVP-026-COMPLETE.receipt.json`. It consumes `KVP-026-RED`, `KVP-026-GREEN`, and all predecessor completion receipts.

### KVP-027: Remove semantic runtime acquisition and process fallback from default demand

**Goal.** Make missing or incompatible IDE endpoint a closed rejection and make runtime-store, archive, executable, launchd, and process-start paths unreachable from the default CLI composition.

**Dependencies.** `KVP-026`. Computed wave: `20`.

**Allowed reads.** `cli/src/main/kotlin/io/github/amichne/kast/cli/runtime`, `cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap`, `distribution/managed`.

**Allowed writes.** `cli/src/main/kotlin`, `cli/src/test`, `build-logic/src/main/kotlin/support/architecture`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.026.proof`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-018`.

**Outputs.** `kvp.027.proof` at `cli/build/reports/KVP-027-no-fallback.json`.

**Public interface.** `IdeOnlyRuntimeDemander`.

**Internal implementation.** Default runtime admission with no repair or fallback authority.

**Effect and cost.** `FILESYSTEM_READ`, `UDS_CONNECT`; `METADATA`.

**Forbidden work.** ManagedSemanticRuntimeProvider; ExactRootProcessRuntimeDemander; IndexerExecutable; RuntimeStore; ProcessBuilder or launchctl fallback.

**RED.** `./gradlew :cli:verifyNoDefaultRuntimeFallbackNegative`. Expected failure: Injected runtime acquisition or process start remains reachable.

**GREEN.** `./gradlew :cli:test :cli:verifyNoDefaultRuntimeFallback`. Expected proof: Missing plugin or endpoint rejects directly and zero fallback code is reachable.

**Review boundary.** Default composition only; explicit legacy fixtures may remain until release retirement.

**Completion receipt.** `KVP-027-COMPLETE` at `build/reports/delivery/receipts/KVP-027-COMPLETE.receipt.json`. It consumes `KVP-027-RED`, `KVP-027-GREEN`, and all predecessor completion receipts.

### KVP-028: Route workspace.inspect through the reused Project

**Goal.** Serve workspace.inspect from the admitted detached Project model and read epoch through the IDE endpoint.

**Dependencies.** `KVP-023`, `KVP-026`. Computed wave: `20`.

**Allowed reads.** `workspace/service`, `runtime/ide-read`, `cli`, `protocol`.

**Allowed writes.** `runtime/ide-read`, `ide-plugin/src/test`, `cli/src/test`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.023.proof`, `taskOutput:kvp.026.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-011`.

**Outputs.** `kvp.028.proof` at `build/reports/ide-hosted/KVP-028-workspace-inspect.json`.

**Public interface.** `workspace.inspect`.

**Internal implementation.** Exact-root read-only workspace status with host kind and epoch evidence.

**Effect and cost.** `IDE_PROJECT_READ`, `UDS_CONNECT`; `METADATA`.

**Forbidden work.** Calling workspace transition; Refresh or import; Reading SQLite; Starting a process.

**RED.** `./gradlew ideHostedWorkspaceInspectNegativeProof`. Expected failure: Inspect can repair missing state or report isolated host authority.

**GREEN.** `./gradlew ideHostedWorkspaceInspectAcceptance`. Expected proof: Inspect reports IDE_PROJECT host, exact root, capabilities, and current epoch without stronger work.

**Review boundary.** Only workspace.inspect routing.

**Completion receipt.** `KVP-028-COMPLETE` at `build/reports/delivery/receipts/KVP-028-COMPLETE.receipt.json`. It consumes `KVP-028-RED`, `KVP-028-GREEN`, and all predecessor completion receipts.

### KVP-029: Route bounded symbol discovery through the reused index

**Goal.** Serve symbol.discover from native IntelliJ contributors under compiled scope, single-flight admission, cancellation, bounds, and epoch revalidation.

**Dependencies.** `KVP-021`, `KVP-023`, `KVP-028`. Computed wave: `21`.

**Allowed reads.** `symbol/intellij`, `symbol/service`, `runtime/ide-read`, `fixtures`.

**Allowed writes.** `runtime/ide-read`, `symbol/intellij/src/test`, `integration-tests`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.021.proof`, `taskOutput:kvp.023.proof`, `taskOutput:kvp.028.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-013`.

**Outputs.** `kvp.029.proof` at `build/reports/ide-hosted/KVP-029-discover.json`.

**Public interface.** `symbol.discover`.

**Internal implementation.** Detached bounded candidates bound to root and epoch.

**Effect and cost.** `NATIVE_INDEX_READ`, `SEMANTIC_READ`; `INDEX_LOOKUP`.

**Forbidden work.** Repository scan; Unscoped provider fallback; Completeness after cap; Persistence write.

**RED.** `./gradlew ideHostedSymbolDiscoverNegativeProof`. Expected failure: Collision, dumb transition, bound, cancellation, or movement can appear complete.

**GREEN.** `./gradlew ideHostedSymbolDiscoverAcceptance`. Expected proof: Native discovery is bounded, exact-scope, cancellable, detached, and VFS-passive.

**Review boundary.** Only symbol.discover routing.

**Completion receipt.** `KVP-029-COMPLETE` at `build/reports/delivery/receipts/KVP-029-COMPLETE.receipt.json`. It consumes `KVP-029-RED`, `KVP-029-GREEN`, and all predecessor completion receipts.

### KVP-030: Route exact selector resolution

**Goal.** Refine one discovery candidate into one compiler-grounded selector under the same exact Project and revalidated epoch.

**Dependencies.** `KVP-029`. Computed wave: `22`.

**Allowed reads.** `symbol/contract`, `symbol/service`, `symbol/intellij`, `runtime/ide-read`.

**Allowed writes.** `runtime/ide-read`, `symbol/intellij/src/test`, `integration-tests`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.029.proof`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-010`, `requirement:KVP-REQ-015`.

**Outputs.** `kvp.030.proof` at `build/reports/ide-hosted/KVP-030-resolve.json`.

**Public interface.** `symbol.resolve`.

**Internal implementation.** Candidate selector to exact selector proof transition.

**Effect and cost.** `NATIVE_INDEX_READ`, `SEMANTIC_READ`; `SEMANTIC_READ`.

**Forbidden work.** Raw symbol string exact input; Overload guessing; Identity reconstruction by caller; Stale candidate acceptance.

**RED.** `./gradlew ideHostedSymbolResolveNegativeProof`. Expected failure: Same-name or overload fixtures can misselect or stale candidates remain valid.

**GREEN.** `./gradlew ideHostedSymbolResolveAcceptance`. Expected proof: Every admitted candidate resolves to the same declaration or one closed failure.

**Review boundary.** Only symbol.resolve routing.

**Completion receipt.** `KVP-030-COMPLETE` at `build/reports/delivery/receipts/KVP-030-COMPLETE.receipt.json`. It consumes `KVP-030-RED`, `KVP-030-GREEN`, and all predecessor completion receipts.

### KVP-031: Route exact symbol description

**Goal.** Describe the exact selector and detach its canonical definition within one cancellable epoch-checked read.

**Dependencies.** `KVP-030`. Computed wave: `23`.

**Allowed reads.** `symbol/contract`, `symbol/service`, `symbol/intellij`, `runtime/ide-read`.

**Allowed writes.** `runtime/ide-read`, `symbol/intellij/src/test`, `integration-tests`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.030.proof`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-010`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-015`.

**Outputs.** `kvp.031.proof` at `build/reports/ide-hosted/KVP-031-describe.json`.

**Public interface.** `symbol.describe`.

**Internal implementation.** Exact selector to detached canonical description.

**Effect and cost.** `NATIVE_INDEX_READ`, `SEMANTIC_READ`; `SEMANTIC_READ`.

**Forbidden work.** PSI escape; Selector weakening; Second discovery call; Mixed-epoch projection.

**RED.** `./gradlew ideHostedSymbolDescribeNegativeProof`. Expected failure: Selector round-trip can change declaration or live PSI escapes.

**GREEN.** `./gradlew ideHostedSymbolDescribeAcceptance`. Expected proof: discover to resolve to describe preserves exact declaration and rejects movement.

**Review boundary.** Only symbol.describe routing.

**Completion receipt.** `KVP-031-COMPLETE` at `build/reports/delivery/receipts/KVP-031-COMPLETE.receipt.json`. It consumes `KVP-031-RED`, `KVP-031-GREEN`, and all predecessor completion receipts.

### KVP-011: Prove final plugin layout and classpath closure

**Goal.** After the read runtime and exact four operations exist, replace the transitional payload and reject every platform, bootstrap, mutation, topology, JDBC, runtime-acquisition, and process-launch artifact from the plugin ZIP and runtime classpath.

**Dependencies.** `KVP-010`, `KVP-025`, `KVP-031`. Computed wave: `24`.

**Allowed reads.** `ide-plugin/build.gradle.kts`, `ide-plugin/src`, `indexer/build.gradle.kts`, `runtime/ide-read`, `workspace/intellij-read`.

**Allowed writes.** `build-logic/src/main/kotlin/support/plugin/IdeHostedPluginArchiveFile.kt`, `build-logic/src/main/kotlin/support/plugin/IdeHostedPluginBytecodePolicy.kt`, `build-logic/src/main/kotlin/support/plugin/IdeHostedPluginLayout.kt`, `build-logic/src/main/kotlin/support/plugin/IdeHostedPluginLayoutFixtures.kt`, `build-logic/src/main/kotlin/support/plugin/IdeHostedPluginLayoutTasks.kt`, `ide-plugin/build.gradle.kts`, `ide-plugin/src/main/resources`, `ide-plugin/src/test`, `indexer/build.gradle.kts`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.010.proof`, `taskOutput:kvp.025.proof`, `taskOutput:kvp.031.proof`, `requirement:KVP-REQ-007`, `requirement:KVP-REQ-016`.

**Outputs.** `kvp.011.proof` at `ide-plugin/build/reports/KVP-011-layout.json`.

**Public interface.** `VerifiedIdePluginLayout`.

**Internal implementation.** Artifact inventory and bytecode owner report.

**Effect and cost.** `BUILD_POLICY_WRITE`, `FILESYSTEM_READ`; `PACKAGE_BUILD`.

**Forbidden work.** Size-only verification; Filename-only verification; Allowing hidden shaded platform classes.

**RED.** `./gradlew :ide-plugin:verifyPluginLayoutNegative`. Expected failure: Injected IntelliJ, Kotlin, Gradle, JBR, bootstrap, change, topology, JDBC, runtime-acquisition, or process-launch content is not detected.

**GREEN.** `./gradlew :ide-plugin:verifyPluginLayout`. Expected proof: Plugin contents and transitive classpath satisfy the read-only policy and size ceiling.

**Review boundary.** Read-only payload ownership, layout verifier, and plugin tests only.

**Completion receipt.** `KVP-011-COMPLETE` at `build/reports/delivery/receipts/KVP-011-COMPLETE.receipt.json`. It consumes `KVP-011-RED`, `KVP-011-GREEN`, `KVP-010-COMPLETE`, `KVP-025-COMPLETE`, and `KVP-031-COMPLETE`.

### KVP-032: Enforce static VFS-passive safety

**Goal.** Scan source, bytecode, classpath, and Gradle dependencies for every forbidden refresh, bootstrap, blocking-read, walk, listener, write, persistence, topology, runtime-acquisition, or process symbol.

**Dependencies.** `KVP-009`, `KVP-011`, `KVP-023`, `KVP-027`, `KVP-031`. Computed wave: `25`.

**Allowed reads.** `workspace/intellij-read`, `runtime/ide-read`, `ide-plugin`, `cli`, `build-logic/src/main/kotlin/support/architecture`.

**Allowed writes.** `build-logic/src/main/kotlin/support/tasks/VerifyVfsPassiveReadTask.kt`, `gradle/architecture/kast-architecture-policy.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.009.proof`, `taskOutput:kvp.011.proof`, `taskOutput:kvp.023.proof`, `taskOutput:kvp.027.proof`, `taskOutput:kvp.031.proof`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-014`, `requirement:KVP-REQ-016`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.032.proof` at `build/reports/ide-hosted/KVP-032-static-safety.json`.

**Public interface.** `VfsPassiveStaticProof`.

**Internal implementation.** Structured violations over exact consumer, symbol, owner, and enforcement rule.

**Effect and cost.** `BUILD_POLICY_WRITE`, `FILESYSTEM_READ`; `BUILD_POLICY`.

**Forbidden work.** Text-only grep as sole proof; Allowing refresh in helper module; Ignoring transitive classpath; Suppressing violation.

**RED.** `./gradlew verifyVfsPassiveReadNegative`. Expected failure: Injected forbidden call or classpath edge is not rejected.

**GREEN.** `./gradlew verifyVfsPassiveRead verifyKastModuleGraph verifyForbiddenEffects`. Expected proof: The complete hosted read graph has no forbidden effect path.

**Review boundary.** Static policy and negative fixtures only.

**Completion receipt.** `KVP-032-COMPLETE` at `build/reports/delivery/receipts/KVP-032-COMPLETE.receipt.json`. It consumes `KVP-032-RED`, `KVP-032-GREEN`, `KVP-009-COMPLETE`, `KVP-011-COMPLETE`, `KVP-023-COMPLETE`, `KVP-027-COMPLETE`, and `KVP-031-COMPLETE`.

### KVP-033: Prove dynamic VFS contention and movement safety

**Goal.** Stress concurrent reads, write-priority cancellation, dumb transitions, Project disposal, epoch movement, and a large VFS event storm while instrumenting every prohibited effect.

**Dependencies.** `KVP-022`, `KVP-025`, `KVP-031`, `KVP-032`. Computed wave: `26`.

**Allowed reads.** `acceptance/ide-hosted`, `integration-tests`, `fixtures`, `benchmarks`.

**Allowed writes.** `acceptance/ide-hosted`, `integration-tests`, `benchmarks/ide-hosted-vfs-passive.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.022.proof`, `taskOutput:kvp.025.proof`, `taskOutput:kvp.031.proof`, `taskOutput:kvp.032.proof`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-013`, `requirement:KVP-REQ-014`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-019`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.033.proof` at `build/reports/ide-hosted/KVP-033-vfs-safety.json`.

**Public interface.** `VfsPassiveDynamicProof`.

**Internal implementation.** Machine-readable counts, timings, cancellations, and stale-result outcomes.

**Effect and cost.** `INSTALLED_SYSTEM_EXECUTION`, `TEST_PROCESS_CONTROL`; `INSTALLED_ACCEPTANCE`.

**Forbidden work.** Latency-only inference; Mock-only process proof; Ignoring EDT work; Accepting stale result with limitation.

**RED.** `./gradlew ideHostedVfsSafetyNegativeProof`. Expected failure: Injected refresh, import, walk, blocking read, listener work, concurrent read, or stale acceptance is detected.

**GREEN.** `./gradlew ideHostedVfsSafetyAcceptance`. Expected proof: All prohibited counts are zero, concurrency is bounded, cancellations propagate, and stale results reject.

**Review boundary.** Dynamic safety fixtures and instrumentation only.

**Completion receipt.** `KVP-033-COMPLETE` at `build/reports/delivery/receipts/KVP-033-COMPLETE.receipt.json`. It consumes `KVP-033-RED`, `KVP-033-GREEN`, and all predecessor completion receipts.

### KVP-034: Prove the installed exact-read journey

**Goal.** Install the control command and plugin, open one fixture Project in supported IDEA, execute all four operations, and directly observe PID, processes, imports, refreshes, indexing cycles, exactness, and retirement.

**Dependencies.** `KVP-027`, `KVP-031`, `KVP-033`. Computed wave: `27`.

**Allowed reads.** `acceptance/ide-hosted`, `packaging`, `fixtures`, `benchmarks`, `cli`, `ide-plugin`.

**Allowed writes.** `acceptance/ide-hosted`, `packaging`, `build.gradle.kts`, `benchmarks/ide-hosted-installed.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.027.proof`, `taskOutput:kvp.031.proof`, `taskOutput:kvp.033.proof`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-010`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-013`, `requirement:KVP-REQ-014`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-019`, `requirement:KVP-REQ-020`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.034.proof` at `build/reports/ide-hosted/KVP-034-installed.json`.

**Public interface.** `InstalledIdeHostedAcceptance`.

**Internal implementation.** Installed process and semantic journey report.

**Effect and cost.** `INSTALLED_SYSTEM_EXECUTION`, `TEST_PROCESS_CONTROL`; `INSTALLED_ACCEPTANCE`.

**Forbidden work.** Development classpath; In-process fake transport; Assuming no second indexing from elapsed time; Skipping Project close.

**RED.** `./gradlew ideHostedInstalledNegativeProof`. Expected failure: Each injected second process, open, import, refresh, runtime read, misselection, or stale endpoint is detected.

**GREEN.** `./gradlew ideHostedInstalledExactReadAcceptance`. Expected proof: The installed journey proves the exact best-case reused-index path with direct counters.

**Review boundary.** Installed acceptance harness only.

**Completion receipt.** `KVP-034-COMPLETE` at `build/reports/delivery/receipts/KVP-034-COMPLETE.receipt.json`. It consumes `KVP-034-RED`, `KVP-034-GREEN`, and all predecessor completion receipts.

### KVP-035: Build the default control-plus-plugin release

**Goal.** Assemble deterministic control and plugin assets, bind their identities, and enforce the combined download ceiling without a semantic runtime asset.

**Dependencies.** `KVP-011`, `KVP-034`. Computed wave: `28`.

**Allowed reads.** `build.gradle.kts`, `distribution`, `ide-plugin`, `cli`, `.github/scripts/release`, `packaging`.

**Allowed writes.** `distribution/release`, `build.gradle.kts`, `.github/scripts/release`, `packaging`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.011.proof`, `taskOutput:kvp.034.proof`, `requirement:KVP-REQ-007`, `requirement:KVP-REQ-021`.

**Outputs.** `kvp.035.proof` at `build/reports/ide-hosted/KVP-035-release.json`.

**Public interface.** `DefaultIdeHostedRelease`.

**Internal implementation.** Manifest with exactly control and plugin assets and their digests.

**Effect and cost.** `PACKAGE_WRITE`, `FILESYSTEM_READ`; `PACKAGE_BUILD`.

**Forbidden work.** Runtime archive in manifest; Private idea-home; Platform JAR in plugin; Unbound plugin/control versions.

**RED.** `./gradlew verifyIdeHostedReleaseNegative`. Expected failure: Injected runtime asset, platform payload, version mismatch, or size excess is not rejected.

**GREEN.** `./gradlew assembleIdeHostedRelease verifyIdeHostedRelease`. Expected proof: Release contains exactly two matched assets and stays at or below 80 MiB.

**Review boundary.** Release assembly and validation only.

**Completion receipt.** `KVP-035-COMPLETE` at `build/reports/delivery/receipts/KVP-035-COMPLETE.receipt.json`. It consumes `KVP-035-RED`, `KVP-035-GREEN`, and all predecessor completion receipts.

### KVP-036: Remove the isolated runtime from the default product

**Goal.** Remove semantic-runtime download, manifest acquisition, runtime store, private idea-home installation, and automatic isolated host authority from installer and release workflows.

**Dependencies.** `KVP-027`, `KVP-035`. Computed wave: `29`.

**Allowed reads.** `install.sh`, `distribution/managed`, `indexer`, `build.gradle.kts`, `.github`, `packaging`, `docs`.

**Allowed writes.** `install.sh`, `build.gradle.kts`, `.github`, `packaging`, `docs`, `gradle/architecture/kast-architecture-policy.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.027.proof`, `taskOutput:kvp.035.proof`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-007`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-021`.

**Outputs.** `kvp.036.proof` at `build/reports/ide-hosted/KVP-036-retirement.json`.

**Public interface.** `RetiredDefaultIsolatedRuntime`.

**Internal implementation.** Retirement receipt over removed default assets, commands, and effects.

**Effect and cost.** `PACKAGE_WRITE`, `BUILD_POLICY_WRITE`; `PACKAGE_BUILD`.

**Forbidden work.** Deleting explicit test fixture before replacement proof; Leaving hidden environment fallback; Keeping archive in latest release; Documenting unsupported fallback.

**RED.** `./gradlew verifyNoDefaultIsolatedRuntimeNegative`. Expected failure: Any installer, manifest, release, CLI, or automatic runtime path remains reachable.

**GREEN.** `./gradlew verifyNoDefaultIsolatedRuntime verifyIdeHostedRelease`. Expected proof: Default installation and release have no isolated runtime authority or payload.

**Review boundary.** Default product path only; explicit non-default fixture may remain labeled and unreachable.

**Completion receipt.** `KVP-036-COMPLETE` at `build/reports/delivery/receipts/KVP-036-COMPLETE.receipt.json`. It consumes `KVP-036-RED`, `KVP-036-GREEN`, and all predecessor completion receipts.

### KVP-037: Prove failure, corruption, and unsupported-operation behavior

**Goal.** Exercise missing plugin, missing Project, malformed descriptor, incompatible builds, wrong root, stale PID, occupied socket, Project close, and every unsupported operation.

**Dependencies.** `KVP-025`, `KVP-026`, `KVP-027`, `KVP-031`, `KVP-036`. Computed wave: `30`.

**Allowed reads.** `acceptance/ide-hosted`, `fixtures`, `protocol/wire`, `cli`, `ide-plugin`.

**Allowed writes.** `acceptance/ide-hosted`, `integration-tests`, `benchmarks/ide-hosted-failures.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.025.proof`, `taskOutput:kvp.026.proof`, `taskOutput:kvp.027.proof`, `taskOutput:kvp.031.proof`, `taskOutput:kvp.036.proof`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-018`, `requirement:KVP-REQ-019`, `requirement:KVP-REQ-020`.

**Outputs.** `kvp.037.proof` at `build/reports/ide-hosted/KVP-037-failure-matrix.json`.

**Public interface.** `IdeHostedFailureMatrix`.

**Internal implementation.** Closed failure matrix with no fallback or ambiguous transport success.

**Effect and cost.** `INSTALLED_SYSTEM_EXECUTION`, `TEST_PROCESS_CONTROL`; `INSTALLED_ACCEPTANCE`.

**Forbidden work.** Generic unknown failure; Automatic fallback; Treating unsupported operation as transport success; Deleting occupied non-owned path.

**RED.** `./gradlew ideHostedFailureMatrixNegative`. Expected failure: One corruption or unsupported state escapes admission or reaches semantic dispatch.

**GREEN.** `./gradlew ideHostedFailureMatrixAcceptance`. Expected proof: Every state maps to one closed failure and all owned lifecycle artifacts retire safely.

**Review boundary.** Failure and corruption fixtures only.

**Completion receipt.** `KVP-037-COMPLETE` at `build/reports/delivery/receipts/KVP-037-COMPLETE.receipt.json`. It consumes `KVP-037-RED`, `KVP-037-GREEN`, and all predecessor completion receipts.

### KVP-038: Prove a detached clean checkout

**Goal.** Create a detached checkout at the exact head, regenerate projections, build assets, run structural gates, and execute installed acceptance without local caches or untracked files.

**Dependencies.** `KVP-008`, `KVP-036`, `KVP-037`. Computed wave: `31`.

**Allowed reads.** `build-logic`, `gradle/delivery`, `packaging`, `acceptance/ide-hosted`, `.github`.

**Allowed writes.** `build-logic/src/main/kotlin/support/delivery/gradle`, `packaging`, `build/reports/ide-hosted`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.008.proof`, `taskOutput:kvp.036.proof`, `taskOutput:kvp.037.proof`, `requirement:KVP-REQ-003`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-022`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.038.proof` at `build/reports/ide-hosted/KVP-038-clean-checkout.json`.

**Public interface.** `CleanCheckoutReceipt`.

**Internal implementation.** Detached checkout command, input, artifact, and installed proof digests.

**Effect and cost.** `TEST_PROCESS_CONTROL`, `INSTALLED_SYSTEM_EXECUTION`, `BUILD_POLICY_WRITE`; `INSTALLED_ACCEPTANCE`.

**Forbidden work.** Using current worktree output; Using Gradle task result from another head; Skipping projection diff; Depending on untracked fixture.

**RED.** `./gradlew cleanCheckoutNegativeProof`. Expected failure: A changed untracked input or stale generated artifact can pass.

**GREEN.** `./gradlew ideHostedCleanCheckoutAcceptance`. Expected proof: A detached exact-head checkout regenerates identical projections and passes all required gates.

**Review boundary.** Clean-checkout harness only.

**Completion receipt.** `KVP-038-COMPLETE` at `build/reports/delivery/receipts/KVP-038-COMPLETE.receipt.json`. It consumes `KVP-038-RED`, `KVP-038-GREEN`, and all predecessor completion receipts.

### KVP-039: Bind exact-head CI to the receipt closure

**Goal.** Run the required program, architecture, release, installed, and clean-checkout gates at pull-request head and reject merge evidence from any other SHA.

**Dependencies.** `KVP-038`. Computed wave: `32`.

**Allowed reads.** `.github/workflows`, `build-logic`, `gradle/delivery`, `packaging`.

**Allowed writes.** `.github/workflows/ci.yml`, `.github/scripts/release`, `build-logic/src/main/kotlin/support/delivery`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.038.proof`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-022`, `requirement:KVP-REQ-023`.

**Outputs.** `kvp.039.proof` at `build/reports/ide-hosted/KVP-039-exact-head-ci.json`.

**Public interface.** `ExactHeadCiReceipt`.

**Internal implementation.** CI receipt join bound to pull-request head SHA.

**Effect and cost.** `BUILD_POLICY_WRITE`, `TEST_PROCESS_CONTROL`; `INSTALLED_ACCEPTANCE`.

**Forbidden work.** Binding merge commit instead of PR head; Reusing release receipt; CI status without receipt digest; Skipping clean checkout.

**RED.** `./gradlew exactHeadCiNegativeProof`. Expected failure: A receipt from a parent, merge commit, or changed command can satisfy the required check.

**GREEN.** `./gradlew verifyExactHeadCiContract`. Expected proof: Required CI joins only exact-head admitted receipts.

**Review boundary.** CI workflow and contract tests only.

**Completion receipt.** `KVP-039-COMPLETE` at `build/reports/delivery/receipts/KVP-039-COMPLETE.receipt.json`. It consumes `KVP-039-RED`, `KVP-039-GREEN`, and all predecessor completion receipts.

### KVP-040: Perform an independent full-diff review

**Goal.** Review the actual exact-head diff, generated projections, schemas, module edges, forbidden effects, public behavior, and installed evidence and emit structured findings.

**Dependencies.** `KVP-039`. Computed wave: `33`.

**Allowed reads.** `git diff`, `gradle/delivery`, `build/reports/ide-hosted`, `build-logic`, `ide-plugin`, `runtime/ide-read`, `workspace/intellij-read`, `cli`, `distribution/release`.

**Allowed writes.** `build/reports/ide-hosted/final-review.json`, `build/reports/ide-hosted/final-review.md`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.039.proof`, `requirement:KVP-REQ-024`.

**Outputs.** `kvp.040.proof` at `build/reports/ide-hosted/KVP-040-review.json`.

**Public interface.** `IndependentReviewReceipt`.

**Internal implementation.** Structured findings with severity, location, validity, and required gate reruns.

**Effect and cost.** `FILESYSTEM_READ`, `REVIEW`; `REVIEW`.

**Forbidden work.** Self-attestation without diff; Ignoring generated changes; Unstructured prose-only review; Marking finding resolved without evidence.

**RED.** `./gradlew finalReviewNegativeProof`. Expected failure: Injected forbidden path, stale receipt, or unsupported claim is not found.

**GREEN.** `./gradlew ideHostedFinalDiffReview`. Expected proof: Review covers the exact diff and emits a complete structured finding set.

**Review boundary.** Review outputs only; no implementation edits.

**Completion receipt.** `KVP-040-COMPLETE` at `build/reports/delivery/receipts/KVP-040-COMPLETE.receipt.json`. It consumes `KVP-040-RED`, `KVP-040-GREEN`, and all predecessor completion receipts.

### KVP-041: Resolve every valid review finding and rerun affected gates

**Goal.** Apply only valid review corrections, record finding disposition, invalidate affected receipts, and rerun every dependent gate at the new exact head.

**Dependencies.** `KVP-040`. Computed wave: `34`.

**Allowed reads.** `build/reports/ide-hosted/final-review.json`, `repository paths named by findings`, `build/reports/delivery/receipts`.

**Allowed writes.** `repository paths named by valid findings`, `build/reports/ide-hosted/review-resolution.json`, `build/reports/delivery/receipts`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.040.proof`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-024`.

**Outputs.** `kvp.041.proof` at `build/reports/ide-hosted/KVP-041-review-resolution.json`.

**Public interface.** `ReviewResolutionReceipt`.

**Internal implementation.** Finding-to-edit-to-rerun trace with no unresolved valid finding.

**Effect and cost.** `BUILD_POLICY_WRITE`, `REVIEW`; `REVIEW`.

**Forbidden work.** Blanket cleanup; Ignoring low-severity valid finding; Keeping old receipts after edit; Changing scope without requirement update.

**RED.** `./gradlew reviewResolutionNegativeProof`. Expected failure: An unresolved valid finding or stale dependent receipt can pass.

**GREEN.** `./gradlew ideHostedReviewResolution`. Expected proof: Every valid finding is resolved and all invalidated gates are rerun at one exact head.

**Review boundary.** Only paths required by valid findings and proof artifacts.

**Completion receipt.** `KVP-041-COMPLETE` at `build/reports/delivery/receipts/KVP-041-COMPLETE.receipt.json`. It consumes `KVP-041-RED`, `KVP-041-GREEN`, and all predecessor completion receipts.

### KVP-042: Revalidate every original requirement point by point

**Goal.** Map each requirement to implementation location, enforcement mechanism, verification command, admitted evidence, and PASS; reject missing, qualified, or unsupported claims.

**Dependencies.** `KVP-001`, `KVP-041`. Computed wave: `35`.

**Allowed reads.** `gradle/delivery/kast-vfs-passive-requirements.json`, `build/reports/delivery/receipts`, `build/reports/ide-hosted`, `gradle/delivery/authority-sources/persisted-goal.txt`.

**Allowed writes.** `build/reports/ide-hosted/specification-revalidation.json`, `docs/plans/kast-vfs-passive-revalidation.md`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.001.proof`, `taskOutput:kvp.041.proof`, `requirement:KVP-REQ-001`, `requirement:KVP-REQ-002`, `requirement:KVP-REQ-003`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-005`, `requirement:KVP-REQ-006`, `requirement:KVP-REQ-007`, `requirement:KVP-REQ-008`, `requirement:KVP-REQ-009`, `requirement:KVP-REQ-010`, `requirement:KVP-REQ-011`, `requirement:KVP-REQ-012`, `requirement:KVP-REQ-013`, `requirement:KVP-REQ-014`, `requirement:KVP-REQ-015`, `requirement:KVP-REQ-016`, `requirement:KVP-REQ-017`, `requirement:KVP-REQ-018`, `requirement:KVP-REQ-019`, `requirement:KVP-REQ-020`, `requirement:KVP-REQ-021`, `requirement:KVP-REQ-022`, `requirement:KVP-REQ-023`, `requirement:KVP-REQ-024`, `requirement:KVP-REQ-025`, `requirement:KVP-REQ-026`, `requirement:KVP-REQ-027`.

**Outputs.** `kvp.042.proof` at `build/reports/ide-hosted/KVP-042-revalidation.json`.

**Public interface.** `RequirementRevalidationReceipt`.

**Internal implementation.** Complete requirement-to-proof matrix over the exact final head.

**Effect and cost.** `FILESYSTEM_READ`, `REVIEW`, `BUILD_POLICY_WRITE`; `REVIEW`.

**Forbidden work.** PASS without admitted evidence; Qualified treated as PASS; Requirement silently removed; Evidence from pre-review head.

**RED.** `./gradlew specificationRevalidationNegativeProof`. Expected failure: One omitted or qualified requirement can report success.

**GREEN.** `./gradlew ideHostedSpecificationRevalidation`. Expected proof: Every original requirement has one exact-head PASS entry and evidence digest.

**Review boundary.** Traceability and revalidation outputs only.

**Completion receipt.** `KVP-042-COMPLETE` at `build/reports/delivery/receipts/KVP-042-COMPLETE.receipt.json`. It consumes `KVP-042-RED`, `KVP-042-GREEN`, and all predecessor completion receipts.

### KVP-043: Derive BestCaseVfsPassiveReusedIndex completion

**Goal.** Consume the full exact-head receipt closure and issue the sole terminal proof that Kast uses the existing IDE process, Project, VFS snapshot, native index, and exact selector path without second indexing or hidden repair.

**Dependencies.** `KVP-008`, `KVP-034`, `KVP-036`, `KVP-037`, `KVP-038`, `KVP-039`, `KVP-041`, `KVP-042`. Computed wave: `36`.

**Allowed reads.** `build/reports/delivery/receipts`, `build/reports/ide-hosted`, `gradle/delivery/kast-vfs-passive-reused-index-program.json`.

**Allowed writes.** `build/reports/ide-hosted/best-case-vfs-passive-reused-index.receipt.json`.

**Inputs.** `baseline:CURRENT_HEAD`, `programAuthority:DELIVERY_AUTHORITY`, `taskOutput:kvp.008.proof`, `taskOutput:kvp.034.proof`, `taskOutput:kvp.036.proof`, `taskOutput:kvp.037.proof`, `taskOutput:kvp.038.proof`, `taskOutput:kvp.039.proof`, `taskOutput:kvp.041.proof`, `taskOutput:kvp.042.proof`, `requirement:KVP-REQ-004`, `requirement:KVP-REQ-020`, `requirement:KVP-REQ-023`, `requirement:KVP-REQ-024`, `requirement:KVP-REQ-025`, `requirement:KVP-REQ-026`.

**Outputs.** `kvp.043.proof` at `build/reports/ide-hosted/KVP-043-terminal.json`.

**Public interface.** `BestCaseVfsPassiveReusedIndex`.

**Internal implementation.** Derived terminal proof type; no constructor is exposed outside receipt admission.

**Effect and cost.** `FILESYSTEM_READ`, `BUILD_POLICY_WRITE`; `REVIEW`.

**Forbidden work.** Manual completion invocation without receipts; Missing installed metric; Unresolved finding; Non-PASS requirement; Receipt from another head.

**RED.** `./gradlew bestCaseVfsPassiveCompletionNegativeProof`. Expected failure: Any missing, stale, forged, qualified, or mismatched proof can derive completion.

**GREEN.** `./gradlew proveBestCaseVfsPassiveReusedIndex`. Expected proof: The exact terminal receipt derives only when every dependency, installed metric, review, and requirement proof passes at one head.

**Review boundary.** Terminal receipt only; no production edits.

**Completion receipt.** `KVP-043-COMPLETE` at `build/reports/delivery/receipts/KVP-043-COMPLETE.receipt.json`. It consumes `KVP-043-RED`, `KVP-043-GREEN`, and all predecessor completion receipts.

## Installed acceptance metrics

| Metric | Predicate | Value |
|---|---|---:|
| `endpoint.host.kind` | `equals` | `ide_project` |
| `endpoint.pid.matches.ide.pid` | `equals` | `true` |
| `spawned.indexer.process.count` | `equals` | `0` |
| `kast.caused.indexing.cycle.count` | `equals` | `0` |
| `runtime.archive.read.count` | `equals` | `0` |
| `semantic.runtime.asset.present` | `equals` | `false` |
| `private.idea.home.created` | `equals` | `false` |
| `project.open.call.count` | `equals` | `0` |
| `gradle.import.call.count` | `equals` | `0` |
| `vfs.refresh.call.count` | `equals` | `0` |
| `vfs.listener.semantic.job.count` | `equals` | `0` |
| `repository.walk.inside.read.count` | `equals` | `0` |
| `source.hash.inside.read.count` | `equals` | `0` |
| `blocking.read.action.call.count` | `equals` | `0` |
| `semantic.work.on.edt.count` | `equals` | `0` |
| `max.concurrent.kast.reads` | `atMost` | `1` |
| `max.queued.kast.reads` | `atMost` | `1` |
| `selector.round.trip` | `equals` | `true` |
| `wrong.symbol.selection.count` | `equals` | `0` |
| `stale.epoch.accepted.count` | `equals` | `0` |
| `endpoint.retired.on.project.close` | `equals` | `true` |
| `plugin.platform.jar.count` | `equals` | `0` |
| `plugin.bootstrap.class.count` | `equals` | `0` |
| `default.download.bytes` | `atMost` | `83886080` |
| `automatic.fallback.path.count` | `equals` | `0` |
| `unsupported.endpoint.accepted.count` | `equals` | `0` |

## Derived stopping condition

Success requires every task receipt at one exact head, the installed metrics above, the detached clean-checkout receipt, exact-head CI receipt, independent review receipt, review-resolution receipt, and a PASS entry for every requirement. Code volume, commits, elapsed time, and subjective confidence do not contribute to progression.
