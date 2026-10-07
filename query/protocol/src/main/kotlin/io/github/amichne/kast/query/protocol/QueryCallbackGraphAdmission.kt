package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationOrigin
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallbackObservation

internal fun callbackProof(
    relations: List<QueryRelationObservation>,
    walks: List<QueryWalkObservation>,
): Refinement<Unit, QueryCompletionFailure> {
    relations.forEachIndexed { group, relation ->
        when (
            val proof =
                callbackGroupProof(
                    relation.callbackObservations,
                    relation.callableObservations.size,
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
                    walk.callableObservations.size,
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
    callableCount: Int,
    origin: QueryCallbackObservationOrigin,
    group: Int,
): Refinement<Unit, QueryCompletionFailure> {
    // An unsupported value observation is cheaper to reject than constructing an unrelated callback graph.
    if (callableCount > 0)
        return callbackRejected(QueryCallbackGraphCauseDocument.CallableValueUnproven, origin, group, 0)
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
