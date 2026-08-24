package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliOutcomeProjector
import io.github.amichne.kast.cli.ProjectedCliOutcome
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
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import kotlinx.serialization.Serializable

internal val workspaceInspectCliProjector = CliOutcomeProjector<
    WorkspaceInspectResult,
    WorkspaceInspectQualification,
    WorkspaceInspectRejection,
    > { outcome -> CanonicalReadCliDocuments.projectWorkspace(outcome) }

internal val symbolDiscoverCliProjector = CliOutcomeProjector<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > { outcome -> CanonicalSymbolCliDocuments.projectDiscovery(outcome) }

internal val symbolResolveCliProjector = CliOutcomeProjector<
    SymbolResolveResult,
    SymbolResolveQualification,
    SymbolResolveRejection,
    > { outcome -> CanonicalSymbolCliDocuments.projectResolution(outcome) }

internal val symbolDescribeCliProjector = CliOutcomeProjector<
    SymbolDescribeResult,
    SymbolDescribeQualification,
    SymbolDescribeRejection,
    > { outcome -> CanonicalSymbolCliDocuments.projectDescription(outcome) }

internal val relationReadCliProjector = CliOutcomeProjector<
    RelationReadResult,
    RelationReadQualification,
    RelationReadRejection,
    > { outcome -> CanonicalReadCliDocuments.projectRelation(outcome) }

internal val traversalRunCliProjector = CliOutcomeProjector<
    TraversalRunResult,
    TraversalRunQualification,
    TraversalRunRejection,
    > { outcome -> CanonicalReadCliDocuments.projectTraversal(outcome) }

internal val diagnosticCheckCliProjector = CliOutcomeProjector<
    DiagnosticCheckResult,
    DiagnosticCheckQualification,
    DiagnosticCheckRejection,
    > { outcome -> CanonicalReadCliDocuments.projectDiagnostics(outcome) }

internal val changePlanCliProjector = CliOutcomeProjector<
    ChangePlanResult,
    ChangePlanQualification,
    ChangePlanRejection,
    > { outcome -> CanonicalChangeCliDocuments.projectPlan(outcome) }

internal val changeApplyCliProjector = CliOutcomeProjector<
    ChangeApplyResult,
    ChangeApplyQualification,
    ChangeApplyRejection,
    > { outcome -> CanonicalChangeCliDocuments.projectApplication(outcome) }

internal val changeVerifyCliProjector = CliOutcomeProjector<
    ChangeVerifyResult,
    ChangeVerifyQualification,
    ChangeVerifyRejection,
    > { outcome -> CanonicalChangeCliDocuments.projectVerification(outcome) }

internal val changeRecoverCliProjector = CliOutcomeProjector<
    ChangeRecoverResult,
    ChangeRecoverQualification,
    ChangeRecoverRejection,
    > { outcome -> CanonicalChangeCliDocuments.projectRecovery(outcome) }

internal fun <
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > projectClosedOutcome(
    outcome: OperationOutcome<Result, Qualification, Rejection>,
    complete: (Result) -> CliJsonDocument,
    qualified: (Result, Qualification) -> CliJsonDocument,
    rejected: (Rejection) -> CliJsonDocument,
): ProjectedCliOutcome = when (outcome) {
    is OperationOutcome.Complete -> ProjectedCliOutcome.Complete(
        complete(outcome.evidence.payload),
    )
    is OperationOutcome.Qualified -> ProjectedCliOutcome.Qualified(
        qualified(outcome.evidence.payload, outcome.qualification),
    )
    is OperationOutcome.Rejected -> ProjectedCliOutcome.Rejected(rejected(outcome.reason))
}

@Serializable
private data class RejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
)

private val rejectedDocumentFactory =
    CliJsonDocument.generated(RejectedCliDocument.serializer())

internal fun canonicalRejectedDocument(
    operation: CanonicalOperation,
    reason: String,
): CliJsonDocument = rejectedDocumentFactory.create(
    RejectedCliDocument(
        operation = operation.id.value,
        status = "rejected",
        reason = reason,
    ),
)

internal fun Enum<*>.cliName(): String = name.lowercase().replace('_', '-')
