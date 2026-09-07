package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.runtime.composition.installedSemanticBudgets
import io.github.amichne.kast.runtime.composition.protocol.graph.protocolDocument
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Public query admission and projection around the in-process typed evaluator. */
internal class CanonicalQueryRunHandler(
    private val workspace: WorkspaceInspectionOperations,
    private val operations: QueryOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<QueryRunRequest, QueryRunResult, QueryRunQualification, QueryRunRejection> {
    override suspend fun execute(
        request: QueryRunRequest,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val lease = when (val state = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace.readLease
            else -> return OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady)
        }
        val syntax = when (val admission = request.admitSyntax(lease)) {
            is QuerySyntaxAdmission.Admitted -> admission.syntax
            is QuerySyntaxAdmission.ReferenceRejected -> return OperationOutcome.Rejected(
                QueryRunRejection.ReferenceRejected(position(admission.position), admission.reason),
            )
            QuerySyntaxAdmission.RequestRejected -> return OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED),
            )
        }
        val plan = when (val admitted = QueryPlanCompiler.admit(syntax)) {
            is QueryPlanAdmission.Admitted -> admitted.plan
            is QueryPlanAdmission.Rejected -> return OperationOutcome.Rejected(
                admitted.failure.protocolRejection(),
            )
        }
        val budget = installedSemanticBudgets()?.query ?: return OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.BUDGET_REJECTED),
        )
        val execution = when (val admitted = QueryExecutionRequest.create(plan, lease, budget)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED),
            )
        }
        return when (val result = operations.run(execution)) {
            is QueryExecutionResult.Complete -> project(request.output, lease, result.result, null)
            is QueryExecutionResult.Qualified -> project(
                request.output,
                lease,
                result.result,
                result.coverage,
            )
            is QueryExecutionResult.Rejected -> OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(
                    QueryExecutionRejectionDocument.valueOf(result.reason.name),
                ),
            )
        }
    }

    private fun project(
        output: QueryOutputDocument,
        lease: SemanticReadLease,
        result: QueryResult,
        coverage: QueryCoverage.Qualified?,
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val items = when (val projected = projectItems(output, result.items)) {
            is QueryProjection.Projected -> projected.values
            QueryProjection.Rejected -> return contractRejected()
        }
        val failures = when (val projected = result.failures.mapProjected(::projectFailure)) {
            is QueryProjection.Projected -> projected.values
            QueryProjection.Rejected -> return contractRejected()
        }
        val boundedItems = BoundedProtocolList.create(items).refinedOrNull() ?: return contractRejected()
        val boundedFailures = BoundedProtocolList.create(failures).refinedOrNull() ?: return contractRejected()
        val envelope = EvidenceEnvelope(
            CanonicalOperation.QUERY_RUN.id,
            lease.generation,
            QueryRunResult(boundedItems, boundedFailures),
        )
        if (coverage == null) return OperationOutcome.Complete(envelope)
        val qualification = QueryRunQualification.create(
            QueryKnownMinimum.parse(coverage.knownMinimum.value).refinedOrNull()
                ?: return contractRejected(),
            coverage.limitations.map { QueryLimitationDocument.valueOf(it.name) },
        ).refinedOrNull() ?: return contractRejected()
        return OperationOutcome.Qualified(envelope, qualification)
    }

    private fun projectItems(
        output: QueryOutputDocument,
        items: QueryResultSet,
    ): QueryProjection<QueryResultItemDocument> = when {
        output is QueryOutputDocument.Candidates && items is QueryResultSet.Candidates ->
            items.values.mapProjected { candidate ->
                val token = when (val issued = authority.issueDeclarationCandidate(candidate.selection)) {
                    is CandidateSelectorTokenIssuance.Issued -> issued.selector
                    is CandidateSelectorTokenIssuance.Rejected -> return@mapProjected null
                }
                val document = candidate.selection.candidate.protocolDocument(token)
                    as? SymbolDiscoveryDocument.Declaration ?: return@mapProjected null
                QueryResultItemDocument.Candidate(
                    QueryReferenceDocument.DeclarationCandidate(token),
                    document.kind,
                    document.name.takeIf { QueryCandidateFieldDocument.NAME in output.fields.values },
                    QueryCandidateLocationDocument(document.file, document.offset)
                        .takeIf { QueryCandidateFieldDocument.LOCATION in output.fields.values },
                )
            }
        output is QueryOutputDocument.Symbols && items is QueryResultSet.Symbols ->
            items.values.mapProjected { symbol ->
                val token = when (val issued = authority.issueExact(symbol.selector)) {
                    is ExactSelectorIssuance.Issued -> issued.selector
                    is ExactSelectorIssuance.Rejected -> return@mapProjected null
                }
                val document = symbol.description.protocolDocument(token) ?: return@mapProjected null
                val connections = symbol.connections.mapProjected { it.protocolDocument(authority) }
                val boundedConnections = when (connections) {
                    is QueryProjection.Projected ->
                        BoundedProtocolList.create(connections.values).refinedOrNull()
                            ?: return@mapProjected null
                    QueryProjection.Rejected -> return@mapProjected null
                }
                QueryResultItemDocument.ExactSymbol(
                    QueryReferenceDocument.ExactSymbol(token),
                    document.kind,
                    document.name.takeIf { QuerySymbolFieldDocument.NAME in output.fields.values },
                    QueryExactLocationDocument(document.file, document.range)
                        .takeIf { QuerySymbolFieldDocument.LOCATION in output.fields.values },
                    document.compilerEvidence.signature
                        .takeIf { QuerySymbolFieldDocument.SIGNATURE in output.fields.values },
                    boundedConnections,
                )
            }
        else -> QueryProjection.Rejected
    }

    private fun projectFailure(failure: QueryItemFailure): QueryItemFailureDocument? = when (failure) {
        is QueryItemFailure.Refinement -> {
            val token = (authority.issueDeclarationCandidate(failure.candidate)
                as? CandidateSelectorTokenIssuance.Issued)?.selector ?: return null
            QueryItemFailureDocument.Refinement(
                QueryReferenceDocument.DeclarationCandidate(token),
                QueryExactFailureDocument.valueOf(failure.reason.name),
            )
        }
        is QueryItemFailure.ExactReference -> exactFailure(failure.selector, failure.reason)
        is QueryItemFailure.Visibility -> QueryItemFailureDocument.Predicate(
            exactReference(failure.selector) ?: return null,
            QueryPredicateFailureDocument.valueOf(failure.reason.name),
        )
        is QueryItemFailure.PredicateUnproven -> QueryItemFailureDocument.Predicate(
            exactReference(failure.selector) ?: return null,
            QueryPredicateFailureDocument.PREDICATE_UNPROVEN,
        )
        is QueryItemFailure.Relation -> QueryItemFailureDocument.Relation(
            exactReference(failure.selector) ?: return null,
            failure.meaning.protocolDocument(),
            QueryRelationFailureDocument.valueOf(failure.reason.name),
        )
    }

    private fun exactFailure(
        selector: SymbolSelector,
        reason: SymbolExactRejection,
    ): QueryItemFailureDocument.ExactReference? = QueryItemFailureDocument.ExactReference(
        exactReference(selector) ?: return null,
        QueryExactFailureDocument.valueOf(reason.name),
    )

    private fun exactReference(selector: SymbolSelector): QueryReferenceDocument.ExactSymbol? =
        when (val issued = authority.issueExact(selector)) {
            is ExactSelectorIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is ExactSelectorIssuance.Rejected -> null
        }

    private fun contractRejected(): OperationOutcome.Rejected<QueryRunRejection> =
        OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(
                QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION,
            ),
        )
}

