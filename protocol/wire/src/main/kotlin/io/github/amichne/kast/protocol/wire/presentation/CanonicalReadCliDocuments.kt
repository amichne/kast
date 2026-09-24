@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ReadRecoveryGuidance
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationReadFailure
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.recoveryGuidance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CanonicalReadCliDocuments {
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
            complete = { result, live ->
                relationCompleteFactory.create(
                    RelationCompleteCliDocument(
                        operation = CanonicalOperation.RELATION_READ.id.value,
                        status = "complete",
                        relations = result.relations.values.map { it.toCliDocument() },
                        omissions = result.omissions.values.map { it.toCliDocument() },
                        soundness = result.soundness,
                        executionBudget = result.executionBudget,
                        referenceAcquisitions = result.referenceAcquisitions,
                        live = live,
                    )
                )
            },
            qualified = { result, qualification, live ->
                relationQualifiedFactory.create(
                    RelationQualifiedCliDocument(
                        operation = CanonicalOperation.RELATION_READ.id.value,
                        status = "qualified",
                        relations = result.relations.values.map { it.toCliDocument() },
                        omissions = result.omissions.values.map { it.toCliDocument() },
                        soundness = result.soundness,
                        executionBudget = result.executionBudget,
                        referenceAcquisitions = result.referenceAcquisitions,
                        qualification = qualification.toCliDocument(result.executionBudget),
                        live = live,
                    )
                )
            },
            rejected = { rejection ->
                canonicalReadRejectedDocument(rejection)
            },
        )

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
private data class RelationCompleteCliDocument(
    val operation: String,
    val status: String,
    val relations: List<RelationFactCliDocument>,
    val omissions: List<RelationOmissionCliDocument>,
    val soundness: io.github.amichne.kast.protocol.contract.RelationSoundnessDocument,
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
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
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    val qualification: RelationQualificationCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
private sealed interface RelationQualificationCliDocument {
    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val knownMinimum: Int,
        val limitations: List<String>,
        val recovery: List<ReadRecoveryGuidance>,
        val continuation: String,
        val checkpoint: io.github.amichne.kast.protocol.contract.RelationCheckpointDocument,
        @SerialName("next_action") val nextAction: io.github.amichne.kast.protocol.contract.ReadResumeActionDocument,
    ) : RelationQualificationCliDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(
        val knownMinimum: Int,
        val limitations: List<String>,
        val recovery: List<ReadRecoveryGuidance>,
    ) : RelationQualificationCliDocument
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

private fun RelationReadQualification.toCliDocument(report: ExecutionBudgetReport?): RelationQualificationCliDocument =
    when (this) {
        is RelationReadQualification.Resumable ->
            RelationQualificationCliDocument.Resumable(
                knownMinimum = knownMinimum.value,
                limitations = limitations.map { it.cliName() },
                recovery = recoveryGuidance(report),
                continuation = continuation.value,
                checkpoint = checkpoint,
                nextAction = nextAction,
            )
        is RelationReadQualification.TerminalIncomplete ->
            RelationQualificationCliDocument.TerminalIncomplete(
                knownMinimum = knownMinimum.value,
                limitations = limitations.map { it.cliName() },
                recovery = recoveryGuidance(report),
            )
    }

private fun SourceRangeDocument.toReadCliDocument() = SourceRangeCliDocument(startInclusive.value, endExclusive.value)

private val relationCompleteFactory = CanonicalJsonDocument.generated(RelationCompleteCliDocument.serializer())
private val relationQualifiedFactory = CanonicalJsonDocument.generated(RelationQualifiedCliDocument.serializer())
