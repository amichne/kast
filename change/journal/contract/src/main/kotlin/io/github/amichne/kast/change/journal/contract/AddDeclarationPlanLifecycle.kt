package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class AddDeclarationPlanStage {
    AWAITING_APPROVAL,
    APPROVED,
    RECOVERY_PREPARED,
    APPLY_ADMITTED,
    APPLIED_UNVERIFIED,
}

enum class AddDeclarationPlanStateVersionFailure {
    NEGATIVE,
    EXHAUSTED,
}

@Serializable
@JvmInline
value class AddDeclarationPlanStateVersion private constructor(val value: Long) {
    /**
     * Proof transition:
     * `AddDeclarationPlanStateVersion -> Refinement<AddDeclarationPlanStateVersion,
     * AddDeclarationPlanStateVersionFailure>`.
     *
     * Establishes the exact next compare-and-set version. The closed expected failure is
     * `EXHAUSTED`; raw extraction is permitted only at the journal SQL boundary.
     */
    fun next(): Refinement<AddDeclarationPlanStateVersion, AddDeclarationPlanStateVersionFailure> =
        if (value == Long.MAX_VALUE) {
            Refinement.Rejected(AddDeclarationPlanStateVersionFailure.EXHAUSTED)
        } else {
            Refinement.Refined(AddDeclarationPlanStateVersion(value + 1))
        }

    companion object {
        fun initial(): AddDeclarationPlanStateVersion = AddDeclarationPlanStateVersion(0)

        /**
         * Proof transition:
         * `Long -> Refinement<AddDeclarationPlanStateVersion,
         * AddDeclarationPlanStateVersionFailure>`.
         *
         * Establishes a non-negative durable lifecycle version. The closed expected failure is
         * `NEGATIVE`; raw extraction is permitted only at the journal SQL boundary.
         */
        fun parse(
            raw: Long,
        ): Refinement<AddDeclarationPlanStateVersion, AddDeclarationPlanStateVersionFailure> =
            if (raw >= 0) {
                Refinement.Refined(AddDeclarationPlanStateVersion(raw))
            } else {
                Refinement.Rejected(AddDeclarationPlanStateVersionFailure.NEGATIVE)
            }
    }
}

@Serializable
@JvmInline
value class AddDeclarationPlanApprover private constructor(val value: String) {
    companion object {
        internal fun fromProvenRaw(raw: String): AddDeclarationPlanApprover =
            AddDeclarationPlanApprover(raw)
    }
}

