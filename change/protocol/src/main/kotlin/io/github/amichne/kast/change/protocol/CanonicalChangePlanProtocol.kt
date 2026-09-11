package io.github.amichne.kast.change.protocol

import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddFilePlanResult
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.ChangePlanningFailure
import io.github.amichne.kast.change.contract.RenameSymbolPlanResult
import io.github.amichne.kast.change.contract.RenameSymbolPlanningFailure
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanResult
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanningFailure
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ProtocolText

/** Host-independent lowering and projection; admission owns authority, planning owns pure derivation. */
class CanonicalChangePlanProtocol(
    private val operations: ChangePlanningOperations,
    private val admission: ChangePlanRequestAdmission,
    private val authority: DurableChangeAuthority,
) {
    suspend fun execute(
        request: ChangePlanRequest
    ): OperationOutcome<ChangePlanResult, ChangePlanQualification, ChangePlanRejection> {
        return when (val admitted = admission.admit(request)) {
            is ChangePlanAdmission.Rejected -> OperationOutcome.Rejected(admitted.failure.protocol())
            is ChangePlanAdmission.AddFile ->
                when (val result = operations.addFile.plan(admitted.request)) {
                    is AddFilePlanResult.Planned -> planned(result.plan)
                }
            is ChangePlanAdmission.AddDeclaration ->
                when (val result = operations.addDeclaration.plan(admitted.request)) {
                    is AddDeclarationPlanResult.Planned -> planned(result.plan)
                    is AddDeclarationPlanResult.Rejected -> OperationOutcome.Rejected(result.failure.protocol())
                }
            is ChangePlanAdmission.ReplaceDeclaration ->
                when (val result = operations.replaceDeclaration.plan(admitted.request)) {
                    is ReplaceDeclarationPlanResult.Planned -> planned(result.plan)
                    is ReplaceDeclarationPlanResult.Rejected -> OperationOutcome.Rejected(result.failure.protocol())
                }
            is ChangePlanAdmission.RenameSymbol ->
                when (val result = operations.renameSymbol.plan(admitted.request)) {
                    is RenameSymbolPlanResult.Planned -> planned(result.plan)
                    is RenameSymbolPlanResult.Rejected -> OperationOutcome.Rejected(result.failure.protocol())
                }
        }
    }

    private fun planned(
        plan: ChangePlan
    ): OperationOutcome<ChangePlanResult, ChangePlanQualification, ChangePlanRejection> =
        when (val issued = authority.issuePlan(plan)) {
            is ChangePlanIssuance.Issued ->
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.CHANGE_PLAN.id,
                        plan.priorLease.generation,
                        ChangePlanResult(issued.identity.protocolText(), plan.protocolPreview()),
                    )
                )
            is ChangePlanIssuance.Rejected -> OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED)
        }
}

private fun io.github.amichne.kast.change.verify.ChangePlanIdentity.protocolText(): ProtocolText =
    when (val parsed = ProtocolText.parse(value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("canonical change plan identity is protocol text")
    }

private fun ChangePlanAdmissionFailure.protocol(): ChangePlanRejection =
    when (this) {
        ChangePlanAdmissionFailure.WORKSPACE_NOT_READY -> ChangePlanRejection.WORKSPACE_NOT_READY
        ChangePlanAdmissionFailure.EXACT_SYMBOL_REQUIRED -> ChangePlanRejection.EXACT_SYMBOL_REQUIRED
        ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED -> ChangePlanRejection.EDITABLE_TARGET_REQUIRED
        ChangePlanAdmissionFailure.RELATION_READ_REQUIRED -> ChangePlanRejection.RELATION_READ_REQUIRED
        ChangePlanAdmissionFailure.TOPOLOGY_BUILD_REQUIRED -> ChangePlanRejection.TOPOLOGY_BUILD_REQUIRED
        ChangePlanAdmissionFailure.REQUIRED_TRAVERSAL_INCOMPLETE -> ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE
        ChangePlanAdmissionFailure.DIAGNOSTIC_CHECK_REQUIRED -> ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED
        ChangePlanAdmissionFailure.INTENT_REJECTED -> ChangePlanRejection.INTENT_REJECTED
    }

private fun ChangePlanningFailure.protocol(): ChangePlanRejection =
    when (this) {
        ChangePlanningFailure.RELATION_EVIDENCE_REQUIRED,
        ChangePlanningFailure.RELATION_EVIDENCE_INCOMPLETE -> ChangePlanRejection.RELATION_READ_REQUIRED
        ChangePlanningFailure.TRAVERSAL_EVIDENCE_REQUIRED -> ChangePlanRejection.INTENT_REJECTED
        ChangePlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE -> ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE
        ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_REQUIRED,
        ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE -> ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED
        ChangePlanningFailure.EVIDENCE_LEASE_MISMATCH,
        ChangePlanningFailure.EVIDENCE_TARGET_MISMATCH -> ChangePlanRejection.EXACT_SYMBOL_REQUIRED
    }

private fun RenameSymbolPlanningFailure.protocol(): ChangePlanRejection =
    when (this) {
        is RenameSymbolPlanningFailure.Evidence -> failure.protocol()
        RenameSymbolPlanningFailure.REFERENCE_EVIDENCE_REQUIRED,
        RenameSymbolPlanningFailure.REFERENCE_EVIDENCE_AMBIGUOUS -> ChangePlanRejection.RELATION_READ_REQUIRED
        RenameSymbolPlanningFailure.NEW_NAME_UNCHANGED,
        RenameSymbolPlanningFailure.OCCURRENCE_EVIDENCE_MISMATCH -> ChangePlanRejection.INTENT_REJECTED
    }

private fun ReplaceDeclarationPlanningFailure.protocol(): ChangePlanRejection =
    when (this) {
        is ReplaceDeclarationPlanningFailure.Evidence -> failure.protocol()
        ReplaceDeclarationPlanningFailure.REPLACEMENT_UNCHANGED -> ChangePlanRejection.INTENT_REJECTED
    }
