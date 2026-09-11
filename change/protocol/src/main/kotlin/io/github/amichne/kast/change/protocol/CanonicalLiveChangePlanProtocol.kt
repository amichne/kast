package io.github.amichne.kast.change.protocol

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangePlanIssuance
import io.github.amichne.kast.change.contract.LiveChangePlanStore
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult

/** The owner restores the original reference and finishes pure planning inside one current live read. */
fun interface LiveChangePlanRequestAdmission {
    suspend fun plan(request: ChangePlanRequest): Refinement<LiveAddDeclarationChangePlan, ChangePlanRejection>
}

/** Shared canonical operation and preview; the live basis is never projected as a generation. */
class CanonicalLiveChangePlanProtocol(
    private val admission: LiveChangePlanRequestAdmission,
    private val plans: LiveChangePlanStore,
) {
    suspend fun execute(
        request: ChangePlanRequest
    ): OperationOutcome<ChangePlanResult, ChangePlanQualification, ChangePlanRejection> {
        when (request.intent) {
            is ChangeIntentDocument.AddDeclaration -> Unit
            is ChangeIntentDocument.AddFile,
            is ChangeIntentDocument.RenameSymbol,
            is ChangeIntentDocument.ReplaceDeclaration ->
                return OperationOutcome.Rejected(ChangePlanRejection.UNSUPPORTED_HOSTED_INTENT)
        }
        val plan =
            when (val result = admission.plan(request)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(result.failure)
            }
        val reference = plan.basis.observation.reference
        val basis =
            when (
                val result =
                    LiveReadEvidence.create(
                        workspaceRoot = reference.workspaceRoot.value,
                        host = reference.host.value,
                        epoch = reference.epoch.value,
                        contentView = LiveReadContentView.SAVED_PSI_COMMITTED,
                        version = reference.version,
                    )
            ) {
                is Refinement.Refined -> EvidenceBasis.Live(result.value)
                is Refinement.Rejected -> return OperationOutcome.Rejected(ChangePlanRejection.EXACT_SYMBOL_REQUIRED)
            }
        return when (val issued = plans.issuePlan(plan)) {
            is LiveChangePlanIssuance.Issued ->
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        operation = CanonicalOperation.CHANGE_PLAN.id,
                        basis = basis,
                        payload = ChangePlanResult(issued.identity.protocolText(), plan.protocolPreview()),
                    )
                )
            is LiveChangePlanIssuance.Rejected -> OperationOutcome.Rejected(ChangePlanRejection.RECOVERY_REQUIRED)
        }
    }
}
