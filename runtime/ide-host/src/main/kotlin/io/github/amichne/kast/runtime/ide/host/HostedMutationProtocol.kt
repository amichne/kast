package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.change.apply.AddDeclarationApplyFailure
import io.github.amichne.kast.change.apply.AddDeclarationApplyResult
import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.MutationAdmissionFailure
import io.github.amichne.kast.change.apply.SourceObservationFailure
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.verify.AddDeclarationProofFailure
import io.github.amichne.kast.change.verify.ChangeApplicationIdentity
import io.github.amichne.kast.change.verify.ChangeApplicationIssuance
import io.github.amichne.kast.change.verify.ChangeApplicationLookup
import io.github.amichne.kast.change.verify.ChangePlanIdentity
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangePlanLookup
import io.github.amichne.kast.change.verify.ChangeProofFailure
import io.github.amichne.kast.change.verify.ChangeReceiptIdentity
import io.github.amichne.kast.change.verify.ChangeReceiptIssuance
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.change.verify.VerifiedMutationBeforePublicationFailure
import io.github.amichne.kast.change.verify.VerifiedMutationResult
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.TypedOperationBinding

/** Exact add-declaration protocol table; unavailable intents have no semantic branch. */
internal object HostedMutationProtocol {
    fun bindings(
        state: HostedMutationState,
        selectors: HostedExactSelectorOperations,
        authority: DurableChangeAuthority,
    ): List<TypedOperationBinding<*, *, *, *>> {
        val runtimeState = HostedMutationRuntimeState(state)
        return listOf(
            TypedOperationBinding(
                CanonicalOperationWireBindings.changePlan,
                HostedChangePlanHandler(runtimeState, selectors, authority),
            ),
            TypedOperationBinding(
                CanonicalOperationWireBindings.changeApply,
                HostedChangeApplyHandler(runtimeState, authority),
            ),
            TypedOperationBinding(
                CanonicalOperationWireBindings.changeVerify,
                HostedChangeVerifyHandler(runtimeState, authority),
            ),
            TypedOperationBinding(
                CanonicalOperationWireBindings.changeRecover,
                HostedChangeRecoverHandler(runtimeState, authority),
            ),
        )
    }
}

/** Drops clean writer authority immediately after the physical protocol requires recovery. */
internal class HostedMutationRuntimeState(
    initial: HostedMutationState,
) {
    @Volatile
    private var state: HostedMutationState = initial

    fun current(): HostedMutationState = state

    @Synchronized
    fun requireRecovery() {
        val current = state
        if (current is HostedMutationState.Clean) {
            state = HostedMutationState.RecoveryRequired(current.recovery)
        }
    }
}

private class HostedChangePlanHandler(
    private val state: HostedMutationRuntimeState,
    private val selectors: HostedExactSelectorOperations,
    private val authority: DurableChangeAuthority,
) : OperationHandler<ChangePlanRequest, ChangePlanResult, ChangePlanQualification, ChangePlanRejection> {
    override suspend fun execute(request: ChangePlanRequest): OperationOutcome<
        ChangePlanResult,
        ChangePlanQualification,
        ChangePlanRejection,
        > {
        val operation = (state.current() as? HostedMutationState.Clean)?.planning
            ?: return OperationOutcome.Rejected(ChangePlanRejection.RECOVERY_REQUIRED)
        val intent = request.intent as? ChangeIntentDocument.AddDeclaration
            ?: return OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED)
        val selector = when (val lookup = selectors.exact(intent.exactTarget)) {
            is HostedExactLookup.Found -> lookup.selector
            HostedExactLookup.Missing -> return OperationOutcome.Rejected(
                ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED,
            )
            HostedExactLookup.TopologyUnavailable -> return OperationOutcome.Rejected(
                ChangePlanRejection.TOPOLOGY_BUILD_REQUIRED,
            )
        }
        return when (val result = operation.plan(selector, intent.declaration.value)) {
            is HostedChangePlanningResult.Planned -> when (val issued = authority.issuePlan(result.plan)) {
                is ChangePlanIssuance.Issued -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.CHANGE_PLAN.id,
                        result.plan.priorLease.generation,
                        ChangePlanResult(issued.identity.protocolText()),
                    ),
                )
                is ChangePlanIssuance.Rejected ->
                    OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED)
            }
            is HostedChangePlanningResult.Rejected -> OperationOutcome.Rejected(
                result.failure.planRejection(),
            )
        }
    }
}

