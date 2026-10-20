package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFunctionInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.invocationWire
import io.github.amichne.kast.protocol.contract.observedCallbackWire
import io.github.amichne.kast.protocol.contract.toWireDocument
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ValueCallOccurrence
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueInvocation

internal fun ValueCallOccurrence.impactOccurrenceDocument(): ImpactProjected<ImpactCallOccurrenceDocument> =
    when (this) {
        is ValueInvocation -> impactDocument().impactMap(ImpactCallOccurrenceDocument::Declared)
        is ValueFunctionInvocation -> impactDocument().impactMap(ImpactCallOccurrenceDocument::Function)
    }

internal fun ValueFunctionInvocation.impactDocument(): ImpactProjected<ImpactFunctionInvocationReferenceDocument> {
    val callable =
        callable.protocolDocument()?.toWireDocument()
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return range.impactDocument().impactZip(executionOwner.identity.impactDocument()).impactMap { (range, owner) ->
        ImpactFunctionInvocationReferenceDocument(range, callable, owner)
    }
}

internal fun ValueCallbackTarget.impactCallbackDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactCallTargetDocument.Callback> {
    val projection = CallbackProjection(references, supplyingFrame.invocation.enclosing.lease)
    val flow =
        projection.observedFlow(flow)?.observedCallbackWire()
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    val selected =
        projection.invocation(selected)?.invocationWire()
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return supplyingFrame.impactDocument(references).impactThen { frame ->
        val invocation = (frame.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation
        val target = frame.target as? ImpactDeclaredCallTargetDocument
        if (invocation == null || target == null)
            Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
        else
            Refinement.Refined(
                ImpactCallTargetDocument.Callback(
                    flow,
                    selected,
                    ImpactDeclaredCallFrameDocument(frame.enclosing, invocation, frame.callee, target),
                )
            )
    }
}

internal fun CallbackInvocationFlow.impactObligationDocument(
    references: QueryReferenceAuthority,
    basis: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
): ImpactProjected<ImpactFlowObligationEvidenceDocument> {
    val flow =
        CallbackProjection(references, basis).observedFlow(this)?.observedCallbackWire()
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return Refinement.Refined(ImpactFlowObligationEvidenceDocument.CallbackFlow(flow))
}