private sealed interface QueryProjection<out Value> {
    data class Projected<Value>(val values: List<Value>) : QueryProjection<Value>
    data object Rejected : QueryProjection<Nothing>
}

private inline fun <Input, Output : Any> Iterable<Input>.mapProjected(
    transform: (Input) -> Output?,
): QueryProjection<Output> {
    val values = mutableListOf<Output>()
    for (input in this) values += transform(input) ?: return QueryProjection.Rejected
    return QueryProjection.Projected(values)
}

private sealed interface QuerySyntaxAdmission {
    data class Admitted(val syntax: QueryPlanSyntax) : QuerySyntaxAdmission
    data class ReferenceRejected(
        val position: Int,
        val reason: QueryReferenceRejectionReason,
    ) : QuerySyntaxAdmission
    data object RequestRejected : QuerySyntaxAdmission
}

private fun QueryRunRequest.admitSyntax(lease: SemanticReadLease): QuerySyntaxAdmission {
    val source = when (val value = from) {
        is QueryFromDocument.Candidates -> value.discovery.syntax()
            ?.let(QuerySourceSyntax::Candidates) ?: return QuerySyntaxAdmission.RequestRejected
        is QueryFromDocument.Symbols -> value.discovery.syntax()
            ?.let(QuerySourceSyntax::Symbols) ?: return QuerySyntaxAdmission.RequestRejected
        is QueryFromDocument.References -> when (
            val references = value.values.values.admitReferenceSource(lease)
        ) {
            is QueryReferenceSourceAdmission.Admitted -> references.source
            is QueryReferenceSourceAdmission.Rejected -> return QuerySyntaxAdmission.ReferenceRejected(
                references.position,
                references.reason,
            )
            QueryReferenceSourceAdmission.RequestRejected -> return QuerySyntaxAdmission.RequestRejected
        }
    }
    val querySteps = steps.values.map { it.syntax() ?: return QuerySyntaxAdmission.RequestRejected }
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
    lease: SemanticReadLease,
): QueryReferenceSourceAdmission {
    val first = firstOrNull() ?: return QueryReferenceSourceAdmission.RequestRejected
    return when (first) {
        is QueryReferenceDocument.DeclarationCandidate -> admitCandidateReferences(lease)
        is QueryReferenceDocument.ExactSymbol -> admitExactReferences(lease)
    }
}