private class HostedChangeApplyHandler(
    private val state: HostedMutationRuntimeState,
    private val authority: DurableChangeAuthority,
) : OperationHandler<ChangeApplyRequest, ChangeApplyResult, ChangeApplyQualification, ChangeApplyRejection> {
    override suspend fun execute(request: ChangeApplyRequest): OperationOutcome<
        ChangeApplyResult,
        ChangeApplyQualification,
        ChangeApplyRejection,
        > {
        val operation = (state.current() as? HostedMutationState.Clean)?.application
            ?: return OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED)
        val identity = ChangePlanIdentity.parse(request.planIdentity.value)
            ?: return OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND)
        val plan = when (val lookup = authority.loadPlan(identity)) {
            is ChangePlanLookup.Found -> lookup.plan as? AddDeclarationChangePlan
                ?: return OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND)
            ChangePlanLookup.Missing -> return OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND)
            is ChangePlanLookup.Rejected ->
                return OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED)
        }
        return when (val result = operation.apply(plan)) {
            is AppliedUnverified -> when (val issued = authority.issueApplication(plan, result)) {
                is ChangeApplicationIssuance.Issued -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.CHANGE_APPLY.id,
                        result.priorLease.generation,
                        ChangeApplyResult(issued.identity.protocolText()),
                    ),
                )
                is ChangeApplicationIssuance.Rejected ->
                    OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED)
            }
            is AddDeclarationApplyResult.Rejected ->
                OperationOutcome.Rejected(result.failure.applyRejection())
            is AddDeclarationApplyResult.RolledBack ->
                OperationOutcome.Rejected(ChangeApplyRejection.ROLLED_BACK)
            is AddDeclarationApplyResult.RecoveryRequired -> {
                state.requireRecovery()
                OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED)
            }
        }
    }
}

private class HostedChangeVerifyHandler(
    private val state: HostedMutationRuntimeState,
    private val authority: DurableChangeAuthority,
) : OperationHandler<ChangeVerifyRequest, ChangeVerifyResult, ChangeVerifyQualification, ChangeVerifyRejection> {
    override suspend fun execute(request: ChangeVerifyRequest): OperationOutcome<
        ChangeVerifyResult,
        ChangeVerifyQualification,
        ChangeVerifyRejection,
        > {
        val operation = (state.current() as? HostedMutationState.Clean)?.verification
            ?: return OperationOutcome.Rejected(ChangeVerifyRejection.OBLIGATION_FAILED)
        val identity = ChangeApplicationIdentity.parse(request.applicationIdentity.value)
            ?: return OperationOutcome.Rejected(ChangeVerifyRejection.APPLICATION_NOT_FOUND)
        val pending = when (val lookup = authority.loadApplication(identity)) {
            is ChangeApplicationLookup.Found -> lookup.application
            ChangeApplicationLookup.Missing ->
                return OperationOutcome.Rejected(ChangeVerifyRejection.APPLICATION_NOT_FOUND)
            is ChangeApplicationLookup.Rejected ->
                return OperationOutcome.Rejected(ChangeVerifyRejection.OBLIGATION_FAILED)
        }
        return when (val result = operation.verify(pending)) {
            is HostedChangeVerificationResult.Verified -> when (val issued = authority.issueReceipt(result.receipt)) {
                is ChangeReceiptIssuance.Issued -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.CHANGE_VERIFY.id,
                        result.receipt.resultingWorkspace.generation,
                        ChangeVerifyResult(issued.identity.protocolText()),
                    ),
                )
                is ChangeReceiptIssuance.Rejected ->
                    OperationOutcome.Rejected(ChangeVerifyRejection.OBLIGATION_FAILED)
            }
            is HostedChangeVerificationResult.TopologyRejected -> OperationOutcome.Rejected(
                ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE,
            )
            is HostedChangeVerificationResult.MutationRejected -> when (val mutation = result.result) {
                is VerifiedMutationResult.RejectedBeforePublication -> OperationOutcome.Rejected(
                    when (mutation.failure) {
                        is VerifiedMutationBeforePublicationFailure.Admission ->
                            ChangeVerifyRejection.OBLIGATION_FAILED
                        is VerifiedMutationBeforePublicationFailure.Publication ->
                            ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE
                    },
                )
                is VerifiedMutationResult.RejectedAfterPublication,
                is VerifiedMutationResult.RejectedAfterResultingWorkspace,
                -> OperationOutcome.Rejected(ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE)
                is VerifiedMutationResult.RejectedAfterObservation ->
                    OperationOutcome.Rejected(mutation.failures.verifyRejection())
                is VerifiedMutationResult.Verified -> error(
                    "verified mutation must retain hosted topology publication proof",
                )
            }
        }
    }
}

