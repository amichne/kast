@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.ProjectedCliOutcome
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationReadFailure
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.reason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object CanonicalReadCliDocuments {
    fun projectRelation(
        outcome:
            OperationOutcome<
                RelationReadResult,
                RelationReadQualification,
                RelationReadFailure,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result ->
                relationCompleteFactory.create(
                    RelationCompleteCliDocument(
                        operation = CanonicalOperation.RELATION_READ.id.value,
                        status = "complete",
                        relations = result.relations.values.map { it.toCliDocument() },
                        omissions = result.omissions.values.map { it.toCliDocument() },
                        soundness = result.soundness,
                        executionBudget = result.executionBudget,
                    )
                )
            },
            qualified = { result, qualification ->
                relationQualifiedFactory.create(
                    RelationQualifiedCliDocument(
                        operation = CanonicalOperation.RELATION_READ.id.value,
                        status = "qualified",
                        relations = result.relations.values.map { it.toCliDocument() },
                        omissions = result.omissions.values.map { it.toCliDocument() },
                        soundness = result.soundness,
                        executionBudget = result.executionBudget,
                        qualification = qualification.toCliDocument(),
                    )
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(
                    CanonicalOperation.RELATION_READ,
                    rejection.reason().cliName(),
                    rejection.budgetPresence(),
                )
            },
        )

    fun projectTraversal(
        outcome:
            OperationOutcome<
                TraversalRunResult,
                TraversalRunQualification,
                TraversalRunFailure,
            >
    ): ProjectedCliOutcome =
        when (outcome) {
            is OperationOutcome.Complete ->
                ProjectedCliOutcome.Complete(
                    traversalCompleteDocument(outcome.evidence.payload, outcome.evidence.basis)
                )
            is OperationOutcome.Qualified ->
                ProjectedCliOutcome.Qualified(
                    traversalQualifiedDocument(outcome.evidence.payload, outcome.evidence.basis, outcome.qualification)
                )
            is OperationOutcome.Rejected ->
                ProjectedCliOutcome.Rejected(
                    canonicalRejectedDocument(
                        CanonicalOperation.TRAVERSAL_RUN,
                        outcome.reason.reason().cliName(),
                        outcome.reason.budgetPresence(),
                    )
                )
        }

    private fun traversalCompleteDocument(
        result: TraversalRunResult,
        basis: EvidenceBasis,
    ) =
        traversalCompleteFactory
            .create(
                TraversalCompleteCliDocument(
                    operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                    progress = result.progress,
                    executionBudget = result.executionBudget,
                    partialExpansions = result.partialExpansions.values.map { it.toCliDocument() },
                    strategy = result.strategy,
                    status = "complete",
                    graph =
                        normalizeTraversalGraph(
                            result.snapshotRoot,
                            basis,
                            result.records.values,
                        ),
                )
            )
            .withEvidence(basis)

    private fun traversalQualifiedDocument(
        result: TraversalRunResult,
        basis: EvidenceBasis,
        qualification: TraversalRunQualification,
    ) =
        traversalQualifiedFactory
            .create(
                TraversalQualifiedCliDocument(
                    operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                    progress = result.progress,
                    executionBudget = result.executionBudget,
                    partialExpansions = result.partialExpansions.values.map { it.toCliDocument() },
                    strategy = result.strategy,
                    status = "qualified",
                    graph =
                        normalizeTraversalGraph(
                            result.snapshotRoot,
                            basis,
                            result.records.values,
                        ),
                    qualification = qualification.toCliDocument(),
                )
            )
            .withEvidence(basis)

    fun projectDiagnostics(
        outcome:
            OperationOutcome<
                DiagnosticCheckResult,
                DiagnosticCheckQualification,
                DiagnosticCheckRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result ->
                diagnosticCompleteFactory.create(
                    DiagnosticCompleteCliDocument(
                        operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                        status = "complete",
                        diagnostics = result.diagnostics.values.map { it.toCliDocument() },
                    )
                )
            },
            qualified = { result, qualification ->
                diagnosticQualifiedFactory.create(
                    DiagnosticQualifiedCliDocument(
                        operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                        status = "qualified",
                        diagnostics = result.diagnostics.values.map { it.toCliDocument() },
                        qualification = qualification.toCliDocument(),
                    )
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.DIAGNOSTIC_CHECK, rejection.cliName())
            },
        )
}

