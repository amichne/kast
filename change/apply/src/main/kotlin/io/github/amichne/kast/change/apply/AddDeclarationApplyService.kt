package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOperationFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryPreparation
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryPreparationFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AppliedAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.recovery.PreparedAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.RecordAppliedAddDeclarationResult
import io.github.amichne.kast.change.recovery.RecoveryRequiredEvidence
import io.github.amichne.kast.change.recovery.UndurableRecoveryRequirement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

/** Finite service-level failures before a truthful terminal apply state exists. */
sealed interface AddDeclarationApplyFailure {
    data class Observation(
        val failure: SourceObservationFailure,
    ) : AddDeclarationApplyFailure

    data class Admission(
        val failure: MutationAdmissionFailure,
    ) : AddDeclarationApplyFailure

    data class RecoveryPreparation(
        val failure: AddDeclarationRecoveryPreparationFailure,
    ) : AddDeclarationApplyFailure

    data class RecoveryEvidence(
        val failure: AddDeclarationRecoveryOperationFailure,
    ) : AddDeclarationApplyFailure

    data class Write(
        val failure: SourceWriteFailure,
    ) : AddDeclarationApplyFailure
}

/** Closed KCS-017 apply states; only KCS-018 can refine [AppliedUnverified] further. */
sealed interface AddDeclarationApplyResult {
    data class Rejected(
        val failure: AddDeclarationApplyFailure,
    ) : AddDeclarationApplyResult

    data class RolledBack(
        val failure: SourceWriteFailure,
        val evidence: AddDeclarationRecoveryOutcome.RolledBack,
    ) : AddDeclarationApplyResult

    data class RecoveryRequired(
        val failure: SourceWriteFailure,
        val evidence: RecoveryRequiredEvidence,
    ) : AddDeclarationApplyResult
}

/**
 * Exact physically applied source state that deliberately carries no semantic-success proof.
 */
class AppliedUnverified private constructor(
    val planId: AddDeclarationPlanId,
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val priorLease: SemanticReadLease,
    val postimage: WorkspaceSourceContentHash,
    internal val recovery: AppliedAddDeclarationRecovery,
) : AddDeclarationApplyResult {
    companion object {
        /**
         * Proof transition: `(MutationAuthority, AppliedSourceWrite,
         * AppliedAddDeclarationRecovery) -> AppliedUnverified`.
         *
         * Establishes exact singleton physical application durably chained to its preimage while
         * preserving the prior generation that must not be treated as verification. There is no
         * expected failure because the service admits only matching proofs. Raw content extraction
         * is prohibited; KCS-018 may consume only the retained typed identities and obligations.
         */
        internal fun issue(
            authority: MutationAuthority,
            write: AppliedSourceWrite,
            recovery: AppliedAddDeclarationRecovery,
        ): AppliedUnverified = AppliedUnverified(
            authority.planId,
            authority.source,
            authority.priorLease,
            write.content,
            recovery,
        )
    }
}

/** Public `change.apply` boundary for the one KCS-017 AddDeclaration mutation. */
fun interface AddDeclarationApplyOperations {
    /**
     * Proof transition: `AddDeclarationApplyRequest -> AddDeclarationApplyResult`.
     *
     * Returns [AppliedUnverified] only after exact admission, durable pre-write evidence, one
     * authority-bound source write, durable applied evidence, and exact physical observation.
     * Expected failures are closed by [AddDeclarationApplyResult]. Raw source and platform values
     * remain inside the injected physical boundaries.
     */
    fun apply(request: AddDeclarationApplyRequest): AddDeclarationApplyResult
}

