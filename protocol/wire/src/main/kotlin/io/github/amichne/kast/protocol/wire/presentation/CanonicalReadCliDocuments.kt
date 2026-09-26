@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import kotlinx.serialization.Serializable

object CanonicalReadCliDocuments {
    fun projectDiagnostics(
        outcome: OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckFailure>
    ): ProjectedOperationOutcome = CanonicalDiagnosticCliDocuments.project(outcome)
}

@Serializable
data class RelationFactCliDocument(
    val meaning: String,
    val source: SymbolCliDocument,
    val target: SymbolCliDocument,
    val occurrence: RelationOccurrenceCliDocument,
    val provenance: String,
    val coverage: String,
)

@Serializable
data class RelationOccurrenceCliDocument(
    val candidateSelector: String,
    val file: String,
    val range: SourceRangeCliDocument,
)

fun RelationFactDocument.toCliDocument(): RelationFactCliDocument =
    RelationFactCliDocument(
        meaning.cliName(),
        source.toCliDocument(),
        target.toCliDocument(),
        RelationOccurrenceCliDocument(
            occurrence.candidateSelector.value,
            occurrence.file.value,
            occurrence.range.toReadCliDocument(),
        ),
        provenance.cliName(),
        coverage.cliName(),
    )

private fun SourceRangeDocument.toReadCliDocument() = SourceRangeCliDocument(startInclusive.value, endExclusive.value)