@Serializable
private data class RelationCompleteCliDocument(
    val operation: String,
    val status: String,
    val relations: List<RelationFactCliDocument>,
    val omissions: List<RelationOmissionCliDocument>,
    val soundness: io.github.amichne.kast.protocol.contract.RelationSoundnessDocument,
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
)

@Serializable
private data class RelationQualifiedCliDocument(
    val operation: String,
    val status: String,
    val relations: List<RelationFactCliDocument>,
    val omissions: List<RelationOmissionCliDocument>,
    val soundness: io.github.amichne.kast.protocol.contract.RelationSoundnessDocument,
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
    val qualification: RelationQualificationCliDocument,
)

@Serializable
private sealed interface RelationQualificationCliDocument {
    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val knownMinimum: Int,
        val limitations: List<String>,
        val continuation: String,
    ) : RelationQualificationCliDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(
        val knownMinimum: Int,
        val limitations: List<String>,
    ) : RelationQualificationCliDocument
}

@Serializable
private data class DiagnosticCompleteCliDocument(
    val operation: String,
    val status: String,
    val diagnostics: List<DiagnosticCliDocument>,
)

@Serializable
private data class DiagnosticQualifiedCliDocument(
    val operation: String,
    val status: String,
    val diagnostics: List<DiagnosticCliDocument>,
    val qualification: DiagnosticQualificationCliDocument,
)

@Serializable
private data class DiagnosticQualificationCliDocument(
    val knownDiagnosticCount: Int,
    val resultLimitReached: Boolean,
    val analyzedFiles: List<String>,
    val limitations: List<DiagnosticLimitationCliDocument>,
)

@Serializable
private data class DiagnosticLimitationCliDocument(
    val file: String,
    val reason: String,
)

@Serializable
internal data class RelationFactCliDocument(
    val meaning: String,
    val source: SymbolCliDocument,
    val target: SymbolCliDocument,
    val occurrence: RelationOccurrenceCliDocument,
    val provenance: String,
    val coverage: String,
)

@Serializable
internal data class RelationOccurrenceCliDocument(
    val candidateSelector: String,
    val file: String,
    val range: SourceRangeCliDocument,
)

@Serializable
private data class DiagnosticCliDocument(
    val severity: String,
    val code: String,
    val message: String,
    val location: DiagnosticLocationCliDocument,
)

@Serializable
private data class DiagnosticLocationCliDocument(
    val candidateSelector: String,
    val file: String,
    val range: SourceRangeCliDocument,
)

internal fun RelationFactDocument.toCliDocument(): RelationFactCliDocument =
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

private fun DiagnosticDocument.toCliDocument(): DiagnosticCliDocument =
    DiagnosticCliDocument(
        severity.cliName(),
        code.value,
        message.value,
        DiagnosticLocationCliDocument(
            location.candidateSelector.value,
            location.file.value,
            SourceRangeCliDocument(
                location.range.startInclusive.value,
                location.range.endExclusive.value,
            ),
        ),
    )

private fun RelationReadQualification.toCliDocument(): RelationQualificationCliDocument =
    when (this) {
        is RelationReadQualification.Resumable ->
            RelationQualificationCliDocument.Resumable(
                knownMinimum = knownMinimum.value,
                limitations = limitations.map { it.cliName() },
                continuation = continuation.value,
            )
        is RelationReadQualification.TerminalIncomplete ->
            RelationQualificationCliDocument.TerminalIncomplete(
                knownMinimum = knownMinimum.value,
                limitations = limitations.map { it.cliName() },
            )
    }

private fun DiagnosticCheckQualification.toCliDocument() =
    DiagnosticQualificationCliDocument(
        knownDiagnosticCount = knownDiagnosticCount.value,
        resultLimitReached = resultLimitReached,
        analyzedFiles = analyzedFiles.map { it.value },
        limitations = limitations.map(DiagnosticLimitationDocument::toCliDocument),
    )

private fun DiagnosticLimitationDocument.toCliDocument() =
    DiagnosticLimitationCliDocument(
        file.value,
        reason.cliName(),
    )

private fun SourceRangeDocument.toReadCliDocument() = SourceRangeCliDocument(startInclusive.value, endExclusive.value)

private val relationCompleteFactory = CliJsonDocument.generated(RelationCompleteCliDocument.serializer())
private val relationQualifiedFactory = CliJsonDocument.generated(RelationQualifiedCliDocument.serializer())
private val diagnosticCompleteFactory = CliJsonDocument.generated(DiagnosticCompleteCliDocument.serializer())
private val diagnosticQualifiedFactory = CliJsonDocument.generated(DiagnosticQualifiedCliDocument.serializer())
