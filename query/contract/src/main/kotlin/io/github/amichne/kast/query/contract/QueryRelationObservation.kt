package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.fingerprintFields
import java.util.Collections

/** The enumeration question is independent of the emitted row kind and subsequent presentation predicates. */
data class QueryRelationQuestion
internal constructor(
    val subject: RelationEndpoint,
    val meaning: RelationMeaning,
    val requestedDomain: RelationSearchBoundary,
    val effectiveScope: SymbolSearchScope,
    val effectiveConstraints: SymbolDiscoveryConstraints,
    val domainFingerprint: RelationScopeFingerprint,
) {
    val provider: RelationProviderKind = RelationProviderKind.forMeaning(meaning)
}

sealed interface QueryRelationCoverage {
    data object Exhausted : QueryRelationCoverage

    data class Resumable internal constructor(val limitations: Set<RelationLimitation>) : QueryRelationCoverage

    data class TerminalIncomplete internal constructor(val limitations: Set<RelationLimitation>) : QueryRelationCoverage
}

/** Detached native-provider coverage; retention can preserve this observation but cannot produce it. */
data class QueryRelationObservation
private constructor(
    val question: QueryRelationQuestion,
    val coverage: QueryRelationCoverage,
    val scopeExclusions: List<io.github.amichne.kast.relation.contract.RelationScopeExclusion>,
    val callbackObservations: List<io.github.amichne.kast.relation.contract.RelationCallbackObservation>,
    val callableObservations: List<io.github.amichne.kast.relation.contract.RelationCallableObservation>,
) {
    val retainedBytes: Long
        get() =
            4096L
                .saturatedAdd(question.subject.file.stableValue.length.toLong().saturatedMultiply(2))
                .saturatedAdd(question.subject.name.value.length.toLong().saturatedMultiply(2))
                .saturatedAdd(question.subject.signature.canonicalEncoding().value.length.toLong().saturatedMultiply(2))
                .saturatedAdd(
                    question.effectiveConstraints.fingerprintFields().fold(0L) { bytes, field ->
                        bytes.saturatedAdd(field.length.toLong().saturatedMultiply(2))
                    }
                )
                .saturatedAdd(
                    SymbolSearchScope.snapshot(question.effectiveScope).toString().length.toLong().saturatedMultiply(2)
                )
                .saturatedAdd(
                    callbackObservations.fold(0L) { bytes, callback -> bytes.saturatedAdd(callback.retainedBytes) }
                )
                .saturatedAdd(
                    callableObservations.fold(0L) { bytes, callable -> bytes.saturatedAdd(callable.retainedBytes) }
                )
                .saturatedAdd(
                    scopeExclusions.fold(0L) { bytes, exclusion ->
                        bytes.saturatedAdd(exclusion.canonicalProjection().length.toLong().saturatedMultiply(2))
                    }
                )

    fun projectedUtf8Size(): Long = projectedRelationObservationBytes(retainedBytes)

    fun evidenceUnits(): List<QueryRelationObservation> =
        if (scopeExclusions.size + callbackObservations.size + callableObservations.size <= 1) listOf(this)
        else {
            val structural =
                copy(
                    scopeExclusions = emptyList(),
                    callbackObservations = emptyList(),
                    callableObservations = emptyList(),
                )
            Collections.unmodifiableList(
                scopeExclusions.map { structural.copy(scopeExclusions = Collections.singletonList(it)) } +
                    callbackObservations.map { structural.copy(callbackObservations = Collections.singletonList(it)) } +
                    callableObservations.map { structural.copy(callableObservations = Collections.singletonList(it)) }
            )
        }

    companion object {
        fun from(result: RelationReadResult.Complete): QueryRelationObservation =
            observation(
                result.batch.request,
                QueryRelationCoverage.Exhausted,
                result.batch.scopeExclusions,
                result.batch.callbackObservations,
                result.batch.callableObservations,
            )

        fun from(result: RelationReadResult.Qualified): QueryRelationObservation =
            observation(
                result.batch.request,
                when (val coverage = result.coverage) {
                    is RelationIncompleteCoverage.Resumable ->
                        QueryRelationCoverage.Resumable(Collections.unmodifiableSet(coverage.limitations.toSet()))
                    is RelationIncompleteCoverage.TerminalIncomplete ->
                        QueryRelationCoverage.TerminalIncomplete(
                            Collections.unmodifiableSet(coverage.limitations.toSet())
                        )
                },
                result.batch.scopeExclusions,
                result.batch.callbackObservations,
                result.batch.callableObservations,
            )

        private fun observation(
            request: io.github.amichne.kast.relation.contract.RelationRequest,
            coverage: QueryRelationCoverage,
            exclusions: List<io.github.amichne.kast.relation.contract.RelationScopeExclusion>,
            callbacks: List<io.github.amichne.kast.relation.contract.RelationCallbackObservation>,
            callables: List<io.github.amichne.kast.relation.contract.RelationCallableObservation>,
        ): QueryRelationObservation =
            QueryRelationObservation(
                QueryRelationQuestion(
                    request.subject,
                    request.meaning,
                    request.boundary,
                    request.searchScope,
                    request.searchConstraints,
                    request.scopeFingerprint,
                ),
                coverage,
                Collections.unmodifiableList(exclusions.toList()),
                Collections.unmodifiableList(callbacks.toList()),
                Collections.unmodifiableList(callables.toList()),
            )
    }
}

internal fun projectedRelationObservationBytes(retainedBytes: Long): Long =
    DOCUMENT_OVERHEAD_BYTES.saturatedAdd(retainedBytes.saturatedMultiply(UTF8_EXPANSION_FACTOR))

private const val DOCUMENT_OVERHEAD_BYTES = 4096L
private const val UTF8_EXPANSION_FACTOR = 3L