/** Host-neutral KCS-017 mutation coordinator. */
class AddDeclarationApplyService private constructor(
    private val recovery: AddDeclarationRecoveryService,
    private val observer: AddDeclarationSourceObserver,
    private val writer: AddDeclarationSourceWriter,
    private val rollback: AddDeclarationSourceRollback,
    private val admission: MutationAdmissionService,
) : AddDeclarationApplyOperations {
    constructor(
        recovery: AddDeclarationRecoveryService,
        observer: AddDeclarationSourceObserver,
        writer: AddDeclarationSourceWriter,
        rollback: AddDeclarationSourceRollback,
    ) : this(recovery, observer, writer, rollback, MutationAdmissionService())

    override fun apply(request: AddDeclarationApplyRequest): AddDeclarationApplyResult {
        val observed = when (val result = observer.observe(request.plan.target.file)) {
            is SourceObservationResult.Observed -> result.source
            is SourceObservationResult.Rejected -> return AddDeclarationApplyResult.Rejected(
                AddDeclarationApplyFailure.Observation(result.failure),
            )
        }
        val admitted = when (val result = admission.admit(request, observed)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return AddDeclarationApplyResult.Rejected(
                AddDeclarationApplyFailure.Admission(result.failure),
            )
        }
        val recoveryInput = when (val result = AddDeclarationRecoveryPreparation.fromPlan(
            request.plan,
            observed.recoveryPreimage,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return AddDeclarationApplyResult.Rejected(
                AddDeclarationApplyFailure.RecoveryPreparation(result.failure),
            )
        }
        val prepared = when (val result = recovery.prepare(recoveryInput)) {
            is PrepareAddDeclarationRecoveryResult.Prepared -> result.recovery
            is PrepareAddDeclarationRecoveryResult.Rejected ->
                return AddDeclarationApplyResult.Rejected(
                    AddDeclarationApplyFailure.RecoveryEvidence(result.failure),
                )
        }
        val authority = MutationAuthority.issue(admitted, prepared)
        val durability = RecoveryDurabilityBarrier(recovery, prepared)
        return resolveWrite(
            authority,
            durability,
            writer.write(authority, durability),
        )
    }

    private fun resolveWrite(
        authority: MutationAuthority,
        durability: RecoveryDurabilityBarrier,
        result: SourceWriteResult,
    ): AddDeclarationApplyResult = when (result) {
        is SourceWriteResult.Applied -> when (val state = durability.current()) {
            is ApplyDurabilityState.Durable ->
                if (result.write.authority === authority) {
                    AppliedUnverified.issue(authority, result.write, state.recovery)
                } else {
                    recoverAfterFault(authority, SourceWriteFailure.OBSERVATION_FAILED)
                }
            ApplyDurabilityState.Awaiting,
            is ApplyDurabilityState.Rejected,
                -> undurable(authority, SourceWriteFailure.DURABILITY_REJECTED)
        }
        is SourceWriteResult.RejectedBeforeMutation ->
            resolveRejected(authority, durability.current(), result.failure)
        is SourceWriteResult.RejectedAfterRollback ->
            resolveRejected(authority, durability.current(), result.failure)
        is SourceWriteResult.RecoveryRequired -> when (durability.current()) {
            is ApplyDurabilityState.Durable -> recoverAfterFault(authority, result.failure)
            ApplyDurabilityState.Awaiting,
            is ApplyDurabilityState.Rejected,
                -> undurable(authority, result.failure)
        }
    }

    private fun resolveRejected(
        authority: MutationAuthority,
        state: ApplyDurabilityState,
        failure: SourceWriteFailure,
    ): AddDeclarationApplyResult = when (state) {
        ApplyDurabilityState.Awaiting,
        is ApplyDurabilityState.Rejected,
            -> AddDeclarationApplyResult.Rejected(AddDeclarationApplyFailure.Write(failure))
        is ApplyDurabilityState.Durable -> recoverAfterFault(authority, failure)
    }

    private fun recoverAfterFault(
        authority: MutationAuthority,
        failure: SourceWriteFailure,
    ): AddDeclarationApplyResult = when (val outcome = recovery.recover(authority.binding) { record ->
        rollback.rollback(authority, record)
    }) {
        is AddDeclarationRecoveryOutcome.RolledBack ->
            AddDeclarationApplyResult.RolledBack(failure, outcome)
        is AddDeclarationRecoveryOutcome.RecoveryRequired ->
            AddDeclarationApplyResult.RecoveryRequired(failure, outcome.evidence)
        is AddDeclarationRecoveryOutcome.PriorState -> undurable(authority, failure)
    }

    private fun undurable(
        authority: MutationAuthority,
        failure: SourceWriteFailure,
    ): AddDeclarationApplyResult.RecoveryRequired = AddDeclarationApplyResult.RecoveryRequired(
        failure,
        RecoveryRequiredEvidence.Undurable(
            authority.binding,
            UndurableRecoveryRequirement.EVIDENCE_UNAVAILABLE,
        ),
    )
}

private sealed interface ApplyDurabilityState {
    data object Awaiting : ApplyDurabilityState

    data class Durable(
        val recovery: AppliedAddDeclarationRecovery,
    ) : ApplyDurabilityState

    data class Rejected(
        val failure: AddDeclarationRecoveryOperationFailure,
    ) : ApplyDurabilityState
}

private class RecoveryDurabilityBarrier(
    private val recovery: AddDeclarationRecoveryService,
    private val prepared: PreparedAddDeclarationRecovery,
) : MutationDurabilityBarrier {
    private var state: ApplyDurabilityState = ApplyDurabilityState.Awaiting

    @Synchronized
    override fun recordApplied(): MutationDurabilityResult = when (state) {
        ApplyDurabilityState.Awaiting -> when (val result = recovery.recordApplied(prepared)) {
            is RecordAppliedAddDeclarationResult.Recorded -> {
                state = ApplyDurabilityState.Durable(result.recovery)
                MutationDurabilityResult.Durable
            }
            is RecordAppliedAddDeclarationResult.Rejected -> {
                state = ApplyDurabilityState.Rejected(result.failure)
                MutationDurabilityResult.Rejected(
                    MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED,
                )
            }
        }
        is ApplyDurabilityState.Durable,
        is ApplyDurabilityState.Rejected,
            -> MutationDurabilityResult.Rejected(MutationDurabilityFailure.ALREADY_DECIDED)
    }

    @Synchronized
    fun current(): ApplyDurabilityState = state
}
