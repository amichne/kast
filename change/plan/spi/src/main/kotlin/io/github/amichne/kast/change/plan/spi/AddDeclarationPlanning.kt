package io.github.amichne.kast.change.plan.spi

import io.github.amichne.kast.change.contract.AddDeclarationIntent
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.PlannedAddDeclaration

enum class AddDeclarationPlanningLimitation {
    PROJECT_MODEL_INCOMPLETE,
    GENERATED_SOURCE_READ_ONLY,
    SOURCE_PROVENANCE_UNKNOWN,
    OUTSIDE_WORKSPACE_AUTHORITY,
    HARD_EXCLUDED_MUTATION_TARGET,
    SOURCE_OWNER_UNPROVEN,
    SOURCE_OWNER_AMBIGUOUS,
    TARGET_PARENT_MISSING,
    TARGET_ALREADY_EXISTS,
    TARGET_FILE_MISSING,
    TARGET_FILE_HASH_CHANGED,
    TARGET_NOT_KOTLIN_SOURCE,
    MODULE_CONTEXT_ANCHOR_UNAVAILABLE,
    PROPOSED_SYNTAX_INVALID,
    ZERO_DECLARATIONS,
    MULTIPLE_DECLARATIONS,
    UNSUPPORTED_TOP_LEVEL_DECLARATION,
    COMPILER_COLLISION_SCOPE_INCOMPLETE,
    DECLARATION_COLLISION,
    OUTBOUND_REFERENCE_UNRESOLVED,
    OUTBOUND_REFERENCE_MISMATCH,
    OVERLOAD_AMBIGUOUS,
    REBINDING_SCOPE_INCOMPLETE,
    IMPLICIT_LOOKUP_UNACCOUNTED,
    JAVA_REBINDING_UNPROVEN,
    GENERATION_CHANGED,
    PROJECT_MODEL_CHANGED,
    CLASSPATH_CHANGED,
    SOURCE_CONTEXT_CHANGED,
    FILE_BOTTOM_UNAVAILABLE,
    NEWLINE_POLICY_UNPROVEN,
    POSTIMAGE_MISMATCH,
    EVIDENCE_CONTRACT_INVALID,
    EVIDENCE_INTENT_MISMATCH,
}

@ConsistentCopyVisibility
data class AddDeclarationPlanningRejection private constructor(
    val limitations: List<AddDeclarationPlanningLimitation>,
) {
    init {
        require(limitations.isNotEmpty())
        require(limitations == limitations.distinct().sortedBy(AddDeclarationPlanningLimitation::ordinal))
    }

    companion object {
        /**
         * Proof transition: one required `AddDeclarationPlanningLimitation` plus optional additional
         * limitations to `AddDeclarationPlanningRejection`.
         *
         * Establishes a non-empty, duplicate-free, declaration-ordered closed failure value. There
         * is no expected failure because the first limitation is required at the type boundary.
         */
        fun of(
            first: AddDeclarationPlanningLimitation,
            vararg additional: AddDeclarationPlanningLimitation,
        ): AddDeclarationPlanningRejection = AddDeclarationPlanningRejection(
            (listOf(first) + additional)
                .toSet()
                .sortedBy(AddDeclarationPlanningLimitation::ordinal),
        )
    }
}

sealed interface AddDeclarationEvidenceResult {
    data class Proven(
        val evidence: AddDeclarationPlanningEvidence,
    ) : AddDeclarationEvidenceResult

    data class Rejected(
        val rejection: AddDeclarationPlanningRejection,
    ) : AddDeclarationEvidenceResult
}

fun interface AddDeclarationPlanningEvidenceSource {
    /**
     * Proof transition:
     * AddDeclarationIntent to AddDeclarationEvidenceResult.
     *
     * A proven result establishes only detached compiler, project-model, provenance, exact-file,
     * generation, and expected-delta facts. Expected failure is closed by
     * AddDeclarationPlanningRejection. Live host values must be consumed before return.
     */
    suspend fun evidence(intent: AddDeclarationIntent): AddDeclarationEvidenceResult
}

sealed interface AddDeclarationPlanningResult {
    data class Planned(
        val plan: PlannedAddDeclaration,
    ) : AddDeclarationPlanningResult

    data class Rejected(
        val rejection: AddDeclarationPlanningRejection,
    ) : AddDeclarationPlanningResult
}

fun interface AddDeclarationPlanner {
    /**
     * Proof transition:
     * AddDeclarationIntent to AddDeclarationPlanningResult.
     *
     * A planned result establishes one canonical, detached, generation-bound add-declaration plan
     * without mutation authority. Expected failure is closed by AddDeclarationPlanningRejection.
     * Raw fields may be extracted only by an injected physical evidence source.
     */
    suspend fun plan(intent: AddDeclarationIntent): AddDeclarationPlanningResult
}
