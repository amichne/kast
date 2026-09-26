package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.query.contract.QueryBindingName
import io.github.amichne.kast.query.contract.QueryCompositionInput
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryPlanAdmissionFailure
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName

internal sealed interface QuerySyntaxAdmission {
    data class Admitted(val syntax: QueryPlanSyntax) : QuerySyntaxAdmission

    data class ReferenceRejected(
        val position: Int,
        val reason: QueryReferenceRejectionReason,
    ) : QuerySyntaxAdmission

    data class StepReferenceRejected(
        val stepPosition: Int,
        val referencePosition: Int,
        val reason: QueryReferenceRejectionReason,
    ) : QuerySyntaxAdmission

    data object RequestRejected : QuerySyntaxAdmission
}

internal fun QueryRunRequest.Run.admitSyntax(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
    retained: Map<QueryFromDocument.Result, QueryRetainedResult>,
): QuerySyntaxAdmission {
    val source =
        when (val admitted = from.admitSource(lease, authority, retained)) {
            is QueryReferenceSourceAdmission.Admitted -> admitted.source
            is QueryReferenceSourceAdmission.Rejected ->
                return QuerySyntaxAdmission.ReferenceRejected(admitted.position, admitted.reason)
            QueryReferenceSourceAdmission.RequestRejected -> return QuerySyntaxAdmission.RequestRejected
        }
    val querySteps = mutableListOf<QueryStepSyntax>()
    steps.values.forEachIndexed { index, step ->
        when (val admitted = step.admitStep(lease, authority, retained)) {
            is QueryStepAdmission.Admitted -> querySteps += admitted.step
            is QueryStepAdmission.ReferenceRejected ->
                return QuerySyntaxAdmission.StepReferenceRejected(index, admitted.position, admitted.reason)
            QueryStepAdmission.RequestRejected -> return QuerySyntaxAdmission.RequestRejected
        }
    }
    val queryOutput = output.syntax() ?: return QuerySyntaxAdmission.RequestRejected
    return QuerySyntaxAdmission.Admitted(QueryPlanSyntax(source, querySteps, queryOutput))
}

private fun QueryFromDocument.admitSource(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
    retained: Map<QueryFromDocument.Result, QueryRetainedResult>,
): QueryReferenceSourceAdmission =
    when (this) {
        is QueryFromDocument.Symbols ->
            discovery.syntax()?.let { QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.Symbols(it)) }
                ?: QueryReferenceSourceAdmission.RequestRejected
        is QueryFromDocument.References -> values.values.admitExactReferences(lease, authority)
        is QueryFromDocument.Result ->
            retained[this]?.let {
                QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.Retained(it))
            } ?: QueryReferenceSourceAdmission.RequestRejected
    }

private sealed interface QueryStepAdmission {
    data class Admitted(val step: QueryStepSyntax) : QueryStepAdmission

    data class ReferenceRejected(val position: Int, val reason: QueryReferenceRejectionReason) : QueryStepAdmission

    data object RequestRejected : QueryStepAdmission
}

private fun QueryStepDocument.admitStep(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
    retained: Map<QueryFromDocument.Result, QueryRetainedResult>,
): QueryStepAdmission =
    when (this) {
        is QueryStepDocument.ProjectBinding ->
            name.domainName()?.let { QueryStepAdmission.Admitted(QueryStepSyntax.ProjectBinding(it)) }
                ?: QueryStepAdmission.RequestRejected
        is QueryStepDocument.Join -> admitJoin(retained)
        is QueryStepDocument.Concat -> admitConcat(lease, authority, retained)
        is QueryStepDocument.Intersect ->
            (retained[right] as? QueryRetainedResult.Symbols)?.let {
                QueryStepAdmission.Admitted(QueryStepSyntax.Intersect(it))
            } ?: QueryStepAdmission.RequestRejected
        is QueryStepDocument.Union ->
            (retained[right] as? QueryRetainedResult.Symbols)?.let {
                QueryStepAdmission.Admitted(QueryStepSyntax.Union(it))
            } ?: QueryStepAdmission.RequestRejected
        is QueryStepDocument.Difference ->
            (retained[right] as? QueryRetainedResult.Symbols)?.let {
                QueryStepAdmission.Admitted(QueryStepSyntax.Difference(it))
            } ?: QueryStepAdmission.RequestRejected
        else -> syntax()?.let(QueryStepAdmission::Admitted) ?: QueryStepAdmission.RequestRejected
    }

