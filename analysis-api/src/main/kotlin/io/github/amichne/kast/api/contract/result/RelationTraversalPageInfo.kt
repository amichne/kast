package io.github.amichne.kast.api.contract.result

import kotlin.ConsistentCopyVisibility
import kotlinx.serialization.Serializable

@Serializable
@ConsistentCopyVisibility
data class RelationTraversalPageInfo private constructor(
    @Serializable(with = RelationshipResultEvidence.CompleteSerializer::class)
    val evidence: RelationshipResultEvidence.Complete,
    val returnedCount: Int,
    val visitedCandidateCount: Int,
    val truncated: Boolean,
    val nextHandle: RelationTraversalHandle? = null,
) {
    val cardinality: ResultCardinality
        get() = evidence.cardinality

    init {
        require(returnedCount >= 0) { "Returned relationship count must be non-negative" }
        require(visitedCandidateCount >= 0) {
            "Visited relationship candidate count must be non-negative"
        }
        require(returnedCount <= cardinality.knownMinimum()) {
            "Returned relationship count cannot exceed established cardinality"
        }
        require(truncated == (nextHandle != null)) {
            "Relationship page truncation must agree with next-handle presence"
        }
        if (nextHandle != null) {
            require(cardinality.knownMinimum().toLong() >= returnedCount.toLong() + 1L) {
                "A relationship continuation requires one additional proven result"
            }
        }
    }

    companion object {
        fun create(
            evidence: RelationshipResultEvidence.Complete,
            returnedCount: Int,
            returnedBefore: Int,
            visitedCandidateCount: Int,
            candidateVisitLimit: Int,
            nextHandle: RelationTraversalHandle?,
        ): RelationTraversalPageInfo {
            require(returnedBefore >= 0) {
                "Previously returned relationship count must be non-negative"
            }
            require(candidateVisitLimit >= 0) {
                "Relationship candidate visit limit must be non-negative"
            }
            require(visitedCandidateCount <= candidateVisitLimit) {
                "Visited relationship candidate count cannot exceed its declared limit"
            }

            val cardinality = evidence.cardinality
            val cumulativeReturned = returnedBefore.toLong() + returnedCount.toLong()
            require(cardinality.knownMinimum().toLong() >= cumulativeReturned) {
                "Relationship cardinality cannot understate cumulative returned results"
            }
            if (nextHandle != null) {
                require(cardinality.knownMinimum().toLong() >= cumulativeReturned + 1L) {
                    "A relationship continuation requires one additional cumulative proven result"
                }
            }

            return RelationTraversalPageInfo(
                evidence = evidence,
                returnedCount = returnedCount,
                visitedCandidateCount = visitedCandidateCount,
                truncated = nextHandle != null,
                nextHandle = nextHandle,
            )
        }
    }
}
