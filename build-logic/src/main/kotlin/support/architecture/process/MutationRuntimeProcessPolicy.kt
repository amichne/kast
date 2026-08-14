package support.architecture.process

import support.architecture.ModuleId

enum class MutationRuntimeProcessId {
    RP01,
    RP02,
    RP03,
    RP04,
    RP05,
    RP06,
    RP07,
    RP08,
    RP09,
    RP10,
    RP11S,
    RP11E,
    RP12,
    RP13,
    RP14,
    RP15,
    RP16,
    RP17,
    RP18,
    RP19,
}

enum class MutationRuntimeEffect {
    NONE,
    PURE,
    READ,
    AUTHORITY,
    SOURCE_WRITE,
    DERIVED_WRITE,
    OBSERVATION,
}

data class MutationRuntimeProcessPolicy(
    val id: MutationRuntimeProcessId,
    val name: String,
    val admission: MutationRuntimeAdmission,
    val owners: Set<ModuleId>,
    val effects: Set<MutationRuntimeEffect>,
    val cost: String,
)

enum class MutationApplyLane(val processId: MutationRuntimeProcessId) {
    SEMANTIC(MutationRuntimeProcessId.RP11S),
    EXTERNAL(MutationRuntimeProcessId.RP11E),
}

enum class MutationRecoveryTerminal {
    ROLLED_BACK,
    RECOVERY_REQUIRED,
}

enum class MutationRuntimeTopologyFailure {
    SEMANTIC_APPLY_LANE_INVALID,
    EXTERNAL_APPLY_LANE_INVALID,
    SELECTED_APPLY_LANE_JOIN_REQUIRED,
    RECOVERY_PREPARATION_POINT_INVALID,
    POST_PREPARATION_RECOVERY_COVERAGE_INVALID,
    RECOVERY_TERMINALS_INVALID,
}

class ValidatedMutationRuntimeTopology internal constructor(
    val processes: Map<MutationRuntimeProcessId, MutationRuntimeProcessPolicy>,
)

sealed interface MutationRuntimeTopologyValidation {
    data class Valid(
        val topology: ValidatedMutationRuntimeTopology,
    ) : MutationRuntimeTopologyValidation

    data class Invalid(
        val failures: Set<MutationRuntimeTopologyFailure>,
    ) : MutationRuntimeTopologyValidation
}

sealed interface MutationRuntimeAdmission {
    val orderingDependencies: Set<MutationRuntimeProcessId>

    data object Entry : MutationRuntimeAdmission {
        override val orderingDependencies: Set<MutationRuntimeProcessId> = emptySet()
    }

    class After internal constructor(
        val predecessor: MutationRuntimeProcessId,
    ) : MutationRuntimeAdmission {
        override val orderingDependencies: Set<MutationRuntimeProcessId> = setOf(predecessor)
    }

    class ApplyLane internal constructor(
        val lane: MutationApplyLane,
    ) : MutationRuntimeAdmission {
        val predecessor: MutationRuntimeProcessId = MutationRuntimeProcessId.RP10
        override val orderingDependencies: Set<MutationRuntimeProcessId> =
            setOf(predecessor)
    }

    /**
     * Admits the common suffix after exactly the lane selected by the admitted plan completes.
     *
     * Both lane processes remain ordering predecessors so the projected policy stays acyclic;
     * they are alternatives, not an all-of completion requirement.
     */
    data object SelectedApplyLaneJoin : MutationRuntimeAdmission {
        val lanes: Set<MutationApplyLane> = MutationApplyLane.entries.toSet()
        override val orderingDependencies: Set<MutationRuntimeProcessId> =
            lanes.mapTo(linkedSetOf(), MutationApplyLane::processId)
    }

    /** Raw invalid-policy fixture representing an all-of join across alternative apply lanes. */
    data object AllApplyLanesJoin : MutationRuntimeAdmission {
        override val orderingDependencies: Set<MutationRuntimeProcessId> =
            MutationApplyLane.entries.mapTo(linkedSetOf(), MutationApplyLane::processId)
    }

