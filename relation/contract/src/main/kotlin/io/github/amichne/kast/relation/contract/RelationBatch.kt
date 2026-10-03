package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets

enum class RelationMeasureFailure {
    NEGATIVE
}

@JvmInline
value class RelationByteCount private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RelationByteCount, RelationMeasureFailure>`.
         *
         * Establishes a non-negative canonical detached byte count. [RelationMeasureFailure] is the closed expected
         * failure. Raw counts may be extracted only by compiler collectors, metrics, and transport.
         */
        fun parse(raw: Long): Refinement<RelationByteCount, RelationMeasureFailure> =
            if (raw >= 0L) Refinement.Refined(RelationByteCount(raw))
            else Refinement.Rejected(RelationMeasureFailure.NEGATIVE)
    }
}

@JvmInline
value class RelationWorkCount private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RelationWorkCount, RelationMeasureFailure>`.
         *
         * Establishes a non-negative number of native items examined. [RelationMeasureFailure] is the closed expected
         * failure. Raw counts may be extracted only by compiler collectors, metrics, and continuation issuance.
         */
        fun parse(raw: Long): Refinement<RelationWorkCount, RelationMeasureFailure> =
            if (raw >= 0L) Refinement.Refined(RelationWorkCount(raw))
            else Refinement.Rejected(RelationMeasureFailure.NEGATIVE)
    }
}

/** Number of exact facts retained in a detached page, distinct from semantic work. */
@JvmInline
value class RelationResultCount private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<RelationResultCount, RelationMeasureFailure> =
            if (raw >= 0) Refinement.Refined(RelationResultCount(raw))
            else Refinement.Rejected(RelationMeasureFailure.NEGATIVE)
    }
}

enum class RelationBatchFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    GENERATION_MISMATCH,
    NON_EXACT_FACT,
    RESULT_LIMIT_EXCEEDED,
    BYTE_LIMIT_EXCEEDED,
    WORK_LIMIT_EXCEEDED,
    NON_DETERMINISTIC_ORDER,
    ENCODED_BYTE_COUNT_MISMATCH,
    RESULT_COUNT_MISMATCH,
    INVALID_OMISSION_EVIDENCE,
    INVALID_SCOPE_EXCLUSION,
}