private fun List<QueryReferenceDocument>.admitCandidateReferences(
    lease: SemanticReadLease,
): QueryReferenceSourceAdmission {
    val selections = mutableListOf<io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection>()
    forEachIndexed { index, reference ->
        if (reference !is QueryReferenceDocument.DeclarationCandidate) {
            return QueryReferenceSourceAdmission.Rejected(index, QueryReferenceRejectionReason.WRONG_KIND)
        }
        val selector = when (val decoded = CanonicalSelectorCodec.decodeCandidate(reference.token)) {
            is CanonicalSelectorDecoding.Decoded -> decoded.value
            is CanonicalSelectorDecoding.Rejected -> return QueryReferenceSourceAdmission.Rejected(
                index,
                if (reference.token.belongsToOtherReferenceFamily(expectedExact = false)) {
                    QueryReferenceRejectionReason.WRONG_KIND
                } else {
                    QueryReferenceRejectionReason.MALFORMED
                },
            )
        }
        val declaration = selector as? CandidateSelector.Declaration
            ?: return QueryReferenceSourceAdmission.Rejected(
                index,
                QueryReferenceRejectionReason.WRONG_KIND,
            )
        when (val rejection = declaration.selection.lease.referenceRejection(lease)) {
            null -> selections += declaration.selection
            else -> return QueryReferenceSourceAdmission.Rejected(index, rejection)
        }
    }
    val references = QueryCandidateReferences.from(selections).refinedOrNull()
        ?: return QueryReferenceSourceAdmission.RequestRejected
    return QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.CandidateReferences(references))
}

private fun List<QueryReferenceDocument>.admitExactReferences(
    lease: SemanticReadLease,
): QueryReferenceSourceAdmission {
    val selectors = mutableListOf<SymbolSelector>()
    forEachIndexed { index, reference ->
        if (reference !is QueryReferenceDocument.ExactSymbol) {
            return QueryReferenceSourceAdmission.Rejected(index, QueryReferenceRejectionReason.WRONG_KIND)
        }
        val selector = when (val decoded = CanonicalSelectorCodec.decodeExact(reference.token)) {
            is CanonicalSelectorDecoding.Decoded -> decoded.value
            is CanonicalSelectorDecoding.Rejected -> return QueryReferenceSourceAdmission.Rejected(
                index,
                if (reference.token.belongsToOtherReferenceFamily(expectedExact = true)) {
                    QueryReferenceRejectionReason.WRONG_KIND
                } else {
                    QueryReferenceRejectionReason.MALFORMED
                },
            )
        }
        when (val rejection = selector.lease.referenceRejection(lease)) {
            null -> selectors += selector
            else -> return QueryReferenceSourceAdmission.Rejected(index, rejection)
        }
    }
    val references = QueryExactReferences.from(selectors).refinedOrNull()
        ?: return QueryReferenceSourceAdmission.RequestRejected
    return QueryReferenceSourceAdmission.Admitted(QuerySourceSyntax.ExactReferences(references))
}

private fun SemanticReadLease.referenceRejection(
    current: SemanticReadLease,
): QueryReferenceRejectionReason? = when {
    workspaceRoot != current.workspaceRoot -> QueryReferenceRejectionReason.INCOMPATIBLE_WORKSPACE
    generation != current.generation -> QueryReferenceRejectionReason.STALE_GENERATION
    else -> null
}

private fun ProtocolText.belongsToOtherReferenceFamily(expectedExact: Boolean): Boolean =
    if (expectedExact) {
        value.startsWith("candidate:v2:") || value.startsWith("source-selector-v1:")
    } else {
        value.startsWith("exact:v2:") || value.startsWith("source-selector-v1:")
    }