    /**
     * Arms recovery when durable recovery preparation completes.
     *
     * This is an interrupt admission for every later failure, not a join over failure points.
     */
    class RecoveryInterruptAfterPreparation internal constructor(
        val preparedBy: MutationRuntimeProcessId,
        val failurePoints: Set<MutationRuntimeProcessId>,
        val terminalOutcomes: Set<MutationRecoveryTerminal>,
    ) : MutationRuntimeAdmission {
        override val orderingDependencies: Set<MutationRuntimeProcessId> = setOf(preparedBy)
    }
}

object KastMutationRuntimeProcesses {
    val postRecoveryPreparationFailurePoints: Set<MutationRuntimeProcessId> = linkedSetOf(
        MutationRuntimeProcessId.RP10,
        MutationRuntimeProcessId.RP11S,
        MutationRuntimeProcessId.RP11E,
        MutationRuntimeProcessId.RP12,
        MutationRuntimeProcessId.RP13,
        MutationRuntimeProcessId.RP14,
        MutationRuntimeProcessId.RP15,
        MutationRuntimeProcessId.RP16,
        MutationRuntimeProcessId.RP17,
        MutationRuntimeProcessId.RP18,
    )

    val all: List<MutationRuntimeProcessPolicy> = listOf(
        process(
            MutationRuntimeProcessId.RP01,
            "Parse mutation intent",
            MutationRuntimeAdmission.Entry,
            owners(ModuleId.PROTOCOL_REGISTRY, ModuleId.CHANGE_CONTRACT),
            effects(MutationRuntimeEffect.PURE),
            "METADATA"
        ),
        process(
            MutationRuntimeProcessId.RP02,
            "Acquire planning read lease",
            after(MutationRuntimeProcessId.RP01),
            owners(ModuleId.WORKSPACE_CONTRACT, ModuleId.WORKSPACE_SERVICE),
            effects(MutationRuntimeEffect.READ),
            "SEMANTIC_ADMISSION"
        ),
        process(
            MutationRuntimeProcessId.RP03,
            "Resolve exact target and affected semantic scope",
            after(MutationRuntimeProcessId.RP02),
            owners(ModuleId.CHANGE_PLAN_INTELLIJ),
            effects(MutationRuntimeEffect.READ),
            "SCOPED_SEMANTIC_READ / BOUNDED_RELATION_SEARCH"
        ),
        process(
            MutationRuntimeProcessId.RP04,
            "Capture stable preconditions and before-image identities",
            after(MutationRuntimeProcessId.RP03),
            owners(ModuleId.CHANGE_PLAN_SERVICE),
            effects(MutationRuntimeEffect.READ),
            "BOUNDED_FILE_OBSERVATION"
        ),
        process(
            MutationRuntimeProcessId.RP05,
            "Construct and persist stable plan",
            after(MutationRuntimeProcessId.RP04),
            owners(ModuleId.CHANGE_PLAN_SERVICE, ModuleId.CHANGE_JOURNAL_CONTRACT),
            effects(MutationRuntimeEffect.DERIVED_WRITE),
            "SMALL_DURABLE_WRITE"
        ),
        process(
            MutationRuntimeProcessId.RP06,
            "Await explicit approval",
            after(MutationRuntimeProcessId.RP05),
            owners(ModuleId.RUNTIME_SERVER),
            effects(MutationRuntimeEffect.NONE),
            "WAIT"
        ),
        process(
            MutationRuntimeProcessId.RP07,
            "Acquire one logical Kast mutation lease",
            after(MutationRuntimeProcessId.RP06),
            owners(ModuleId.WORKSPACE_MUTATION_SERVICE),
            effects(MutationRuntimeEffect.AUTHORITY),
            "CONCURRENCY_ADMISSION"
        ),
        process(
            MutationRuntimeProcessId.RP08,
            "Revalidate plan and exact identity",
            after(MutationRuntimeProcessId.RP07),
            owners(ModuleId.CHANGE_APPLY_SERVICE, ModuleId.CHANGE_PLAN_INTELLIJ),
            effects(MutationRuntimeEffect.READ),
            "SCOPED_SEMANTIC_READ"
        ),
        process(
            MutationRuntimeProcessId.RP09,
            "Prepare durable recovery evidence",
            after(MutationRuntimeProcessId.RP08),
            owners(ModuleId.CHANGE_RECOVERY_SERVICE, ModuleId.CHANGE_RECOVERY_FILESYSTEM),
            effects(MutationRuntimeEffect.DERIVED_WRITE),
            "BOUNDED_FILE_COPY"
        ),
        process(
            MutationRuntimeProcessId.RP10,
            "Prepare ephemeral apply context",
            after(MutationRuntimeProcessId.RP09),
            owners(ModuleId.CHANGE_APPLY_INTELLIJ, ModuleId.CHANGE_APPLY_FILESYSTEM),
            effects(MutationRuntimeEffect.READ),
            "SMALL_PREPARATION"
        ),
        applyLaneProcess(
            MutationApplyLane.SEMANTIC,
            "Apply semantic mutation through IntelliJ",
            owners(ModuleId.CHANGE_APPLY_INTELLIJ),
            effects(MutationRuntimeEffect.SOURCE_WRITE),
            "SHORT_EDT_WRITE_COMMAND"
        ),
        applyLaneProcess(
            MutationApplyLane.EXTERNAL,
            "Apply external file mutation",
            owners(ModuleId.CHANGE_APPLY_FILESYSTEM),
            effects(MutationRuntimeEffect.SOURCE_WRITE),
            "BOUNDED_DESCRIPTOR_RELATIVE_WRITE"
        ),
        process(
            MutationRuntimeProcessId.RP12,
            "Persist affected documents and capture after-images",
            MutationRuntimeAdmission.SelectedApplyLaneJoin,
            owners(ModuleId.CHANGE_APPLY_SERVICE),
            effects(MutationRuntimeEffect.SOURCE_WRITE, MutationRuntimeEffect.OBSERVATION),
            "BOUNDED_BY_DECLARED_WRITE_SET"
        ),
        process(
            MutationRuntimeProcessId.RP13,
            "Prove write-set closure",
            after(MutationRuntimeProcessId.RP12),
            owners(ModuleId.CHANGE_APPLY_SERVICE),
            effects(MutationRuntimeEffect.READ),
            "BOUNDED_DIFF"
        ),
        process(
            MutationRuntimeProcessId.RP14,
            "Request targeted workspace transition",
            after(MutationRuntimeProcessId.RP13),
            owners(ModuleId.WORKSPACE_SERVICE),
            effects(MutationRuntimeEffect.DERIVED_WRITE),
            "INCREMENTAL_RECONCILIATION"
        ),
        process(
            MutationRuntimeProcessId.RP15,
            "Publish resulting semantic generation",
            after(MutationRuntimeProcessId.RP14),
            owners(ModuleId.WORKSPACE_SERVICE, ModuleId.EVIDENCE_SQLITE),
            effects(MutationRuntimeEffect.DERIVED_WRITE),
            "INCREMENTAL_INDEX / ATOMIC_PUBLICATION"
        ),
        process(
            MutationRuntimeProcessId.RP16,
            "Evaluate diagnostics and semantic postconditions",
            after(MutationRuntimeProcessId.RP15),
            owners(ModuleId.CHANGE_VERIFY_INTELLIJ),
            effects(MutationRuntimeEffect.READ),
            "SCOPED_SMART_READ"
        ),
        process(
            MutationRuntimeProcessId.RP17,
            "Reconcile expected and observed result",
            after(MutationRuntimeProcessId.RP16),
            owners(ModuleId.CHANGE_VERIFY_SERVICE),
            effects(MutationRuntimeEffect.PURE),
            "BOUNDED_COMPARISON"
        ),
        process(
            MutationRuntimeProcessId.RP18,
            "Issue verified receipt and release recovery",
            after(MutationRuntimeProcessId.RP17),
            owners(ModuleId.CHANGE_VERIFY_SERVICE, ModuleId.CHANGE_JOURNAL_CONTRACT),
            effects(MutationRuntimeEffect.DERIVED_WRITE),
            "SMALL_DURABLE_WRITE"
        ),
        process(
            MutationRuntimeProcessId.RP19,
            "Rollback or retain recovery after failure",
            recoveryInterruptAfterPreparation(),
            owners(ModuleId.CHANGE_RECOVERY_SERVICE),
            effects(MutationRuntimeEffect.SOURCE_WRITE, MutationRuntimeEffect.DERIVED_WRITE),
            "FAILURE_DEPENDENT"
        ),
    )

