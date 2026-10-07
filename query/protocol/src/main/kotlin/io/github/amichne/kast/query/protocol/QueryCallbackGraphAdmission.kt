package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationOrigin
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.admitCallbackWorkspace

internal fun callbackProof(
    relations: List<QueryRelationObservation>,
    walks: List<QueryWalkObservation>,
): Refinement<Unit, QueryCompletionFailure> {
    relations.forEachIndexed { group, relation ->
        when (
            val proof =
                callbackGroupProof(
                    relation.callbackObservations,
                    relation.callableObservations,
                    QueryCallbackObservationOrigin.RELATION,
                    group,
                )
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return proof
        }
    }
    walks.forEachIndexed { group, walk ->
        when (
            val proof =
                callbackGroupProof(
                    walk.callbackObservations.map { it.observation },
                    walk.callableObservations.map { it.observation },
                    QueryCallbackObservationOrigin.WALK,
                    group,
                )
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return proof
        }
    }
    return Refinement.Refined(Unit)
}

private fun callbackGroupProof(
    callbacks: List<RelationCallbackObservation>,
    callables: List<io.github.amichne.kast.relation.contract.RelationCallableObservation>,
    origin: QueryCallbackObservationOrigin,
    group: Int,
): Refinement<Unit, QueryCompletionFailure> {
    callables.forEachIndexed { position, value ->
        when (val proof = value.callableProof()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return callbackRejected(proof.failure, origin, group, position)
        }
    }
    callbacks.forEachIndexed { position, observation ->
        when (val admitted = CompleteStaticCallbackGraph.admit(observation)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected ->
                return when (val projected = admitted.failure.protocolGraphCause()) {
                    is Refinement.Refined -> callbackRejected(projected.value, origin, group, position)
                    is Refinement.Rejected ->
                        callbackRejected(
                            QueryCallbackGraphCauseDocument.InvalidGraphFailure(projected.failure),
                            origin,
                            group,
                            position,
                        )
                }
        }
    }
    return Refinement.Refined(Unit)
}

private fun callbackRejected(
    cause: QueryCallbackGraphCauseDocument,
    origin: QueryCallbackObservationOrigin,
    group: Int,
    position: Int,
) =
    Refinement.Rejected(
        QueryCompletionFailure.Callback(
            QueryCallbackGraphFailureDocument(cause, origin, queryPosition(group), queryPosition(position))
        )
    )

private fun io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow.Unavailable.namedReferenceFailure():
    QueryCallbackGraphCauseDocument {
    if (causes.size == 1) return QueryCallbackGraphCauseDocument.Unavailable(cause.protocolCallbackDocument())
    return when (
        val projected =
            io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument.from(
                causes.map { it.protocolCallbackDocument() }.sortedBy { it.ordinal }
            )
    ) {
        is Refinement.Refined ->
            QueryCallbackGraphCauseDocument.Unresolved(
                projected.value,
                io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument.INCOMPLETE,
            )
        is Refinement.Rejected ->
            QueryCallbackGraphCauseDocument.InvalidGraphFailure(
                io.github.amichne.kast.protocol.contract.QueryCallbackGraphProjectionFailureDocument
                    .NON_CANONICAL_NATIVE_OBLIGATIONS
            )
    }
}

private fun io.github.amichne.kast.relation.contract.RelationCallableTarget.callableProof():
    Refinement<Unit, QueryCallbackGraphCauseDocument> =
    when (this) {
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.UnavailableSupply ->
            when (
                val obligations =
                    io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument.from(
                        evidence.causes.sortedBy { it.ordinal }.map { it.protocolCallbackDocument() }
                    )
            ) {
                is Refinement.Refined ->
                    Refinement.Rejected(
                        QueryCallbackGraphCauseDocument.Unresolved(
                            obligations.value,
                            io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument.INCOMPLETE,
                        )
                    )
                is Refinement.Rejected ->
                    Refinement.Rejected(
                        QueryCallbackGraphCauseDocument.InvalidGraphFailure(
                            io.github.amichne.kast.protocol.contract.QueryCallbackGraphProjectionFailureDocument
                                .NON_CANONICAL_NATIVE_OBLIGATIONS
                        )
                    )
            }
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.DirectInvocations,
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.CallbackSupplies -> Refinement.Refined(Unit)
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.UnavailableReference ->
            Refinement.Rejected(QueryCallbackGraphCauseDocument.Unavailable(cause.protocolCallbackDocument()))
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.NamedReference -> reference.flow.namedProof()
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.ParameterInvocation ->
            when (val proof = suppliers) {
                is io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence.Exhaustive ->
                    Refinement.Refined(Unit)
                is io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence.Unavailable ->
                    Refinement.Rejected(
                        QueryCallbackGraphCauseDocument.Unavailable(proof.cause.protocolCallbackDocument())
                    )
            }
        is io.github.amichne.kast.relation.contract.RelationCallableTarget.SourceLess ->
            Refinement.Rejected(QueryCallbackGraphCauseDocument.CallableValueUnproven)
    }

private fun io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow.namedProof():
    Refinement<Unit, QueryCallbackGraphCauseDocument> =
    when (this) {
        is io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow.Supplied,
        is io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow.Direct -> Refinement.Refined(Unit)
        is io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow.Unavailable ->
            Refinement.Rejected(namedReferenceFailure())
        is io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow.Immutable -> flow.immutableProof()
    }

private fun io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow.immutableProof():
    Refinement<Unit, QueryCallbackGraphCauseDocument> {
    if (obligations.isNotEmpty())
        return when (
            val projected =
                io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure.ImmutableUnresolved(this)
                    .protocolGraphCause()
        ) {
            is Refinement.Refined -> Refinement.Rejected(projected.value)
            is Refinement.Rejected ->
                Refinement.Rejected(QueryCallbackGraphCauseDocument.InvalidGraphFailure(projected.failure))
        }
    return if (scan == io.github.amichne.kast.relation.contract.CallbackInvocationScan.EXHAUSTIVE)
        Refinement.Refined(Unit)
    else Refinement.Rejected(QueryCallbackGraphCauseDocument.IncompleteScan)
}

private fun io.github.amichne.kast.relation.contract.RelationCallableObservation.callableProof():
    Refinement<Unit, QueryCallbackGraphCauseDocument> {
    when (val complete = target.callableProof()) {
        is Refinement.Rejected -> return complete
        is Refinement.Refined -> Unit
    }
    return when (val admitted = admitCallbackWorkspace()) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected ->
            when (val projected = admitted.failure.protocolGraphCause()) {
                is Refinement.Refined -> Refinement.Rejected(projected.value)
                is Refinement.Rejected ->
                    Refinement.Rejected(QueryCallbackGraphCauseDocument.InvalidGraphFailure(projected.failure))
            }
    }
}