private fun QueryDiscoveryDocument.syntax(): QueryDiscoverySyntax? {
    val kinds = declarationKinds.values.uniqueValues()
        ?.mapTo(linkedSetOf()) { it.compilerKind() } ?: return null
    val admittedKinds = QueryDeclarationKinds.from(kinds).refinedOrNull() ?: return null
    val sets = scope.sourceSets.values.uniqueValues()
        ?.mapTo(linkedSetOf()) { QuerySourceSet.valueOf(it.name) } ?: return null
    val admittedSets = QuerySourceSets.Exact.from(sets).refinedOrNull() ?: return null
    val directory = scope.directory?.let {
        SymbolDiscoveryDirectoryConstraint(
            SymbolDiscoveryDirectory.parse(it.path.value).refinedOrNull() ?: return null,
            SymbolDiscoveryContainment.valueOf(it.containment.name),
        )
    }
    val packageName = scope.packageName?.let {
        SymbolDiscoveryPackageConstraint(
            SymbolDiscoveryPackage.parse(it.name.value).refinedOrNull() ?: return null,
            SymbolDiscoveryContainment.valueOf(it.containment.name),
        )
    }
    val queryMatch = when (val value = match) {
        QueryMatchDocument.All -> QueryMatch.All
        is QueryMatchDocument.Name -> QueryMatch.Name(
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

private fun QueryStepDocument.syntax(): QueryStepSyntax? = when (this) {
    QueryStepDocument.Inspect -> QueryStepSyntax.Inspect
    is QueryStepDocument.Related -> QueryStepSyntax.Related(relation.meaning())
    QueryStepDocument.Distinct -> QueryStepSyntax.Distinct
    is QueryStepDocument.Where -> when (val value = predicate) {
        is QueryPredicateDocument.Visibility -> {
            val visibilities = value.values.values.uniqueValues()
                ?.mapTo(linkedSetOf()) { DeclarationVisibility.valueOf(it.name) } ?: return null
            QueryStepSyntax.Where(
                QueryPredicate.Visibility(
                    QueryVisibilitySelection.from(visibilities).refinedOrNull() ?: return null,
                ),
            )
        }
    }
}

private fun QueryOutputDocument.syntax(): QueryOutputSyntax? = when (this) {
    is QueryOutputDocument.Candidates -> {
        val selected = fields.values.uniqueValues()
            ?.mapTo(linkedSetOf()) { QueryCandidateField.valueOf(it.name) } ?: return null
        QueryOutputSyntax.Candidates(QueryCandidateFields.from(selected).refinedOrNull() ?: return null)
    }
    is QueryOutputDocument.Symbols -> {
        val selected = fields.values.uniqueValues()
            ?.mapTo(linkedSetOf()) { QuerySymbolField.valueOf(it.name) } ?: return null
        QueryOutputSyntax.Symbols(QuerySymbolFields.from(selected).refinedOrNull() ?: return null)
    }
}

private fun QueryDeclarationKindDocument.compilerKind(): CompilerSymbolKind = when (this) {
    QueryDeclarationKindDocument.CLASS -> CompilerSymbolKind.CLASSLIKE
    QueryDeclarationKindDocument.CONSTRUCTOR -> CompilerSymbolKind.CONSTRUCTOR
    QueryDeclarationKindDocument.FUNCTION -> CompilerSymbolKind.FUNCTION
    QueryDeclarationKindDocument.PROPERTY -> CompilerSymbolKind.PROPERTY
    QueryDeclarationKindDocument.TYPE_ALIAS -> CompilerSymbolKind.TYPE_ALIAS
}

private fun RelationKindDocument.meaning(): RelationMeaning = when (this) {
    RelationKindDocument.REFERENCES -> RelationMeaning.References
    RelationKindDocument.CALLERS -> RelationMeaning.Callers
    RelationKindDocument.CALLEES -> RelationMeaning.Callees
    RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
    RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
    RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
    RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
}

private fun QueryPlanAdmissionFailure.protocolRejection(): QueryRunRejection = when (this) {
    is QueryPlanAdmissionFailure.UnsupportedDeclarationKind -> QueryRunRejection.SourceRejected(
        when (kind) {
            CompilerSymbolKind.CLASSLIKE -> QueryDeclarationKindDocument.CLASS
            CompilerSymbolKind.CONSTRUCTOR -> QueryDeclarationKindDocument.CONSTRUCTOR
            CompilerSymbolKind.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
            CompilerSymbolKind.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
            CompilerSymbolKind.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
        },
        QuerySourceRejectionReason.UNSUPPORTED_DECLARATION_KIND,
    )
    is QueryPlanAdmissionFailure.StageTypeMismatch -> QueryRunRejection.PlanRejected(
        position(position.value),
        QueryElementTypeDocument.valueOf(required.name),
        QueryElementTypeDocument.valueOf(actual.name),
        QueryAdmissionCorrectionDocument.valueOf(correction.name),
    )
    is QueryPlanAdmissionFailure.OutputTypeMismatch -> QueryRunRejection.PlanRejected(
        position(position.value),
        QueryElementTypeDocument.valueOf(required.name),
        QueryElementTypeDocument.valueOf(actual.name),
        QueryAdmissionCorrectionDocument.valueOf(correction.name),
    )
}

private fun position(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refinedOrNull()
    ?: error("A bounded query position cannot be negative")

/** Structural uniqueness only. Semantic non-emptiness belongs to each strong collection type. */
private fun <Value> List<Value>.uniqueValues(): List<Value>? =
    takeIf { it.distinct().size == it.size }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
