@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationReadFailure
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceReadFailure
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import kotlinx.serialization.Serializable

val indexSyncCliProjector =
    OperationOutcomeProjector<
        IndexSyncResult,
        IndexSyncQualification,
        IndexSyncRejection,
    > { outcome ->
        CanonicalIndexCliDocuments.project(outcome)
    }

val symbolDiscoverCliProjector =
    OperationOutcomeProjector<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
    > { outcome ->
        CanonicalSymbolCliDocuments.projectDiscovery(outcome)
    }

val symbolInspectCliProjector =
    OperationOutcomeProjector<
        SymbolInspectResult,
        SymbolInspectQualification,
        SymbolInspectRejection,
    > { outcome ->
        CanonicalSymbolCliDocuments.projectInspection(outcome)
    }

val sourceReadCliProjector =
    OperationOutcomeProjector<
        SourceReadResult,
        SourceReadQualification,
        SourceReadFailure,
    > { outcome ->
        CanonicalSourceReadCliDocuments.project(outcome)
    }

val relationReadCliProjector =
    OperationOutcomeProjector<
        RelationReadResult,
        RelationReadQualification,
        RelationReadFailure,
    > { outcome ->
        CanonicalReadCliDocuments.projectRelation(outcome)
    }

val traversalRunCliProjector =
    OperationOutcomeProjector<
        TraversalRunResult,
        TraversalRunQualification,
        TraversalRunFailure,
    > { outcome ->
        CanonicalReadCliDocuments.projectTraversal(outcome)
    }

val queryRunCliProjector =
    OperationOutcomeProjector<
        QueryRunResult,
        QueryRunQualification,
        QueryRunFailure,
    > { outcome ->
        CanonicalQueryCliDocuments.project(outcome)
    }

val diagnosticCheckCliProjector =
    OperationOutcomeProjector<
        DiagnosticCheckResult,
        DiagnosticCheckQualification,
        DiagnosticCheckFailure,
    > { outcome ->
        CanonicalReadCliDocuments.projectDiagnostics(outcome)
    }

val changePlanCliProjector =
    OperationOutcomeProjector<
        ChangePlanResult,
        ChangePlanQualification,
        ChangePlanRejection,
    > { outcome ->
        CanonicalChangeCliDocuments.projectPlan(outcome)
    }

val changeApplyCliProjector =
    OperationOutcomeProjector<
        ChangeApplyResult,
        ChangeApplyQualification,
        ChangeApplyRejection,
    > { outcome ->
        CanonicalChangeCliDocuments.projectApplication(outcome)
    }

val changeRecoverCliProjector =
    OperationOutcomeProjector<
        ChangeRecoverResult,
        ChangeRecoverQualification,
        ChangeRecoverRejection,
    > { outcome ->
        CanonicalChangeCliDocuments.projectRecovery(outcome)
    }

fun <
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
> projectClosedOutcome(
    outcome: OperationOutcome<Result, Qualification, Rejection>,
    complete: (Result, LiveReadCliEvidence?) -> CanonicalJsonDocument,
    qualified: (Result, Qualification, LiveReadCliEvidence?) -> CanonicalJsonDocument,
    rejected: (Rejection) -> CanonicalJsonDocument,
): ProjectedOperationOutcome =
    when (outcome) {
        is OperationOutcome.Complete ->
            ProjectedOperationOutcome.Complete(
                complete(outcome.evidence.payload, outcome.evidence.basis.liveDocument())
            )
        is OperationOutcome.Qualified ->
            ProjectedOperationOutcome.Qualified(
                qualified(outcome.evidence.payload, outcome.qualification, outcome.evidence.basis.liveDocument())
            )
        is OperationOutcome.Rejected -> ProjectedOperationOutcome.Rejected(rejected(outcome.reason))
    }

@Serializable
private data class RejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence =
        io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence.Absent,
)

private val rejectedDocumentFactory = CanonicalJsonDocument.generated(RejectedCliDocument.serializer())

fun canonicalRejectedDocument(
    operation: CanonicalOperation,
    reason: String,
    executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence =
        io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence.Absent,
): CanonicalJsonDocument =
    rejectedDocumentFactory.create(
        RejectedCliDocument(
            operation = operation.id.value,
            status = "rejected",
            reason = reason,
            executionBudget = executionBudget,
        )
    )

fun Enum<*>.cliName(): String = name.lowercase().replace('_', '-')