private class HostedChangeRecoverHandler(
    private val state: HostedMutationRuntimeState,
    private val authority: DurableChangeAuthority,
) : OperationHandler<ChangeRecoverRequest, ChangeRecoverResult, ChangeRecoverQualification, ChangeRecoverRejection> {
    override suspend fun execute(request: ChangeRecoverRequest): OperationOutcome<
        ChangeRecoverResult,
        ChangeRecoverQualification,
        ChangeRecoverRejection,
        > {
        val operation = state.current().recoveryOrNull()
            ?: return OperationOutcome.Rejected(ChangeRecoverRejection.JOURNAL_UNAVAILABLE)
        val identity = ChangePlanIdentity.parse(request.planIdentity.value)
            ?: return OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND)
        val plan = when (val lookup = authority.loadPlan(identity)) {
            is ChangePlanLookup.Found -> lookup.plan
            ChangePlanLookup.Missing -> return OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND)
            is ChangePlanLookup.Rejected -> return OperationOutcome.Rejected(ChangeRecoverRejection.RECOVERY_FAILED)
        }
        val binding = when (val parsed = MutationPlanBinding.parse(plan.planId.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(ChangeRecoverRejection.RECOVERY_FAILED)
        }
        return when (operation.recover(binding)) {
            is AddDeclarationRecoveryOutcome.PriorState -> complete(plan.priorLease.generation, ChangeRecoveryDocumentState.PRIOR_STATE)
            is AddDeclarationRecoveryOutcome.RolledBack -> complete(plan.priorLease.generation, ChangeRecoveryDocumentState.ROLLED_BACK)
            is AddDeclarationRecoveryOutcome.RecoveryRequired -> OperationOutcome.Qualified(
                envelope(plan.priorLease.generation, ChangeRecoveryDocumentState.RECOVERY_REQUIRED),
                ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED,
            )
        }
    }

    private fun complete(
        generation: EvidenceGeneration,
        state: ChangeRecoveryDocumentState,
    ): OperationOutcome<ChangeRecoverResult, ChangeRecoverQualification, ChangeRecoverRejection> =
        OperationOutcome.Complete(envelope(generation, state))

    private fun envelope(
        generation: EvidenceGeneration,
        state: ChangeRecoveryDocumentState,
    ) = EvidenceEnvelope(CanonicalOperation.CHANGE_RECOVER.id, generation, ChangeRecoverResult(state))
}

private fun HostedMutationState.recoveryOrNull(): ChangeRecoveryOperations? = when (this) {
    is HostedMutationState.Clean -> recovery
    is HostedMutationState.RecoveryRequired -> recovery
    is HostedMutationState.Rejected -> null
}

private fun HostedMutationAdmissionFailure.planRejection(): ChangePlanRejection = when (this) {
    HostedMutationAdmissionFailure.WORKSPACE_NOT_READY -> ChangePlanRejection.WORKSPACE_NOT_READY
    HostedMutationAdmissionFailure.TOPOLOGY_BUILD_REQUIRED -> ChangePlanRejection.TOPOLOGY_BUILD_REQUIRED
    HostedMutationAdmissionFailure.SELECTOR_STALE -> ChangePlanRejection.SYMBOL_RESOLVE_REQUIRED
    HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED -> ChangePlanRejection.EDITABLE_TARGET_REQUIRED
    HostedMutationAdmissionFailure.RELATION_READ_REQUIRED -> ChangePlanRejection.RELATION_READ_REQUIRED
    HostedMutationAdmissionFailure.TRAVERSAL_REQUIRED -> ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE
    HostedMutationAdmissionFailure.DIAGNOSTIC_CHECK_REQUIRED -> ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED
    HostedMutationAdmissionFailure.INTENT_REJECTED,
    HostedMutationAdmissionFailure.STORAGE_UNAVAILABLE,
    HostedMutationAdmissionFailure.CORRUPT_RECOVERY,
    -> ChangePlanRejection.INTENT_REJECTED
}

