package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalOperations

/**
 * Proof transition: `RelationOperations -> TraversalOperations`.
 *
 * Establishes a host-neutral traversal capability whose every hop delegates to the sole public
 * bounded relation authority. Exact endpoint, meaning, scope, budget, and continuation authority
 * are retained. Each read is conservatively charged its full authorized one-hop elapsed limit;
 * no platform or clock effect is introduced.
 */
fun traversalOperations(relations: RelationOperations): TraversalOperations =
    TraversalService(RelationOperationsOneHopReader(relations))

private class RelationOperationsOneHopReader(
    private val relations: RelationOperations,
) : OneHopRelationReader {
    override suspend fun read(request: OneHopRelationRequest): OneHopRelationRead =
        when (val projection = request.toRelationRequest()) {
            is OneHopRelationRequestProjection.Admitted -> OneHopRelationRead.Completed(
                relations.read(projection.request),
                OneHopElapsedMillis.charge(request.budget.resources.elapsedTimeLimit),
            )
            OneHopRelationRequestProjection.Rejected -> OneHopRelationRead.Rejected
        }
}

private sealed interface OneHopRelationRequestProjection {
    data class Admitted(val request: RelationRequest) : OneHopRelationRequestProjection
    data object Rejected : OneHopRelationRequestProjection
}

/**
 * Proof transition: `OneHopRelationRequest -> OneHopRelationRequestProjection`.
 *
 * Admitted preserves the exact subject, meaning, scope, one-hop budget, and bound continuation in
 * one public [RelationRequest]. Rejected is the closed failure for divergent scope or continuation
 * authority. Primitive identity extraction is not permitted at this adapter boundary.
 */
private fun OneHopRelationRequest.toRelationRequest(): OneHopRelationRequestProjection {
    if (node.endpoint.scope != scope) return OneHopRelationRequestProjection.Rejected
    return when (val oneHopPosition = position) {
        OneHopRelationPosition.Start -> OneHopRelationRequestProjection.Admitted(
            when (val subject = node.endpoint) {
                is RelationEndpoint.Subject ->
                    RelationRequest.start(subject.selector, meaning, budget)
                is RelationEndpoint.Resolved -> RelationRequest.start(subject, meaning, budget)
            },
        )
        is OneHopRelationPosition.Resume -> when (val subject = node.endpoint) {
            is RelationEndpoint.Subject -> RelationRequest.resume(
                subject.selector,
                meaning,
                budget,
                oneHopPosition.continuation,
            ).toProjection()
            is RelationEndpoint.Resolved -> RelationRequest.resume(
                subject,
                meaning,
                budget,
                oneHopPosition.continuation,
            ).toProjection()
        }
    }
}

/**
 * Proof transition: `Refinement<RelationRequest, RelationResumeFailure> ->
 * OneHopRelationRequestProjection`.
 *
 * Preserves an admitted exact resumed request and closes every subject, meaning, or generation
 * mismatch as the adapter's single request-contract rejection. Raw continuation extraction remains
 * outside this projection.
 */
private fun Refinement<RelationRequest, RelationResumeFailure>.toProjection():
    OneHopRelationRequestProjection = when (this) {
    is Refinement.Refined -> OneHopRelationRequestProjection.Admitted(value)
    is Refinement.Rejected -> OneHopRelationRequestProjection.Rejected
}
