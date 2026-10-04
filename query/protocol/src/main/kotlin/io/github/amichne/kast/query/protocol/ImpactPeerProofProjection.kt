package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerAcquisitionReceiptDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerContinuationReasonDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerSiteAdmissionDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAdmissionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.query.contract.QueryImpactPeerAcquisitionReceipt
import io.github.amichne.kast.query.contract.QueryImpactPeerContinuationReason
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.relation.contract.RelationBudget

internal fun QueryImpactPeerSiteAdmission.impactDocument(): ImpactProjected<ImpactPeerSiteAdmissionDocument> =
    acquisition
        .impactDocument()
        .impactZip(selection.request.budget.impactBudget())
        .impactZip(count(selection.examinedWorkUnits.value))
        .impactMap { (admission, work) ->
            ImpactPeerSiteAdmissionDocument(admission.first, ImpactSiteAdmissionDocument(admission.second, work))
        }

private fun QueryImpactPeerAcquisitionReceipt.impactDocument(): ImpactProjected<ImpactPeerAcquisitionReceiptDocument> =
    completedAuthority.identity
        .impactDocument()
        .impactZip(grant.impactBudget())
        .impactZip(count(examinedWorkUnits.value))
        .impactZip(count(elapsed.value))
        .impactMap { (receipt, nanos) ->
            ImpactPeerAcquisitionReceiptDocument(receipt.first.first, receipt.first.second, receipt.second, nanos)
        }

private fun RelationBudget.impactBudget(): ImpactProjected<ImpactFlowBudgetDocument> =
    ReturnedByteLimit.parse(returnedBytes.value).impactFailure(ImpactPathProjectionFailure::Budget).impactMap {
        ImpactFlowBudgetDocument(resources.elapsedTimeLimit, resources.workUnitLimit, resources.resultLimit, it)
    }

internal fun QueryImpactPeerContinuationReason.impactDocument(): ImpactPeerContinuationReasonDocument =
    when (this) {
        QueryImpactPeerContinuationReason.PEER_FLOW_NOT_INVESTIGATED ->
            ImpactPeerContinuationReasonDocument.PEER_FLOW_NOT_INVESTIGATED
    }

private fun count(raw: Long): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(raw).impactFailure(ImpactPathProjectionFailure::Count)