private fun AddDeclarationApplyFailure.applyRejection(): ChangeApplyRejection = when (this) {
    is AddDeclarationApplyFailure.Observation -> when (failure) {
        SourceObservationFailure.DUMB_MODE -> ChangeApplyRejection.GENERATION_STALE
        SourceObservationFailure.TARGET_NOT_FOUND,
        SourceObservationFailure.TARGET_INVALIDATED,
        SourceObservationFailure.SOURCE_BYTES_UNAVAILABLE,
        SourceObservationFailure.INVALID_SOURCE_CONTENT,
        -> ChangeApplyRejection.CONTENT_CHANGED
        SourceObservationFailure.TARGET_NOT_KOTLIN,
        SourceObservationFailure.DOCUMENT_UNAVAILABLE,
        -> ChangeApplyRejection.WRITE_SCOPE_REJECTED
    }
    is AddDeclarationApplyFailure.Admission -> when (failure) {
        MutationAdmissionFailure.WRONG_ROOT -> ChangeApplyRejection.ROOT_MISMATCH
        MutationAdmissionFailure.STALE_GENERATION,
        MutationAdmissionFailure.STALE_SOURCE_STATE,
        -> ChangeApplyRejection.GENERATION_STALE
        MutationAdmissionFailure.SOURCE_CONTENT_CHANGED,
        MutationAdmissionFailure.MUTATION_PREIMAGE_MISMATCH,
        MutationAdmissionFailure.SOURCE_PRECONDITION_MISMATCH,
        -> ChangeApplyRejection.CONTENT_CHANGED
        else -> ChangeApplyRejection.WRITE_SCOPE_REJECTED
    }
    is AddDeclarationApplyFailure.RecoveryPreparation,
    is AddDeclarationApplyFailure.RecoveryEvidence,
    -> ChangeApplyRejection.RECOVERY_REQUIRED
    is AddDeclarationApplyFailure.Write -> when (failure) {
        SourceWriteFailure.DUMB_MODE -> ChangeApplyRejection.GENERATION_STALE
        SourceWriteFailure.PREIMAGE_CHANGED -> ChangeApplyRejection.CONTENT_CHANGED
        SourceWriteFailure.DURABILITY_REJECTED,
        SourceWriteFailure.ROLLBACK_FAILED,
        SourceWriteFailure.SAVE_FAILED,
        SourceWriteFailure.OBSERVATION_FAILED,
        -> ChangeApplyRejection.RECOVERY_REQUIRED
        else -> ChangeApplyRejection.WRITE_SCOPE_REJECTED
    }
}

private fun Set<ChangeProofFailure>.verifyRejection(): ChangeVerifyRejection = when {
    any { it == AddDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED } ->
        ChangeVerifyRejection.DIAGNOSTIC_REGRESSION
    any {
        it == AddDeclarationProofFailure.SEMANTIC_DELTA_REJECTED ||
            it == AddDeclarationProofFailure.RELATION_DELTA_REJECTED
    } -> ChangeVerifyRejection.SEMANTIC_DELTA_REJECTED
    else -> ChangeVerifyRejection.OBLIGATION_FAILED
}

private fun ChangePlanIdentity.protocolText(): ProtocolText = protocolText(value)
private fun ChangeApplicationIdentity.protocolText(): ProtocolText = protocolText(value)
private fun ChangeReceiptIdentity.protocolText(): ProtocolText = protocolText(value)

private fun protocolText(value: String): ProtocolText = when (val parsed = ProtocolText.parse(value)) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("canonical durable change identity is protocol text")
}
