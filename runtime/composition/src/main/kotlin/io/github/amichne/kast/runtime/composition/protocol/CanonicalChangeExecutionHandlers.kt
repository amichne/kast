package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.apply.AddDeclarationApplyFailure
import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.apply.AddDeclarationApplyResult
import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.MutationAdmissionFailure
import io.github.amichne.kast.change.apply.RequestedMutationWriteScope
import io.github.amichne.kast.change.apply.SourceObservationFailure
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.verify.AddDeclarationProofFailure
import io.github.amichne.kast.change.verify.AddFileProofFailure
import io.github.amichne.kast.change.verify.ChangeProofFailure
import io.github.amichne.kast.change.verify.RenameSymbolProofFailure
import io.github.amichne.kast.change.verify.ReplaceDeclarationProofFailure
import io.github.amichne.kast.change.verify.VerifiedMutationBeforePublicationFailure
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.change.verify.VerifiedMutationRequest
import io.github.amichne.kast.change.verify.VerifiedMutationResult
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.runtime.composition.ChangeRecoveryOperations
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.change.apply.ChangeApplyRequest as DomainChangeApplyRequest

internal class CanonicalChangeApplyHandler(
    private val workspace: WorkspaceInspectionOperations,
    private val operations: AddDeclarationApplyOperations,
    private val authority: CanonicalChangeAuthority,
) : OperationHandler<
    ChangeApplyRequest,
    ChangeApplyResult,
    ChangeApplyQualification,
    ChangeApplyRejection,
    > {
    override suspend fun execute(request: ChangeApplyRequest): OperationOutcome<
        ChangeApplyResult,
        ChangeApplyQualification,
        ChangeApplyRejection,
        > {
        val plan = when (val lookup = authority.plan(request.planIdentity)) {
            is ChangePlanLookup.Found -> lookup.plan
            ChangePlanLookup.Missing ->
                return OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND)
        }
        val ready = when (val state = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            else -> return OperationOutcome.Rejected(ChangeApplyRejection.GENERATION_STALE)
        }
        val writeScope = RequestedMutationWriteScope(
            ready.root,
            plan.writes.entries.mapTo(linkedSetOf()) { it.source },
        )
        return when (val result = operations.apply(DomainChangeApplyRequest(plan, ready, writeScope))) {
            is AppliedUnverified -> applied(result, plan)
            is AddDeclarationApplyResult.Rejected ->
                OperationOutcome.Rejected(result.failure.protocolRejection())
            is AddDeclarationApplyResult.RolledBack ->
                OperationOutcome.Rejected(ChangeApplyRejection.ROLLED_BACK)
            is AddDeclarationApplyResult.RecoveryRequired ->
                OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED)
        }
    }

    private fun applied(
        result: AppliedUnverified,
        plan: io.github.amichne.kast.change.contract.ChangePlan,
    ): OperationOutcome<ChangeApplyResult, ChangeApplyQualification, ChangeApplyRejection> =
        when (val issued = authority.issueApplication(plan, result)) {
            is ChangeApplicationIssuance.Issued -> OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.CHANGE_APPLY.id,
                    result.priorLease.generation,
                    ChangeApplyResult(issued.identity),
                ),
            )
            is ChangeApplicationIssuance.Rejected ->
                OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED)
        }
}

internal class CanonicalChangeVerifyHandler(
    private val operations: VerifiedMutationOperations,
    private val authority: CanonicalChangeAuthority,
) : OperationHandler<
    ChangeVerifyRequest,
    ChangeVerifyResult,
    ChangeVerifyQualification,
    ChangeVerifyRejection,
    > {
    override suspend fun execute(request: ChangeVerifyRequest): OperationOutcome<
        ChangeVerifyResult,
        ChangeVerifyQualification,
        ChangeVerifyRejection,
        > {
        val pending = when (val lookup = authority.application(request.applicationIdentity)) {
            is ChangeApplicationLookup.Found -> lookup.application
            ChangeApplicationLookup.Missing ->
                return OperationOutcome.Rejected(ChangeVerifyRejection.APPLICATION_NOT_FOUND)
        }
        return when (val result = operations.verify(
            VerifiedMutationRequest(
                pending.plan,
                pending.applied,
            )
        )) {
            is VerifiedMutationResult.Verified -> when (
                val issued = authority.issueReceipt(result.receipt)
            ) {
                is ChangeReceiptIssuance.Issued -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.CHANGE_VERIFY.id,
                        result.receipt.resultingWorkspace.generation,
                        ChangeVerifyResult(issued.identity),
                    ),
                )
                is ChangeReceiptIssuance.Rejected ->
                    OperationOutcome.Rejected(ChangeVerifyRejection.OBLIGATION_FAILED)
            }
            is VerifiedMutationResult.RejectedBeforePublication -> OperationOutcome.Rejected(
                when (result.failure) {
                    is VerifiedMutationBeforePublicationFailure.Admission ->
                        ChangeVerifyRejection.OBLIGATION_FAILED
                    is VerifiedMutationBeforePublicationFailure.Publication ->
                        ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE
                },
            )
            is VerifiedMutationResult.RejectedAfterPublication,
            is VerifiedMutationResult.RejectedAfterResultingWorkspace,
                -> OperationOutcome.Rejected(
                ChangeVerifyRejection.RESULTING_GENERATION_UNAVAILABLE,
            )
            is VerifiedMutationResult.RejectedAfterObservation ->
                OperationOutcome.Rejected(result.failures.protocolRejection())
        }
    }
}