private fun QueryStepDocument.Concat.admitConcat(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
    retained: Map<QueryFromDocument.Result, QueryRetainedResult>,
): QueryStepAdmission =
    when (val value = input) {
        is QueryFromDocument.References ->
            when (val admitted = value.values.values.admitExactReferences(lease, authority)) {
                is QueryReferenceSourceAdmission.Admitted -> {
                    val source =
                        admitted.source as? QuerySourceSyntax.ExactReferences
                            ?: return QueryStepAdmission.RequestRejected
                    QueryStepAdmission.Admitted(
                        QueryStepSyntax.Concat(QueryCompositionInput.ExactReferences(source.references))
                    )
                }
                is QueryReferenceSourceAdmission.Rejected ->
                    QueryStepAdmission.ReferenceRejected(admitted.position, admitted.reason)
                QueryReferenceSourceAdmission.RequestRejected -> QueryStepAdmission.RequestRejected
            }
        is QueryFromDocument.Result ->
            (retained[value] as? QueryRetainedResult.Symbols)?.let {
                QueryStepAdmission.Admitted(QueryStepSyntax.Concat(QueryCompositionInput.Retained(it)))
            } ?: QueryStepAdmission.RequestRejected
    }

private fun QueryStepDocument.Join.admitJoin(
    retained: Map<QueryFromDocument.Result, QueryRetainedResult>
): QueryStepAdmission {
    val admittedMode = mode.domainMode() ?: return QueryStepAdmission.RequestRejected
    val admittedRight = retained[right] as? QueryRetainedResult.Symbols
        ?: return QueryStepAdmission.RequestRejected
    return QueryStepAdmission.Admitted(QueryStepSyntax.Join(admittedMode, admittedRight))
}

private fun QueryJoinModeDocument.domainMode(): QueryJoinMode? =
    when (this) {
        is QueryJoinModeDocument.Inner -> {
            val left = leftName.domainName() ?: return null
            val right = rightName.domainName() ?: return null
            QueryJoinMode.Inner.create(left, right).refinedOrNull()
        }
        QueryJoinModeDocument.Semi -> QueryJoinMode.Semi
        QueryJoinModeDocument.Anti -> QueryJoinMode.Anti
    }

private fun QueryBindingNameDocument.domainName(): QueryBindingName? = QueryBindingName.parse(value).refinedOrNull()

private sealed interface QueryReferenceSourceAdmission {
    data class Admitted(val source: QuerySourceSyntax) : QueryReferenceSourceAdmission

    data class Rejected(
        val position: Int,
        val reason: QueryReferenceRejectionReason,
    ) : QueryReferenceSourceAdmission

    data object RequestRejected : QueryReferenceSourceAdmission
}

private fun List<QueryReferenceDocument.ExactSymbol>.admitExactReferences(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
): QueryReferenceSourceAdmission {
    val selectors = mutableListOf<SymbolSelector>()
    forEachIndexed { index, reference ->
        val selector =
            when (val decoded = authority.restoreExact(reference.token, lease)) {
                is CanonicalSelectorDecoding.Decoded -> decoded.value
                is CanonicalSelectorDecoding.Rejected ->
                    return QueryReferenceSourceAdmission.Rejected(
                        index,
                        decoded.failure.queryRejection(reference.token),
                    )
            }
        selectors += selector
    }
    val references =
        QueryExactReferences.from(selectors).refinedOrNull() ?: return QueryReferenceSourceAdmission.RequestRejected
    return QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.ExactReferences(references))
}

private fun ProtocolText.belongsToOtherReferenceFamily(): Boolean =
    value.startsWith("candidate:") || value.startsWith("source-selector-v1:") || value.startsWith("source-selector-v2:")