@Serializable
@JvmInline
value class AddDeclarationPlanApprovalDigest private constructor(val value: String) {
    companion object {
        internal fun fromProvenRaw(raw: String): AddDeclarationPlanApprovalDigest =
            AddDeclarationPlanApprovalDigest(raw)
    }
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationPlanApprovalEvidence private constructor(
    val planId: AddDeclarationPlanId,
    val approvedBy: AddDeclarationPlanApprover,
    val evidenceSha256: AddDeclarationPlanApprovalDigest,
) {
    companion object {
        internal fun fromProvenRaw(
            planId: AddDeclarationPlanId,
            approvedBy: String,
            evidenceSha256: String,
        ): AddDeclarationPlanApprovalEvidence = AddDeclarationPlanApprovalEvidence(
            planId = planId,
            approvedBy = AddDeclarationPlanApprover.fromProvenRaw(approvedBy),
            evidenceSha256 = AddDeclarationPlanApprovalDigest.fromProvenRaw(evidenceSha256),
        )
    }
}

enum class AddDeclarationPlanApprovalEvidenceFailure {
    PLAN_ID_INVALID,
    APPROVER_INVALID,
    EVIDENCE_SHA256_INVALID,
}

data class RawAddDeclarationPlanApprovalEvidence(
    val planId: String,
    val approvedBy: String,
    val evidenceSha256: String,
) {
    /**
     * Proof transition:
     * `RawAddDeclarationPlanApprovalEvidence -> Refinement<AddDeclarationPlanApprovalEvidence,
     * AddDeclarationPlanApprovalEvidenceFailure>`.
     *
     * Establishes explicit canonical approval evidence bound to one opaque PlanId and one
     * non-blank approver. The closed expected failure is
     * `AddDeclarationPlanApprovalEvidenceFailure`; raw strings may be extracted only by transport
     * parsing or the journal record decoder.
     */
    fun refine(): Refinement<AddDeclarationPlanApprovalEvidence, AddDeclarationPlanApprovalEvidenceFailure> {
        val parsedPlanId = AddDeclarationPlanId.parse(planId).valueOrNull()
                           ?: return Refinement.Rejected(
                               AddDeclarationPlanApprovalEvidenceFailure.PLAN_ID_INVALID,
                           )
        if (approvedBy.isBlank() || approvedBy != approvedBy.trim() || approvedBy.any(Char::isISOControl)) {
            return Refinement.Rejected(AddDeclarationPlanApprovalEvidenceFailure.APPROVER_INVALID)
        }
        if (!LOWERCASE_SHA256.matches(evidenceSha256)) {
            return Refinement.Rejected(
                AddDeclarationPlanApprovalEvidenceFailure.EVIDENCE_SHA256_INVALID,
            )
        }
        return Refinement.Refined(
            AddDeclarationPlanApprovalEvidence.fromProvenRaw(
                planId = parsedPlanId,
                approvedBy = approvedBy,
                evidenceSha256 = evidenceSha256,
            ),
        )
    }
}

enum class AddDeclarationPlanApprovalFailure {
    PLAN_ID_MISMATCH,
    PRIOR_VERSION_MISMATCH,
    VERSION_EXHAUSTED,
}

enum class PersistedAddDeclarationPlanRestoreFailure {
    AWAITING_VERSION_INVALID,
    APPROVAL_INVALID,
    APPROVED_VERSION_INVALID,
}

@ConsistentCopyVisibility
data class ApproveAddDeclarationPlan private constructor(
    val planId: AddDeclarationPlanId,
    val expectedVersion: AddDeclarationPlanStateVersion,
    val evidence: AddDeclarationPlanApprovalEvidence,
) {
    companion object {
        /**
         * Proof transition:
         * PlanId, prior version, and approval evidence to
         * `Refinement<ApproveAddDeclarationPlan, AddDeclarationPlanApprovalFailure>`.
         *
         * Establishes that the explicit evidence is bound to the exact PlanId named by the CAS
         * command. The closed expected failure is `PLAN_ID_MISMATCH`; raw fields may be extracted
         * only by the journal adapter.
         */
        fun admit(
            planId: AddDeclarationPlanId,
            expectedVersion: AddDeclarationPlanStateVersion,
            evidence: AddDeclarationPlanApprovalEvidence,
        ): Refinement<ApproveAddDeclarationPlan, AddDeclarationPlanApprovalFailure> {
            if (evidence.planId != planId) {
                return Refinement.Rejected(AddDeclarationPlanApprovalFailure.PLAN_ID_MISMATCH)
            }
            if (expectedVersion.next() is Refinement.Rejected) {
                return Refinement.Rejected(AddDeclarationPlanApprovalFailure.VERSION_EXHAUSTED)
            }
            return Refinement.Refined(ApproveAddDeclarationPlan(planId, expectedVersion, evidence))
        }
    }
}

sealed interface PersistedAddDeclarationPlan {
    val plan: PlannedAddDeclaration
    val version: AddDeclarationPlanStateVersion
    val stage: AddDeclarationPlanStage

    @ConsistentCopyVisibility
    data class AwaitingApproval private constructor(
        override val plan: PlannedAddDeclaration,
        override val version: AddDeclarationPlanStateVersion,
    ) : PersistedAddDeclarationPlan {
        override val stage: AddDeclarationPlanStage = AddDeclarationPlanStage.AWAITING_APPROVAL

        companion object {
            internal fun fromPlan(plan: PlannedAddDeclaration): AwaitingApproval =
                AwaitingApproval(plan, AddDeclarationPlanStateVersion.initial())
        }
    }

    @ConsistentCopyVisibility
    data class Approved private constructor(
        override val plan: PlannedAddDeclaration,
        override val version: AddDeclarationPlanStateVersion,
        val priorStage: AddDeclarationPlanStage,
        val priorVersion: AddDeclarationPlanStateVersion,
        val approvalEvidence: AddDeclarationPlanApprovalEvidence,
    ) : PersistedAddDeclarationPlan {
        override val stage: AddDeclarationPlanStage = AddDeclarationPlanStage.APPROVED

        companion object {
            internal fun fromTransition(
                prior: AwaitingApproval,
                version: AddDeclarationPlanStateVersion,
                evidence: AddDeclarationPlanApprovalEvidence,
            ): Approved = Approved(
                plan = prior.plan,
                version = version,
                priorStage = prior.stage,
                priorVersion = prior.version,
                approvalEvidence = evidence,
            )
        }
    }

    companion object {
        /**
         * Proof transition: `PlannedAddDeclaration -> AwaitingApproval`.
         *
         * Establishes the canonical initial durable lifecycle state at version zero. There is no
         * expected failure because `PlannedAddDeclaration` already carries canonical identity and
         * complete detached evidence; raw plan bytes remain confined to the journal adapter.
         */
        fun awaitingApproval(plan: PlannedAddDeclaration): AwaitingApproval =
            AwaitingApproval.fromPlan(plan)

        /**
         * Proof transition:
         * detached plan plus stored awaiting version to
         * `Refinement<AwaitingApproval, PersistedAddDeclarationPlanRestoreFailure>`.
         *
         * Establishes the only KIP-032 awaiting state: the canonical plan at initial version. The
         * closed expected failure is `AWAITING_VERSION_INVALID`; raw storage values may be
         * extracted only by the journal adapter.
         */
        fun restoreAwaiting(
            plan: PlannedAddDeclaration,
            version: AddDeclarationPlanStateVersion,
        ): Refinement<AwaitingApproval, PersistedAddDeclarationPlanRestoreFailure> =
            if (version == AddDeclarationPlanStateVersion.initial()) {
                Refinement.Refined(AwaitingApproval.fromPlan(plan))
            } else {
                Refinement.Rejected(
                    PersistedAddDeclarationPlanRestoreFailure.AWAITING_VERSION_INVALID,
                )
            }

        /**
         * Proof transition:
         * detached plan, stored versions, and approval evidence to
         * `Refinement<Approved, PersistedAddDeclarationPlanRestoreFailure>`.
         *
         * Replays the exact awaiting-to-approved transition and proves the stored current version,
         * prior version, and PlanId-bound evidence agree. The closed expected failure is
         * `PersistedAddDeclarationPlanRestoreFailure`; raw storage values may be extracted only by
         * the journal adapter.
         */
        fun restoreApproved(
            plan: PlannedAddDeclaration,
            currentVersion: AddDeclarationPlanStateVersion,
            priorVersion: AddDeclarationPlanStateVersion,
            evidence: AddDeclarationPlanApprovalEvidence,
        ): Refinement<Approved, PersistedAddDeclarationPlanRestoreFailure> {
            val prior = restoreAwaiting(plan, priorVersion).valueOrNull()
                        ?: return Refinement.Rejected(
                            PersistedAddDeclarationPlanRestoreFailure.AWAITING_VERSION_INVALID,
                        )
            val command = ApproveAddDeclarationPlan.admit(
                planId = plan.planId,
                expectedVersion = priorVersion,
                evidence = evidence,
            ).valueOrNull() ?: return Refinement.Rejected(
                PersistedAddDeclarationPlanRestoreFailure.APPROVAL_INVALID,
            )
            val restored = approve(prior, command).valueOrNull()
                           ?: return Refinement.Rejected(
                               PersistedAddDeclarationPlanRestoreFailure.APPROVAL_INVALID,
                           )
            return if (restored.version == currentVersion) {
                Refinement.Refined(restored)
            } else {
                Refinement.Rejected(
                    PersistedAddDeclarationPlanRestoreFailure.APPROVED_VERSION_INVALID,
                )
            }
        }

        /**
         * Proof transition:
         * awaiting plan plus approved CAS command to
         * `Refinement<Approved, AddDeclarationPlanApprovalFailure>`.
         *
         * Establishes an exact one-version transition for the same PlanId and prior version. The
         * closed expected failure is `AddDeclarationPlanApprovalFailure`; no raw state is exposed
         * outside the journal adapter.
         */
        fun approve(
            prior: AwaitingApproval,
            command: ApproveAddDeclarationPlan,
        ): Refinement<Approved, AddDeclarationPlanApprovalFailure> {
            if (command.planId != prior.plan.planId) {
                return Refinement.Rejected(AddDeclarationPlanApprovalFailure.PLAN_ID_MISMATCH)
            }
            if (command.expectedVersion != prior.version) {
                return Refinement.Rejected(AddDeclarationPlanApprovalFailure.PRIOR_VERSION_MISMATCH)
            }
            val next = command.expectedVersion.next().valueOrNull()
                       ?: return Refinement.Rejected(AddDeclarationPlanApprovalFailure.VERSION_EXHAUSTED)
            return Refinement.Refined(Approved.fromTransition(prior, next, command.evidence))
        }
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
