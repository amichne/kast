package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.relation.contract.*

fun RelationFact.protocolDocument(authority: QueryReferenceAuthority): RelationFactDocument? {
    val occurrenceStart = ProtocolOffset.parse(occurrence.range.startInclusive).refinedOrNull() ?: return null
    val occurrenceEnd = ProtocolOffset.parse(occurrence.range.endExclusive).refinedOrNull() ?: return null
    val occurrenceRange = SourceRangeDocument.create(occurrenceStart, occurrenceEnd).refinedOrNull() ?: return null
    val occurrenceSelector =
        when (
            val issued =
                authority.issueRangeCandidate(
                    subject.lease,
                    occurrence.file,
                    occurrence.range.startInclusive,
                    occurrence.range.endExclusive,
                )
        ) {
            is CandidateSelectorTokenIssuance.Issued -> issued.selector
            is CandidateSelectorTokenIssuance.Rejected -> return null
        }
    return RelationFactDocument(
        meaning = meaning.protocolDocument(),
        source = source.protocolDocument(authority) ?: return null,
        target = target.protocolDocument(authority) ?: return null,
        occurrence =
            RelationOccurrenceDocument(
                candidateSelector = occurrenceSelector,
                file = ProtocolText.parse(occurrence.file.stableValue).refinedOrNull() ?: return null,
                range = occurrenceRange,
            ),
        provenance = provenance.protocolDocument(),
        coverage = coverage.protocolDocument(),
    )
}

private fun RelationEndpoint.protocolDocument(
    authority: QueryReferenceAuthority
): io.github.amichne.kast.protocol.contract.SymbolDocument? {
    val selector =
        when (val issued = authority.issueEndpoint(this)) {
            is RelationEndpointIssuance.Issued -> issued.selector
            is RelationEndpointIssuance.Rejected -> return null
        }
    return protocolDocument(selector)
}

fun RelationMeaning.protocolDocument(): RelationKindDocument =
    when (this) {
        RelationMeaning.References -> RelationKindDocument.REFERENCES
        RelationMeaning.Callers -> RelationKindDocument.CALLERS
        RelationMeaning.Callees -> RelationKindDocument.CALLEES
        RelationMeaning.Implementations -> RelationKindDocument.IMPLEMENTATIONS
        RelationMeaning.Inheritors -> RelationKindDocument.INHERITORS
        RelationMeaning.Overrides -> RelationKindDocument.OVERRIDES
        RelationMeaning.TypeUses -> RelationKindDocument.TYPE_USES
    }

private fun RelationProvenance.protocolDocument(): RelationProvenanceDocument =
    when (this) {
        RelationProvenance.K2_AUTHORED_SOURCE -> RelationProvenanceDocument.K2_AUTHORED_SOURCE
        RelationProvenance.K2_GENERATED_SOURCE -> RelationProvenanceDocument.K2_GENERATED_SOURCE
        RelationProvenance.K2_PROJECT_LIBRARY -> RelationProvenanceDocument.K2_PROJECT_LIBRARY
    }

private fun RelationFactCoverage.protocolDocument(): RelationFactCoverageDocument =
    when (this) {
        RelationFactCoverage.EXACT_COMPILER_CONFIRMED -> RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