private fun QueryDiscoveryDocument.syntax(): QueryDiscoverySyntax? {
    val kinds = declarationKinds.values.uniqueValues()?.mapTo(linkedSetOf()) { it.compilerKind() } ?: return null
    val admittedKinds = QueryDeclarationKinds.from(kinds).refinedOrNull() ?: return null
    val sets =
        scope.sourceSets.values.uniqueValues()?.mapTo(linkedSetOf()) {
            WorkspaceSourceSetName.parse(it.value).refinedOrNull() ?: return null
        } ?: return null
    val admittedSets = SymbolDiscoverySourceSets.Exact.from(sets).refinedOrNull() ?: return null
    val directory =
        scope.directory?.let {
            SymbolDiscoveryDirectoryConstraint(
                SymbolDiscoveryDirectory.parse(it.path.value).refinedOrNull() ?: return null,
                SymbolDiscoveryContainment.valueOf(it.containment.name),
            )
        }
    val packageName =
        scope.packageName?.let {
            SymbolDiscoveryPackageConstraint(
                SymbolDiscoveryPackage.parse(it.name.value).refinedOrNull() ?: return null,
                SymbolDiscoveryContainment.valueOf(it.containment.name),
            )
        }
    val queryMatch =
        when (val value = match) {
            QueryMatchDocument.All -> QueryMatch.All
            is QueryMatchDocument.Name ->
                QueryMatch.Name(
                    SymbolDiscoveryPattern.parse(value.text.value).refinedOrNull() ?: return null,
                    SymbolDiscoveryMatch.valueOf(value.matching.name),
                )
        }
    return QueryDiscoverySyntax(
        queryMatch,
        QueryScope.Restricted(admittedSets, directory, packageName),
        admittedKinds,
    )
}

private fun QueryDeclarationKindDocument.compilerKind(): CompilerSymbolKind =
    when (this) {
        QueryDeclarationKindDocument.CLASS -> CompilerSymbolKind.CLASSLIKE
        QueryDeclarationKindDocument.CONSTRUCTOR -> CompilerSymbolKind.CONSTRUCTOR
        QueryDeclarationKindDocument.FUNCTION -> CompilerSymbolKind.FUNCTION
        QueryDeclarationKindDocument.PROPERTY -> CompilerSymbolKind.PROPERTY
        QueryDeclarationKindDocument.TYPE_ALIAS -> CompilerSymbolKind.TYPE_ALIAS
    }

internal fun QueryPlanAdmissionFailure.protocolRejection(): QueryRunRejection =
    when (this) {
        QueryPlanAdmissionFailure.IncompleteRightInput ->
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RIGHT_INPUT_INCOMPLETE)
        is QueryPlanAdmissionFailure.UnknownBindingName ->
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.UNKNOWN_BINDING_NAME)
        QueryPlanAdmissionFailure.OutputTypeMismatch ->
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.OUTPUT_KIND_MISMATCH)
        is QueryPlanAdmissionFailure.UnsupportedDeclarationKind ->
            QueryRunRejection.SourceRejected(
                when (kind) {
                    CompilerSymbolKind.CLASSLIKE -> QueryDeclarationKindDocument.CLASS
                    CompilerSymbolKind.CONSTRUCTOR -> QueryDeclarationKindDocument.CONSTRUCTOR
                    CompilerSymbolKind.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
                    CompilerSymbolKind.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
                    CompilerSymbolKind.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
                },
                QuerySourceRejectionReason.UNSUPPORTED_DECLARATION_KIND,
            )
    }

internal fun queryPosition(raw: Int): ProtocolOffset =
    ProtocolOffset.parse(raw).refinedOrNull() ?: error("A bounded query position cannot be negative")

/** Structural uniqueness only. Semantic non-emptiness belongs to each strong collection type. */
private fun <Value> List<Value>.uniqueValues(): List<Value>? = takeIf { it.distinct().size == it.size }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

fun SemanticReadAuthority.evidenceBasis(): EvidenceBasis =
    when (this) {
        is SemanticReadLease -> EvidenceBasis.Published(generation)
        is LiveSemanticReadAuthority ->
            when (
                val admitted =
                    LiveReadEvidence.create(
                        workspaceRoot.value,
                        reference.host.value,
                        reference.epoch.value,
                        LiveReadContentView.valueOf(reference.contentView.name),
                        reference.version,
                    )
            ) {
                is Refinement.Refined -> EvidenceBasis.Live(admitted.value)
                is Refinement.Rejected -> error("An admitted live authority must retain valid evidence")
            }
    }