@ConsistentCopyVisibility
data class RelationBatch
private constructor(
    val request: RelationRequest,
    val facts: List<RelationFact>,
    val encodedBytes: RelationByteCount,
    val examinedWorkUnits: RelationWorkCount,
    val resultCount: RelationResultCount,
    val omissions: List<RelationOmissionEvidence>,
    val referenceOccurrences: List<RelationReferenceOccurrence>,
    val scopeExclusions: List<RelationScopeExclusion>,
) {
    val semanticResultCount: Int
        get() =
            referenceOccurrences.size +
                facts.count { fact ->
                    referenceOccurrences.none { it.occurrence == fact.occurrence && it.target == fact.target }
                }

    fun withOmissions(values: List<RelationOmissionEvidence>): Refinement<RelationBatch, RelationBatchFailure> {
        if (omissions.isNotEmpty() && omissions != values) {
            return Refinement.Rejected(RelationBatchFailure.INVALID_OMISSION_EVIDENCE)
        }
        if (
            values.any { it.provider != request.providerCursor.provider } ||
                values.map { it.reason } != values.map { it.reason }.distinct().sortedBy { it.ordinal }
        )
            return Refinement.Rejected(RelationBatchFailure.INVALID_OMISSION_EVIDENCE)
        return Refinement.Refined(copy(omissions = java.util.Collections.unmodifiableList(values.toList())))
    }

    companion object {
        private fun semanticResultCount(
            facts: List<RelationFact>,
            occurrences: List<RelationReferenceOccurrence>,
        ): Int =
            occurrences.size +
                facts.count { fact ->
                    occurrences.none { it.occurrence == fact.occurrence && it.target == fact.target }
                }

        private fun admitEncodedBytes(
            facts: List<RelationFact>,
            occurrences: List<RelationReferenceOccurrence>,
            exclusions: List<RelationScopeExclusion>,
            expected: RelationByteCount,
        ): Refinement<Unit, RelationBatchFailure> {
            val factBytes = facts.sumOf { it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong() }
            val occurrenceBytes = occurrences.sumOf {
                it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
            val exclusionBytes = exclusions.sumOf {
                it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
            return if (factBytes + occurrenceBytes + exclusionBytes == expected.value) Refinement.Refined(Unit)
            else Refinement.Rejected(RelationBatchFailure.ENCODED_BYTE_COUNT_MISMATCH)
        }

        /**
         * Proof transition: `(RelationRequest, List<RelationFact>, RelationByteCount, RelationWorkCount) ->
         * Refinement<RelationBatch, RelationBatchFailure>`.
         *
         * Establishes exact request ownership, authority, meaning, individual edge coverage, deterministic uniqueness,
         * and request bounds for a detached one-hop page. [RelationBatchFailure] is the closed expected failure. Raw
         * collections and measures may enter only at a bounded compiler collector or transport decoder.
         */
        fun create(
            request: RelationRequest,
            facts: List<RelationFact>,
            encodedBytes: RelationByteCount,
            examinedWorkUnits: RelationWorkCount,
            resultCount: RelationResultCount,
            referenceOccurrences: List<RelationReferenceOccurrence> = emptyList(),
            scopeExclusions: List<RelationScopeExclusion> = emptyList(),
        ): Refinement<RelationBatch, RelationBatchFailure> {
            if (
                scopeExclusions.any { !it.belongsTo(request) } || scopeExclusions != scopeExclusions.distinct().sorted()
            )
                return Refinement.Rejected(RelationBatchFailure.INVALID_SCOPE_EXCLUSION)
            if (
                scopeExclusions.size + semanticResultCount(facts, referenceOccurrences) >
                    request.budget.resources.resultLimit.value
            )
                return Refinement.Rejected(RelationBatchFailure.RESULT_LIMIT_EXCEEDED)
            when (val admitted = request.admitOccurrences(referenceOccurrences)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            when (val admitted = request.admitFacts(facts)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            val semanticCount = semanticResultCount(facts, referenceOccurrences)
            when (val admitted = admitBounds(request, semanticCount, resultCount, encodedBytes, examinedWorkUnits)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            if (
                facts != facts.distinct().sorted() || referenceOccurrences != referenceOccurrences.distinct().sorted()
            ) {
                return Refinement.Rejected(RelationBatchFailure.NON_DETERMINISTIC_ORDER)
            }
            when (val admitted = admitEncodedBytes(facts, referenceOccurrences, scopeExclusions, encodedBytes)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                RelationBatch(
                    request,
                    java.util.Collections.unmodifiableList(facts.toList()),
                    encodedBytes,
                    examinedWorkUnits,
                    resultCount,
                    emptyList(),
                    java.util.Collections.unmodifiableList(referenceOccurrences.toList()),
                    java.util.Collections.unmodifiableList(scopeExclusions.toList()),
                )
            )
        }
    }
}

private fun RelationRequest.admitOccurrences(
    referenceOccurrences: List<RelationReferenceOccurrence>
): Refinement<Unit, RelationBatchFailure> {
    if (referenceOccurrences.any { it.target !== this.subject }) {
        return Refinement.Rejected(RelationBatchFailure.SUBJECT_MISMATCH)
    }
    if (referenceOccurrences.any { it.meaning != this.meaning }) {
        return Refinement.Rejected(RelationBatchFailure.MEANING_MISMATCH)
    }
    if (referenceOccurrences.any { it.authority != this.subject.lease.identity }) {
        return Refinement.Rejected(RelationBatchFailure.GENERATION_MISMATCH)
    }
    return Refinement.Refined(Unit)
}

private fun RelationRequest.admitFacts(facts: List<RelationFact>): Refinement<Unit, RelationBatchFailure> {
    if (facts.any { it.subject !== this.subject }) {
        return Refinement.Rejected(RelationBatchFailure.SUBJECT_MISMATCH)
    }
    if (facts.any { it.meaning != this.meaning }) {
        return Refinement.Rejected(RelationBatchFailure.MEANING_MISMATCH)
    }
    if (facts.any { it.authority != this.subject.lease.identity }) {
        return Refinement.Rejected(RelationBatchFailure.GENERATION_MISMATCH)
    }
    if (facts.any { it.coverage != RelationFactCoverage.EXACT_COMPILER_CONFIRMED }) {
        return Refinement.Rejected(RelationBatchFailure.NON_EXACT_FACT)
    }
    return Refinement.Refined(Unit)
}

private fun admitBounds(
    request: RelationRequest,
    semanticCount: Int,
    resultCount: RelationResultCount,
    encodedBytes: RelationByteCount,
    examinedWorkUnits: RelationWorkCount,
): Refinement<Unit, RelationBatchFailure> {
    if (semanticCount > request.budget.resources.resultLimit.value) {
        return Refinement.Rejected(RelationBatchFailure.RESULT_LIMIT_EXCEEDED)
    }
    if (resultCount.value != semanticCount) {
        return Refinement.Rejected(RelationBatchFailure.RESULT_COUNT_MISMATCH)
    }
    if (encodedBytes.value > request.budget.returnedBytes.value) {
        return Refinement.Rejected(RelationBatchFailure.BYTE_LIMIT_EXCEEDED)
    }
    if (examinedWorkUnits.value > request.budget.resources.workUnitLimit.value) {
        return Refinement.Rejected(RelationBatchFailure.WORK_LIMIT_EXCEEDED)
    }
    return Refinement.Refined(Unit)
}
