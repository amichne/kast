@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import kotlinx.serialization.Serializable

object CanonicalReadCliDocuments {
    fun projectTraversal(
        outcome:
            OperationOutcome<
                TraversalRunResult,
                TraversalRunQualification,
                TraversalRunFailure,
            >
    ): ProjectedOperationOutcome =
        when (outcome) {
            is OperationOutcome.Complete ->
                ProjectedOperationOutcome.Complete(
                    traversalCompleteDocument(outcome.evidence.payload, outcome.evidence.basis)
                )
            is OperationOutcome.Qualified ->
                ProjectedOperationOutcome.Qualified(
                    traversalQualifiedDocument(outcome.evidence.payload, outcome.evidence.basis, outcome.qualification)
                )
            is OperationOutcome.Rejected ->
                ProjectedOperationOutcome.Rejected(canonicalReadRejectedDocument(outcome.reason))
        }

    private fun traversalCompleteDocument(
        result: TraversalRunResult,
        basis: EvidenceBasis,
    ) =
        traversalCompleteFactory.create(
            TraversalCompleteCliDocument(
                operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                progress = result.progress,
                executionBudget = result.executionBudget,
                referenceAcquisitions = result.referenceAcquisitions,
                partialExpansions = result.partialExpansions.values.map { it.toCliDocument() },
                strategy = result.strategy,
                status = "complete",
                graph =
                    normalizeTraversalGraph(
                        result.snapshotRoot,
                        basis,
                        result.records.values,
                    ),
                live = basis.liveDocument(),
            )
        )

    private fun traversalQualifiedDocument(
        result: TraversalRunResult,
        basis: EvidenceBasis,
        qualification: TraversalRunQualification,
    ) =
        traversalQualifiedFactory.create(
            TraversalQualifiedCliDocument(
                operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                progress = result.progress,
                executionBudget = result.executionBudget,
                referenceAcquisitions = result.referenceAcquisitions,
                partialExpansions = result.partialExpansions.values.map { it.toCliDocument() },
                strategy = result.strategy,
                status = "qualified",
                graph =
                    normalizeTraversalGraph(
                        result.snapshotRoot,
                        basis,
                        result.records.values,
                    ),
                qualification = qualification.toCliDocument(result.executionBudget),
                live = basis.liveDocument(),
            )
        )

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
