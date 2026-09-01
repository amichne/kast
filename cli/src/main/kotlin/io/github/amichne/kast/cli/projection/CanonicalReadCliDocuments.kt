package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.ProjectedCliOutcome
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import kotlinx.serialization.Serializable

internal object CanonicalReadCliDocuments {
    fun projectWorkspace(
        outcome: OperationOutcome<
            WorkspaceInspectResult,
            WorkspaceInspectQualification,
            WorkspaceInspectRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            workspaceCompleteFactory.create(
                WorkspaceCompleteCliDocument(
                    operation = CanonicalOperation.WORKSPACE_INSPECT.id.value,
                    status = "complete",
                    canonicalRoot = result.canonicalRoot.value,
                    state = result.state.cliName(),
                ),
            )
        },
        qualified = { result, qualification ->
            workspaceQualifiedFactory.create(
                WorkspaceQualifiedCliDocument(
                    operation = CanonicalOperation.WORKSPACE_INSPECT.id.value,
                    status = "qualified",
                    canonicalRoot = result.canonicalRoot.value,
                    state = result.state.cliName(),
                    qualification = qualification.cliName(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(
                CanonicalOperation.WORKSPACE_INSPECT,
                when (rejection) {
                    WorkspaceInspectRejection.ROOT_UNAVAILABLE -> "root-unavailable"
                    WorkspaceInspectRejection.RUNTIME_BLOCKED -> "runtime-blocked"
                },
            )
        },
    )

    fun projectRelation(
        outcome: OperationOutcome<
            RelationReadResult,
            RelationReadQualification,
            RelationReadRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            relationCompleteFactory.create(
                RelationCompleteCliDocument(
                    operation = CanonicalOperation.RELATION_READ.id.value,
                    status = "complete",
                    relations = result.relations.values.map { it.toCliDocument() },
                ),
            )
        },
        qualified = { result, qualification ->
            relationQualifiedFactory.create(
                RelationQualifiedCliDocument(
                    operation = CanonicalOperation.RELATION_READ.id.value,
                    status = "qualified",
                    relations = result.relations.values.map { it.toCliDocument() },
                    qualification = qualification.toCliDocument(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.RELATION_READ, rejection.cliName())
        },
    )

    fun projectTraversal(
        outcome: OperationOutcome<
            TraversalRunResult,
            TraversalRunQualification,
            TraversalRunRejection,
            >,
    ): ProjectedCliOutcome = when (outcome) {
        is OperationOutcome.Complete -> ProjectedCliOutcome.Complete(
            traversalCompleteFactory.create(
                TraversalCompleteCliDocument(
                    operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                    status = "complete",
                    graph = normalizeTraversalGraph(
                        outcome.evidence.payload.snapshotRoot,
                        outcome.evidence.generation,
                        outcome.evidence.payload.records.values,
                    ),
                ),
            ),
        )
        is OperationOutcome.Qualified -> ProjectedCliOutcome.Qualified(
            traversalQualifiedFactory.create(
                TraversalQualifiedCliDocument(
                    operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                    status = "qualified",
                    graph = normalizeTraversalGraph(
                        outcome.evidence.payload.snapshotRoot,
                        outcome.evidence.generation,
                        outcome.evidence.payload.records.values,
                    ),
                    qualification = outcome.qualification.toCliDocument(),
                ),
            ),
        )
        is OperationOutcome.Rejected -> ProjectedCliOutcome.Rejected(
            canonicalRejectedDocument(
                CanonicalOperation.TRAVERSAL_RUN,
                outcome.reason.cliName(),
            ),
        )
    }

    fun projectDiagnostics(
        outcome: OperationOutcome<
            DiagnosticCheckResult,
            DiagnosticCheckQualification,
            DiagnosticCheckRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            diagnosticCompleteFactory.create(
                DiagnosticCompleteCliDocument(
                    operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                    status = "complete",
                    diagnostics = result.diagnostics.values.map { it.toCliDocument() },
                ),
            )
        },
        qualified = { result, qualification ->
            diagnosticQualifiedFactory.create(
                DiagnosticQualifiedCliDocument(
                    operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                    status = "qualified",
                    diagnostics = result.diagnostics.values.map { it.toCliDocument() },
                    qualification = qualification.toCliDocument(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.DIAGNOSTIC_CHECK, rejection.cliName())
        },
    )
}

@Serializable
private data class WorkspaceCompleteCliDocument(
    val operation: String,
    val status: String,
    val canonicalRoot: String,
    val state: String,
)

@Serializable
private data class WorkspaceQualifiedCliDocument(
    val operation: String,
    val status: String,
    val canonicalRoot: String,
    val state: String,
    val qualification: String,
)

@Serializable
private data class RelationCompleteCliDocument(
    val operation: String,
    val status: String,
    val relations: List<RelationFactCliDocument>,
)

@Serializable
private data class RelationQualifiedCliDocument(
    val operation: String,
    val status: String,
    val relations: List<RelationFactCliDocument>,
    val qualification: RelationQualificationCliDocument,
)

@Serializable
private data class RelationQualificationCliDocument(
    val knownMinimum: Int,
    val limitations: List<String>,
    val continuation: String,
)

@Serializable
private data class TraversalCompleteCliDocument(
    val operation: String,
    val status: String,
    val graph: NormalizedTraversalGraphCliDocument,
)

@Serializable
private data class TraversalQualifiedCliDocument(
    val operation: String,
    val status: String,
    val graph: NormalizedTraversalGraphCliDocument,
    val qualification: TraversalQualificationCliDocument,
)

@Serializable
private data class TraversalQualificationCliDocument(
    val limitations: List<String>,
    val relationLimitations: List<String>,
    val continuation: String,
)

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
private data class RelationFactCliDocument(
    val meaning: String,
    val source: SymbolCliDocument,
    val target: SymbolCliDocument,
    val occurrence: RelationOccurrenceCliDocument,
    val provenance: String,
    val coverage: String,
)

@Serializable
private data class RelationOccurrenceCliDocument(
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
    val file: String,
    val range: SourceRangeCliDocument,
)

private fun RelationFactDocument.toCliDocument(): RelationFactCliDocument =
    RelationFactCliDocument(
        meaning.cliName(),
        source.toCliDocument(),
        target.toCliDocument(),
        RelationOccurrenceCliDocument(occurrence.file.value, occurrence.range.toReadCliDocument()),
        provenance.cliName(),
        coverage.cliName(),
    )

private fun DiagnosticDocument.toCliDocument(): DiagnosticCliDocument = DiagnosticCliDocument(
    severity.cliName(),
    code.value,
    message.value,
    DiagnosticLocationCliDocument(
        location.file.value,
        SourceRangeCliDocument(
            location.range.startInclusive.value,
            location.range.endExclusive.value,
        ),
    ),
)

private fun RelationReadQualification.toCliDocument() = RelationQualificationCliDocument(
    knownMinimum = knownMinimum.value,
    limitations = limitations.map { it.cliName() },
    continuation = continuation.value,
)

private fun TraversalRunQualification.toCliDocument() = TraversalQualificationCliDocument(
    limitations = limitations.map { it.cliName() },
    relationLimitations = relationLimitations.map { it.cliName() },
    continuation = continuation.value,
)

private fun DiagnosticCheckQualification.toCliDocument() = DiagnosticQualificationCliDocument(
    knownDiagnosticCount = knownDiagnosticCount.value,
    resultLimitReached = resultLimitReached,
    analyzedFiles = analyzedFiles.map { it.value },
    limitations = limitations.map(DiagnosticLimitationDocument::toCliDocument),
)

private fun DiagnosticLimitationDocument.toCliDocument() = DiagnosticLimitationCliDocument(
    file.value,
    reason.cliName(),
)

private fun io.github.amichne.kast.protocol.contract.SourceRangeDocument.toReadCliDocument() =
    SourceRangeCliDocument(startInclusive.value, endExclusive.value)

private val workspaceCompleteFactory =
    CliJsonDocument.generated(WorkspaceCompleteCliDocument.serializer())
private val workspaceQualifiedFactory =
    CliJsonDocument.generated(WorkspaceQualifiedCliDocument.serializer())
private val relationCompleteFactory =
    CliJsonDocument.generated(RelationCompleteCliDocument.serializer())
private val relationQualifiedFactory =
    CliJsonDocument.generated(RelationQualifiedCliDocument.serializer())
private val traversalCompleteFactory =
    CliJsonDocument.generated(TraversalCompleteCliDocument.serializer())
private val traversalQualifiedFactory =
    CliJsonDocument.generated(TraversalQualifiedCliDocument.serializer())
private val diagnosticCompleteFactory =
    CliJsonDocument.generated(DiagnosticCompleteCliDocument.serializer())
private val diagnosticQualifiedFactory =
    CliJsonDocument.generated(DiagnosticQualifiedCliDocument.serializer())