    private fun process(
        id: MutationRuntimeProcessId,
        name: String,
        admission: MutationRuntimeAdmission,
        owners: Set<ModuleId>,
        effects: Set<MutationRuntimeEffect>,
        cost: String,
    ): MutationRuntimeProcessPolicy = MutationRuntimeProcessPolicy(id, name, admission, owners, effects, cost)

    private fun applyLaneProcess(
        lane: MutationApplyLane,
        name: String,
        owners: Set<ModuleId>,
        effects: Set<MutationRuntimeEffect>,
        cost: String,
    ): MutationRuntimeProcessPolicy =
        process(lane.processId, name, MutationRuntimeAdmission.ApplyLane(lane), owners, effects, cost)

    private fun after(predecessor: MutationRuntimeProcessId): MutationRuntimeAdmission =
        MutationRuntimeAdmission.After(predecessor)

    private fun recoveryInterruptAfterPreparation(): MutationRuntimeAdmission =
        MutationRuntimeAdmission.RecoveryInterruptAfterPreparation(
            preparedBy = MutationRuntimeProcessId.RP09,
            failurePoints = postRecoveryPreparationFailurePoints,
            terminalOutcomes = MutationRecoveryTerminal.entries.toSet(),
        )

    private fun owners(vararg ids: ModuleId): Set<ModuleId> = ids.toSet()

