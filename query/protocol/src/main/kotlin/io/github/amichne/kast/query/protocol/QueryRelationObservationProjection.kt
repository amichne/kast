package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.query.contract.QueryRelationCoverage
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

internal fun QueryRelationObservation.projectRelationObservation(
    authority: QueryReferenceAuthority
): QueryRelationObservationDocument? {
    val subject =
        when (val issued = authority.issueEndpoint(question.subject)) {
            is RelationEndpointIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is RelationEndpointIssuance.Rejected -> return null
        }
    val domain = relationDomainDocument(question.effectiveScope, question.effectiveConstraints) ?: return null
    val projectedCoverage =
        when (val coverage = coverage) {
            QueryRelationCoverage.Exhausted -> QueryRelationCoverageDocument.Exhausted
            is QueryRelationCoverage.Resumable ->
                QueryRelationCoverageDocument.Resumable(
                    QueryRelationLimitationsDocument.from(
                            coverage.limitations
                                .map { RelationLimitationDocument.valueOf(it.name) }
                                .sortedBy { it.ordinal }
                        )
                        .relationValueOrNull() ?: return null
                )
            is QueryRelationCoverage.TerminalIncomplete ->
                QueryRelationCoverageDocument.TerminalIncomplete(
                    QueryRelationLimitationsDocument.from(
                            coverage.limitations
                                .map { RelationLimitationDocument.valueOf(it.name) }
                                .sortedBy { it.ordinal }
                        )
                        .relationValueOrNull() ?: return null
                )
        }
    return QueryRelationObservationDocument(
        subject,
        question.meaning.protocolDocument(),
        RelationProviderDocument.valueOf(question.provider.name),
        question.requestedDomain.requestedDomainDocument(),
        domain,
        QueryRelationDomainFingerprint.parse(question.domainFingerprint.value).relationValueOrNull() ?: return null,
        projectedCoverage,
        scopeExclusions.mapProjected { it.projectScopeExclusion(authority) }.boundedProjectedOrNull() ?: return null,
        callbackObservations
            .mapProjected { it.projectCallbackObservation(authority, question) }
            .boundedProjectedOrNull() ?: return null,
        callableObservations.mapProjected { it.projectCallableObservation(authority) }.boundedProjectedOrNull()
            ?: return null,
    )
}

internal fun relationDomainDocument(
    scope: SymbolSearchScope,
    constraints: io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints,
): QueryRelationDomainDocument? {
    return QueryRelationDomainDocument(
        scope.semanticScopeDocument() ?: return null,
        scope.sourceKinds.document(),
        scope.generatedSources.document(),
        ((scope as? SymbolSearchScope.Workspace)?.libraries ?: SymbolLibraryPolicy.EXCLUDE).document(),
        constraints.sourceSets.document() ?: return null,
        constraints.directory?.let { it.document() ?: return null },
        constraints.packageName?.let { it.document() ?: return null },
        BoundedProtocolList.create(
                constraints.declarationKinds?.values.orEmpty().map { it.document() }.sortedBy { it.ordinal }
            )
            .relationValueOrNull() ?: return null,
    )
}

internal fun RelationSearchBoundary.requestedDomainDocument(): QueryRelationRequestedDomainDocument =
    when (this) {
        RelationSearchBoundary.RETAINED_SUBJECT -> QueryRelationRequestedDomainDocument.RETAINED_SEED
        RelationSearchBoundary.WORKSPACE_EXPANSION -> QueryRelationRequestedDomainDocument.WORKSPACE
        is RelationSearchBoundary.Explicit -> QueryRelationRequestedDomainDocument.SOURCE_DOMAIN
    }

private fun SymbolSearchScope.semanticScopeDocument(): QuerySemanticScopeDocument? =
    when (this) {
        is SymbolSearchScope.Workspace -> QuerySemanticScopeDocument.Workspace
        is SymbolSearchScope.ExactFile ->
            ProtocolText.parse(file.value).relationValueOrNull()?.let(QuerySemanticScopeDocument::ExactFile)
        is SymbolSearchScope.Module ->
            ProtocolText.parse(module.value).relationValueOrNull()?.let(QuerySemanticScopeDocument::Module)
        is SymbolSearchScope.GradleProject ->
            QuerySemanticScopeDocument.GradleProject(
                ProtocolText.parse(project.buildRoot.value).relationValueOrNull() ?: return null,
                ProtocolText.parse(project.projectPath.value).relationValueOrNull() ?: return null,
            )
        is SymbolSearchScope.SourceSet ->
            QuerySemanticScopeDocument.SourceSet(
                ProtocolText.parse(project.buildRoot.value).relationValueOrNull() ?: return null,
                ProtocolText.parse(project.projectPath.value).relationValueOrNull() ?: return null,
                ProtocolText.parse(sourceSet.value).relationValueOrNull() ?: return null,
            )
    }

private fun <Value, Failure> Refinement<Value, Failure>.relationValueOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
