package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryAdmissionCorrectionDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryElementTypeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.query.contract.QueryCandidateReferences
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryPlanAdmissionFailure
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.symbol.contract.CandidateSelector
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

internal fun QueryRunRequest.admitSyntax(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
): QuerySyntaxAdmission {
    val source =
        when (val value = from) {
            is QueryFromDocument.Candidates ->
                value.discovery.syntax()?.let(QuerySourceSyntax::Candidates)
                    ?: return QuerySyntaxAdmission.RequestRejected
            is QueryFromDocument.Symbols ->
                value.discovery.syntax()?.let(QuerySourceSyntax::Symbols) ?: return QuerySyntaxAdmission.RequestRejected
            is QueryFromDocument.References ->
                when (val references = value.values.values.admitReferenceSource(lease, authority)) {
                    is QueryReferenceSourceAdmission.Admitted -> references.source
                    is QueryReferenceSourceAdmission.Rejected ->
                        return QuerySyntaxAdmission.ReferenceRejected(
                            references.position,
                            references.reason,
                        )
                    QueryReferenceSourceAdmission.RequestRejected -> return QuerySyntaxAdmission.RequestRejected
                }
        }
    val querySteps = mutableListOf<QueryStepSyntax>()
    steps.values.forEachIndexed { index, step ->
        if (step is QueryStepDocument.AppendReferences) {
            val references = step.values.values.map(QueryReferenceDocument::ExactSymbol)
            when (val admitted = references.admitExactReferences(lease, authority)) {
                is QueryReferenceSourceAdmission.Admitted -> {
                    val source =
                        admitted.source as? QuerySourceSyntax.ExactReferences
                            ?: return QuerySyntaxAdmission.RequestRejected
                    querySteps += QueryStepSyntax.AppendReferences(source.references)
                }
                is QueryReferenceSourceAdmission.Rejected ->
                    return QuerySyntaxAdmission.StepReferenceRejected(index, admitted.position, admitted.reason)
                QueryReferenceSourceAdmission.RequestRejected -> return QuerySyntaxAdmission.RequestRejected
            }
        } else {
            querySteps += step.syntax() ?: return QuerySyntaxAdmission.RequestRejected
        }
    }
    val queryOutput = output.syntax() ?: return QuerySyntaxAdmission.RequestRejected
    return QuerySyntaxAdmission.Admitted(QueryPlanSyntax(source, querySteps, queryOutput))
}

private sealed interface QueryReferenceSourceAdmission {
    data class Admitted(val source: QuerySourceSyntax) : QueryReferenceSourceAdmission

    data class Rejected(
        val position: Int,
        val reason: QueryReferenceRejectionReason,
    ) : QueryReferenceSourceAdmission

    data object RequestRejected : QueryReferenceSourceAdmission
}

private fun List<QueryReferenceDocument>.admitReferenceSource(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
): QueryReferenceSourceAdmission {
    val first = firstOrNull() ?: return QueryReferenceSourceAdmission.RequestRejected
    return when (first) {
        is QueryReferenceDocument.DeclarationCandidate -> admitCandidateReferences(lease, authority)
        is QueryReferenceDocument.ExactSymbol -> admitExactReferences(lease, authority)
    }
}

private fun List<QueryReferenceDocument>.admitCandidateReferences(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
): QueryReferenceSourceAdmission {
    val selections = mutableListOf<io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection>()
    forEachIndexed { index, reference ->
        if (reference !is QueryReferenceDocument.DeclarationCandidate) {
            return QueryReferenceSourceAdmission.Rejected(index, QueryReferenceRejectionReason.WRONG_KIND)
        }
        val selector =
            when (val decoded = authority.restoreCandidate(reference.token, lease)) {
                is CanonicalSelectorDecoding.Decoded -> decoded.value
                is CanonicalSelectorDecoding.Rejected ->
                    return QueryReferenceSourceAdmission.Rejected(
                        index,
                        decoded.failure.queryRejection(reference.token, expectedExact = false),
                    )
            }
        val declaration =
            selector as? CandidateSelector.Declaration
                ?: return QueryReferenceSourceAdmission.Rejected(
                    index,
                    QueryReferenceRejectionReason.WRONG_KIND,
                )
        selections += declaration.selection
    }
    val references =
        QueryCandidateReferences.from(selections).refinedOrNull()
            ?: return QueryReferenceSourceAdmission.RequestRejected
    return QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.CandidateReferences(references))
}

private fun List<QueryReferenceDocument>.admitExactReferences(
    lease: SemanticReadAuthority,
    authority: QueryReferenceAuthority,
): QueryReferenceSourceAdmission {
    val selectors = mutableListOf<SymbolSelector>()
    forEachIndexed { index, reference ->
        if (reference !is QueryReferenceDocument.ExactSymbol) {
            return QueryReferenceSourceAdmission.Rejected(index, QueryReferenceRejectionReason.WRONG_KIND)
        }
        val selector =
            when (val decoded = authority.restoreExact(reference.token, lease)) {
                is CanonicalSelectorDecoding.Decoded -> decoded.value
                is CanonicalSelectorDecoding.Rejected ->
                    return QueryReferenceSourceAdmission.Rejected(
                        index,
                        decoded.failure.queryRejection(reference.token, expectedExact = true),
                    )
            }
        selectors += selector
    }
    val references =
        QueryExactReferences.from(selectors).refinedOrNull() ?: return QueryReferenceSourceAdmission.RequestRejected
    return QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.ExactReferences(references))
}

private fun ProtocolText.belongsToOtherReferenceFamily(expectedExact: Boolean): Boolean =
    if (expectedExact) {
        value.startsWith("candidate:") ||
            value.startsWith("source-selector-v1:") ||
            value.startsWith("source-selector-v2:")
    } else {
        value.startsWith("exact:") || value.startsWith("source-selector-v1:") || value.startsWith("source-selector-v2:")
    }

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
        is QueryPlanAdmissionFailure.StageTypeMismatch ->
            QueryRunRejection.PlanRejected(
                queryPosition(position.value),
                QueryElementTypeDocument.valueOf(required.name),
                QueryElementTypeDocument.valueOf(actual.name),
                QueryAdmissionCorrectionDocument.valueOf(correction.name),
            )
        is QueryPlanAdmissionFailure.OutputTypeMismatch ->
            QueryRunRejection.PlanRejected(
                queryPosition(position.value),
                QueryElementTypeDocument.valueOf(required.name),
                QueryElementTypeDocument.valueOf(actual.name),
                QueryAdmissionCorrectionDocument.valueOf(correction.name),
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

internal fun CanonicalSelectorDecodingFailure.queryRejection(
    token: ProtocolText,
    expectedExact: Boolean,
): QueryReferenceRejectionReason =
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
            if (token.belongsToOtherReferenceFamily(expectedExact)) QueryReferenceRejectionReason.WRONG_KIND
            else QueryReferenceRejectionReason.MALFORMED
    }