    private fun effects(vararg effects: MutationRuntimeEffect): Set<MutationRuntimeEffect> = effects.toSet()
}

object MutationRuntimeTopologyValidator {
    /**
     * Proof transition:
     * `Iterable<MutationRuntimeProcessPolicy> -> ValidatedMutationRuntimeTopology`.
     *
     * A valid result establishes exact alternative-lane admission, a selected-lane common suffix,
     * complete post-preparation recovery coverage, and the finite truthful recovery terminals.
     * [MutationRuntimeTopologyValidation.Invalid] retains the closed expected topology failures.
     * Raw process policies may be extracted only by the architecture-policy definition boundary.
     */
    fun validate(
        policies: Iterable<MutationRuntimeProcessPolicy>,
    ): MutationRuntimeTopologyValidation {
        val processes = policies.associateBy(MutationRuntimeProcessPolicy::id)
        val failures = buildSet {
            if (!processes.hasLaneAdmission(MutationApplyLane.SEMANTIC)) {
                add(MutationRuntimeTopologyFailure.SEMANTIC_APPLY_LANE_INVALID)
            }
            if (!processes.hasLaneAdmission(MutationApplyLane.EXTERNAL)) {
                add(MutationRuntimeTopologyFailure.EXTERNAL_APPLY_LANE_INVALID)
            }
            if (processes[MutationRuntimeProcessId.RP12]?.admission !==
                MutationRuntimeAdmission.SelectedApplyLaneJoin
            ) {
                add(MutationRuntimeTopologyFailure.SELECTED_APPLY_LANE_JOIN_REQUIRED)
            }
            val recovery = processes[MutationRuntimeProcessId.RP19]?.admission as?
                MutationRuntimeAdmission.RecoveryInterruptAfterPreparation
            if (recovery?.preparedBy != MutationRuntimeProcessId.RP09) {
                add(MutationRuntimeTopologyFailure.RECOVERY_PREPARATION_POINT_INVALID)
            }
            if (recovery?.failurePoints != KastMutationRuntimeProcesses.postRecoveryPreparationFailurePoints) {
                add(MutationRuntimeTopologyFailure.POST_PREPARATION_RECOVERY_COVERAGE_INVALID)
            }
            if (recovery?.terminalOutcomes != MutationRecoveryTerminal.entries.toSet()) {
                add(MutationRuntimeTopologyFailure.RECOVERY_TERMINALS_INVALID)
            }
        }
        return if (failures.isEmpty()) {
            MutationRuntimeTopologyValidation.Valid(
                ValidatedMutationRuntimeTopology(processes),
            )
        } else {
            MutationRuntimeTopologyValidation.Invalid(failures)
        }
    }

    private fun Map<MutationRuntimeProcessId, MutationRuntimeProcessPolicy>.hasLaneAdmission(
        lane: MutationApplyLane,
    ): Boolean =
        (get(lane.processId)?.admission as? MutationRuntimeAdmission.ApplyLane)?.lane == lane
}
