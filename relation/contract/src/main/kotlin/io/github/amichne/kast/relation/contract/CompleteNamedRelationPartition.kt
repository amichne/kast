package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class NamedRelationPartitionFailure {
    UNSUPPORTED_MEANING,
    RESUMED_PAGE,
    NON_NAMED_EVIDENCE,
    REQUEST_MISMATCH,
}

/** Entire exhausted named adjacency, including empty absence. Mixed semantic evidence is never filtered away. */
class CompleteNamedRelationPartition
private constructor(
    val subject: RelationEndpoint,
    val meaning: RelationMeaning,
    val domain: RelationScopeFingerprint,
    val facts: List<RelationFact>,
) {
    val endpoints: List<RelationEndpoint> =
        Collections.unmodifiableList((listOf(subject) + facts.flatMap { listOf(it.source, it.target) }).distinct())
    val sourceFiles =
        Collections.unmodifiableSet((endpoints.map { it.file } + facts.map { it.occurrence.file }).toSet())
    val retainedBytes: Long =
        facts.fold(
            endpoints.fold(4096L) { bytes, endpoint ->
                bytes.addBytes(4096L).addBytes(endpoint.detachedTextUnits().multiplyBytes(2))
            }
        ) { bytes, fact ->
            bytes.addBytes(4096L).addBytes(fact.canonicalProjection().length.toLong().multiplyBytes(2))
        }

    fun admits(request: RelationRequest): Boolean =
        request.position == RelationReadPosition.Start &&
            request.subject.valueIdentity == subject.valueIdentity &&
            request.subject.scope == subject.scope &&
            request.subject.constraints == subject.constraints &&
            request.meaning == meaning &&
            request.scopeFingerprint == domain

    companion object {
        fun fromCompiler(
            complete: RelationCompilation.Complete
        ): Refinement<CompleteNamedRelationPartition, NamedRelationPartitionFailure> {
            val batch = complete.batch
            val request = batch.request
            if (request.meaning != RelationMeaning.Callers && request.meaning != RelationMeaning.Callees)
                return Refinement.Rejected(NamedRelationPartitionFailure.UNSUPPORTED_MEANING)
            if (request.position != RelationReadPosition.Start)
                return Refinement.Rejected(NamedRelationPartitionFailure.RESUMED_PAGE)
            if (
                batch.omissions.isNotEmpty() ||
                    batch.referenceOccurrences.isNotEmpty() ||
                    batch.scopeExclusions.isNotEmpty()
            )
                return Refinement.Rejected(NamedRelationPartitionFailure.NON_NAMED_EVIDENCE)
            if (batch.callbackObservations.isNotEmpty() || batch.callableObservations.isNotEmpty())
                return Refinement.Rejected(NamedRelationPartitionFailure.NON_NAMED_EVIDENCE)
            return Refinement.Refined(
                CompleteNamedRelationPartition(
                    request.subject,
                    request.meaning,
                    request.scopeFingerprint,
                    Collections.unmodifiableList(batch.facts.toList()),
                )
            )
        }
    }
}

sealed interface NamedRelationReadmissionFailure {
    data class Endpoint(val cause: CallbackSummaryReadmissionFailure) : NamedRelationReadmissionFailure

    data class Partition(val cause: NamedRelationPartitionFailure) : NamedRelationReadmissionFailure

    data class Fact(val cause: RelationFactFailure) : NamedRelationReadmissionFailure

    data class Batch(val cause: RelationBatchFailure) : NamedRelationReadmissionFailure
}

typealias NamedRelationReadmission = Refinement<RelationCompilation.Complete, NamedRelationReadmissionFailure>

/** Rebuilds against the current request subject and exact current compiler endpoint map; no request is rebound. */
fun CallbackEndpointReadmissions.readmitNamedRelations(
    previous: CompleteNamedRelationPartition,
    request: RelationRequest,
    examined: RelationWorkCount,
): NamedRelationReadmission {
    if (request.subject.lease !== authority || !previous.admits(request))
        return Refinement.Rejected(
            NamedRelationReadmissionFailure.Partition(NamedRelationPartitionFailure.REQUEST_MISMATCH)
        )
    if (previous.facts.size > request.budget.resources.resultLimit.value)
        return Refinement.Rejected(NamedRelationReadmissionFailure.Batch(RelationBatchFailure.RESULT_LIMIT_EXCEEDED))
    return when (
        val current = authority.withCurrentOwner {
            NamedRelationReadmissions(this, request).restore(previous, examined)
        }
    ) {
        is Refinement.Refined -> current.value
        is Refinement.Rejected ->
            Refinement.Rejected(
                NamedRelationReadmissionFailure.Endpoint(CallbackSummaryReadmissionFailure.Authority(current.failure))
            )
    }
}

private class NamedRelationReadmissions(
    private val endpoints: CallbackEndpointReadmissions,
    private val request: RelationRequest,
) {
    fun restore(previous: CompleteNamedRelationPartition, examined: RelationWorkCount): NamedRelationReadmission {
        when (val admitted = subject(previous.subject)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        val facts = mutableListOf<RelationFact>()
        for (old in previous.facts) when (val admitted = fact(old)) {
            is Refinement.Refined -> facts += admitted.value
            is Refinement.Rejected -> return admitted
        }
        return batch(facts.distinct().sorted(), examined)
    }

    private fun subject(previous: RelationEndpoint): Refinement<Unit, NamedRelationReadmissionFailure> =
        when (val restored = endpoints.endpoint(previous)) {
            is Refinement.Rejected -> Refinement.Rejected(NamedRelationReadmissionFailure.Endpoint(restored.failure))
            is Refinement.Refined ->
                when (RevalidatedRelationEndpoint.validate(request.subject, restored.value.evidence)) {
                    is Refinement.Refined -> Refinement.Refined(Unit)
                    is Refinement.Rejected ->
                        Refinement.Rejected(
                            NamedRelationReadmissionFailure.Endpoint(
                                CallbackSummaryReadmissionFailure.EndpointChanged(previous.valueIdentity)
                            )
                        )
                }
        }

    private fun fact(old: RelationFact): Refinement<RelationFact, NamedRelationReadmissionFailure> {
        val other = if (request.meaning == RelationMeaning.Callees) old.target else old.source
        val restored =
            when (val endpoint = endpoints.endpoint(other)) {
                is Refinement.Refined -> endpoint.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(NamedRelationReadmissionFailure.Endpoint(endpoint.failure))
            }
        val source = if (request.meaning == RelationMeaning.Callees) request.subject else restored
        val target = if (request.meaning == RelationMeaning.Callees) restored else request.subject
        return when (val fact = RelationFact.create(request, source, target, old.occurrence, old.provenance)) {
            is Refinement.Refined -> fact
            is Refinement.Rejected -> Refinement.Rejected(NamedRelationReadmissionFailure.Fact(fact.failure))
        }
    }

    private fun batch(ordered: List<RelationFact>, examined: RelationWorkCount): NamedRelationReadmission {
        val bytes =
            RelationByteCount.parse(
                ordered.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
            )
        val count = RelationResultCount.parse(ordered.size)
        check(bytes is Refinement.Refined && count is Refinement.Refined)
        return when (val batch = RelationBatch.create(request, ordered, bytes.value, examined, count.value)) {
            is Refinement.Refined -> Refinement.Refined(RelationCompilation.complete(batch.value))
            is Refinement.Rejected -> Refinement.Rejected(NamedRelationReadmissionFailure.Batch(batch.failure))
        }
    }
}