internal class CanonicalChangeRecoverHandler(
    private val operations: ChangeRecoveryOperations,
    private val authority: CanonicalChangeAuthority,
) : OperationHandler<
    ChangeRecoverRequest,
    ChangeRecoverResult,
    ChangeRecoverQualification,
    ChangeRecoverRejection,
    > {
    override suspend fun execute(request: ChangeRecoverRequest): OperationOutcome<
        ChangeRecoverResult,
        ChangeRecoverQualification,
        ChangeRecoverRejection,
        > {
        val plan = when (val lookup = authority.plan(request.planIdentity)) {
            is ChangePlanLookup.Found -> lookup.plan
            ChangePlanLookup.Missing ->
                return OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND)
        }
        val binding = when (val parsed = MutationPlanBinding.parse(plan.planId.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(ChangeRecoverRejection.RECOVERY_FAILED)
        }
        return when (operations.recover(binding)) {
            is AddDeclarationRecoveryOutcome.PriorState -> complete(
                plan,
                ChangeRecoveryDocumentState.PRIOR_STATE,
            )
            is AddDeclarationRecoveryOutcome.RolledBack -> complete(
                plan,
                ChangeRecoveryDocumentState.ROLLED_BACK,
            )
            is AddDeclarationRecoveryOutcome.RecoveryRequired -> OperationOutcome.Qualified(
                envelope(plan, ChangeRecoveryDocumentState.RECOVERY_REQUIRED),
                ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED,
            )
        }
    }

    private fun complete(
        plan: io.github.amichne.kast.change.contract.ChangePlan,
        state: ChangeRecoveryDocumentState,
    ): OperationOutcome<ChangeRecoverResult, ChangeRecoverQualification, ChangeRecoverRejection> =
        OperationOutcome.Complete(envelope(plan, state))

    private fun envelope(
        plan: io.github.amichne.kast.change.contract.ChangePlan,
        state: ChangeRecoveryDocumentState,
    ): EvidenceEnvelope<ChangeRecoverResult> = EvidenceEnvelope(
        CanonicalOperation.CHANGE_RECOVER.id,
        plan.priorLease.generation,
        ChangeRecoverResult(state),
    )
}

private fun AddDeclarationApplyFailure.protocolRejection(): ChangeApplyRejection = when (this) {
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

private fun Set<ChangeProofFailure>.protocolRejection(): ChangeVerifyRejection = when {
    any { it.isDiagnosticRegression() } -> ChangeVerifyRejection.DIAGNOSTIC_REGRESSION
    any { it.isSemanticDeltaRejection() } -> ChangeVerifyRejection.SEMANTIC_DELTA_REJECTED
    else -> ChangeVerifyRejection.OBLIGATION_FAILED
}

private fun ChangeProofFailure.isDiagnosticRegression(): Boolean = when (this) {
    AddDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED,
    AddFileProofFailure.COMPILER_DIAGNOSTICS_REJECTED,
    RenameSymbolProofFailure.COMPILER_DIAGNOSTICS_REJECTED,
    ReplaceDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED,
        -> true
    else -> false
}

private fun ChangeProofFailure.isSemanticDeltaRejection(): Boolean = when (this) {
    AddDeclarationProofFailure.SEMANTIC_DELTA_REJECTED,
    AddDeclarationProofFailure.RELATION_DELTA_REJECTED,
    AddFileProofFailure.FILE_IDENTITY_MISMATCH,
    RenameSymbolProofFailure.OLD_NAME_MISMATCH,
    RenameSymbolProofFailure.NEW_NAME_MISMATCH,
    RenameSymbolProofFailure.OLD_DECLARATION_REMAINS,
    RenameSymbolProofFailure.NEW_DECLARATION_NOT_UNIQUE,
    RenameSymbolProofFailure.OLD_REFERENCE_REMAINS,
    RenameSymbolProofFailure.RENAMED_REFERENCE_COUNT_MISMATCH,
    ReplaceDeclarationProofFailure.REPLACEMENT_DECLARATION_MISMATCH,
    ReplaceDeclarationProofFailure.REPLACEMENT_RANGE_MISMATCH,
        -> true
    else -> false
}
