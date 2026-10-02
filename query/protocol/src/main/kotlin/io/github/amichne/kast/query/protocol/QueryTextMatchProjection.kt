package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocument
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocumentFailure
import io.github.amichne.kast.protocol.contract.SourceLineNumberDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceRange
import io.github.amichne.kast.symbol.contract.SymbolTextMatch

internal sealed interface QueryTextMatchProjection {
    data class Projected(val document: QueryTextMatchDocument) : QueryTextMatchProjection

    data object ScalarContractViolation : QueryTextMatchProjection

    data class EvidenceContractViolation(val reason: QueryTextMatchDocumentFailure) : QueryTextMatchProjection
}

/** Lexical evidence retains its verified coordinates without granting relationship authority. */
internal fun SymbolTextMatch.protocolDocument(): QueryTextMatchProjection {
    val admittedWord =
        ProtocolText.parse(word.value).refinedForQueryOrNull()
            ?: return QueryTextMatchProjection.ScalarContractViolation
    val admittedFile =
        ProtocolText.parse(file.value).refinedForQueryOrNull()
            ?: return QueryTextMatchProjection.ScalarContractViolation
    val admittedRange = range.protocolRange() ?: return QueryTextMatchProjection.ScalarContractViolation
    val admittedContext =
        ProtocolSourceText.parse(context).refinedForQueryOrNull()
            ?: return QueryTextMatchProjection.ScalarContractViolation
    val admittedContextRange = contextRange.protocolRange() ?: return QueryTextMatchProjection.ScalarContractViolation
    val admittedLine =
        SourceLineNumberDocument.parse(line.toLong()).refinedForQueryOrNull()
            ?: return QueryTextMatchProjection.ScalarContractViolation
    return when (
        val result =
            QueryTextMatchDocument.IndexedWord.create(
                admittedWord,
                admittedFile,
                admittedRange,
                admittedContext,
                admittedContextRange,
                admittedLine,
            )
    ) {
        is Refinement.Refined -> QueryTextMatchProjection.Projected(result.value)
        is Refinement.Rejected -> QueryTextMatchProjection.EvidenceContractViolation(result.failure)
    }
}

private fun SymbolDiscoverySourceRange.protocolRange(): SourceRangeDocument? {
    return SourceRangeDocument.create(
            ProtocolOffset.parse(startInclusive.value).refinedForQueryOrNull() ?: return null,
            ProtocolOffset.parse(endExclusive.value).refinedForQueryOrNull() ?: return null,
        )
        .refinedForQueryOrNull()
}