internal fun CanonicalSelectorDecodingFailure.queryRejection(token: ProtocolText): QueryReferenceRejectionReason =
    when (this) {
        CanonicalSelectorDecodingFailure.REVALIDATION_WRONG_KIND ->
            QueryReferenceRejectionReason.REVALIDATION_WRONG_KIND
        CanonicalSelectorDecodingFailure.REVALIDATION_UNRETAINED ->
            QueryReferenceRejectionReason.REVALIDATION_UNRETAINED
        CanonicalSelectorDecodingFailure.REVALIDATION_EXPIRED -> QueryReferenceRejectionReason.REVALIDATION_EXPIRED
        CanonicalSelectorDecodingFailure.REVALIDATION_CAPACITY -> QueryReferenceRejectionReason.REVALIDATION_CAPACITY
        CanonicalSelectorDecodingFailure.REVALIDATION_WORK_LIMIT_REACHED ->
            QueryReferenceRejectionReason.REVALIDATION_WORK_LIMIT_REACHED
        CanonicalSelectorDecodingFailure.REVALIDATION_TIME_LIMIT_REACHED ->
            QueryReferenceRejectionReason.REVALIDATION_TIME_LIMIT_REACHED
        CanonicalSelectorDecodingFailure.REVALIDATION_RETIRED -> QueryReferenceRejectionReason.REVALIDATION_RETIRED
        CanonicalSelectorDecodingFailure.REVALIDATION_CAPTURE_UNAVAILABLE ->
            QueryReferenceRejectionReason.REVALIDATION_CAPTURE_UNAVAILABLE
        CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_MISMATCH ->
            QueryReferenceRejectionReason.REVALIDATION_WORKSPACE_MISMATCH
        CanonicalSelectorDecodingFailure.REVALIDATION_OWNER_MISMATCH ->
            QueryReferenceRejectionReason.REVALIDATION_OWNER_MISMATCH
        CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_NOT_READY ->
            QueryReferenceRejectionReason.REVALIDATION_WORKSPACE_NOT_READY
        CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED ->
            QueryReferenceRejectionReason.REVALIDATION_BASIS_MOVED
        CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_CHANGED ->
            QueryReferenceRejectionReason.REVALIDATION_CONTENT_CHANGED
        CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_UNCOMMITTED ->
            QueryReferenceRejectionReason.REVALIDATION_CONTENT_UNCOMMITTED
        CanonicalSelectorDecodingFailure.REVALIDATION_SCOPE_REJECTED ->
            QueryReferenceRejectionReason.REVALIDATION_SCOPE_REJECTED
        CanonicalSelectorDecodingFailure.REVALIDATION_DECLARATION_MISSING ->
            QueryReferenceRejectionReason.REVALIDATION_DECLARATION_MISSING
        CanonicalSelectorDecodingFailure.REVALIDATION_UNSUPPORTED_DECLARATION ->
            QueryReferenceRejectionReason.REVALIDATION_UNSUPPORTED_DECLARATION
        CanonicalSelectorDecodingFailure.REVALIDATION_AMBIGUOUS -> QueryReferenceRejectionReason.REVALIDATION_AMBIGUOUS
        CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            QueryReferenceRejectionReason.REVALIDATION_COMPILER_IDENTITY_CHANGED
        CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_UNAVAILABLE ->
            QueryReferenceRejectionReason.REVALIDATION_COMPILER_UNAVAILABLE

        CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE -> QueryReferenceRejectionReason.INCOMPATIBLE_WORKSPACE
        CanonicalSelectorDecodingFailure.STALE_AUTHORITY ->
            if (token.value.startsWith("exact:v2:") || token.value.startsWith("candidate:v2:"))
                QueryReferenceRejectionReason.STALE_GENERATION
            else QueryReferenceRejectionReason.STALE_AUTHORITY
        CanonicalSelectorDecodingFailure.INCOMPATIBLE_AUTHORITY,
        CanonicalSelectorDecodingFailure.LIVE_AUTHORITY_REQUIRED -> QueryReferenceRejectionReason.INCOMPATIBLE_AUTHORITY
        CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION ->
            QueryReferenceRejectionReason.INCOMPATIBLE_REFERENCE_VERSION
        else ->
            if (token.belongsToOtherReferenceFamily()) QueryReferenceRejectionReason.WRONG_KIND
            else QueryReferenceRejectionReason.MALFORMED
    }
