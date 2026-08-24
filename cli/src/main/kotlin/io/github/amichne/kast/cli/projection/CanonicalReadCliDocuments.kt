package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
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
                    targets = result.targets.values.map { it.toCliDocument() },
                ),
            )
        },
        qualified = { result, qualification ->
            relationQualifiedFactory.create(
                RelationQualifiedCliDocument(
                    operation = CanonicalOperation.RELATION_READ.id.value,
                    status = "qualified",
                    targets = result.targets.values.map { it.toCliDocument() },
                    qualification = qualification.cliName(),
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
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            traversalCompleteFactory.create(
                TraversalCompleteCliDocument(
                    operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                    status = "complete",
                    reached = result.reached.values.map { it.toCliDocument() },
                ),
            )
        },
        qualified = { result, qualification ->
            traversalQualifiedFactory.create(
                TraversalQualifiedCliDocument(
                    operation = CanonicalOperation.TRAVERSAL_RUN.id.value,
                    status = "qualified",
                    reached = result.reached.values.map { it.toCliDocument() },
                    qualification = qualification.cliName(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.TRAVERSAL_RUN, rejection.cliName())
        },
    )

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
                    diagnostics = result.diagnostics.values.map { it.value },
                ),
            )
        },
        qualified = { result, qualification ->
            diagnosticQualifiedFactory.create(
                DiagnosticQualifiedCliDocument(
                    operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                    status = "qualified",
                    diagnostics = result.diagnostics.values.map { it.value },
                    qualification = qualification.cliName(),
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
    val targets: List<SymbolCliDocument>,
)

@Serializable
private data class RelationQualifiedCliDocument(
    val operation: String,
    val status: String,
    val targets: List<SymbolCliDocument>,
    val qualification: String,
)

@Serializable
private data class TraversalCompleteCliDocument(
    val operation: String,
    val status: String,
    val reached: List<SymbolCliDocument>,
)

@Serializable
private data class TraversalQualifiedCliDocument(
    val operation: String,
    val status: String,
    val reached: List<SymbolCliDocument>,
    val qualification: String,
)

@Serializable
private data class DiagnosticCompleteCliDocument(
    val operation: String,
    val status: String,
    val diagnostics: List<String>,
)

@Serializable
private data class DiagnosticQualifiedCliDocument(
    val operation: String,
    val status: String,
    val diagnostics: List<String>,
    val qualification: String,
)

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
