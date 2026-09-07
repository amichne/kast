package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.CandidateQueryStage
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryCandidate
import io.github.amichne.kast.query.contract.QueryContainment
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryResultSet
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSet
import io.github.amichne.kast.query.contract.QuerySourceSets
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import java.nio.charset.StandardCharsets

/** Monotonic clock isolated at the evaluator effect boundary. */
fun interface QueryNanoClock {
    fun now(): Long
}

private object SystemQueryNanoClock : QueryNanoClock {
    override fun now(): Long = System.nanoTime()
}

/** In-process evaluator for the closed linear candidate/exact-symbol query algebra. */
class QueryService(
    private val discovery: io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations,
    private val exact: SymbolExactOperations,
    private val source: SourceReadOperations,
    private val relations: RelationOperations,
    private val clock: QueryNanoClock = SystemQueryNanoClock,
) : QueryOperations {
    override suspend fun run(request: QueryExecutionRequest): QueryExecutionResult {
        val state = QueryExecutionState(request, clock)
        return when (val plan = request.plan) {
            is AdmittedQueryPlan.Candidates -> {
                val candidates = when (val discovered = discover(plan.source, state)) {
                    is DiscoveryExecution.Discovered -> discovered.values
                    is DiscoveryExecution.Rejected -> return discovered.result
                }
                executeCandidate(candidates, plan.stage, state, plan.source)
            }
            is AdmittedQueryPlan.Symbols -> {
                val candidates = when (val discovered = discover(plan.source, state)) {
                    is DiscoveryExecution.Discovered -> discovered.values
                    is DiscoveryExecution.Rejected -> return discovered.result
                }
                executeExact(distinct(refine(candidates, plan.source, state), state), plan.stage, state)
            }
            is AdmittedQueryPlan.CandidateReferences -> {
                executeCandidate(plan.source.values, plan.stage, state, null)
            }
            is AdmittedQueryPlan.ExactReferences -> {
                executeExact(distinct(revalidate(plan.source.values, state), state), plan.stage, state)
            }
        }
    }

    private suspend fun discover(
        syntax: QueryDiscoverySyntax,
        state: QueryExecutionState,
    ): DiscoveryExecution {
        val kinds = discoveryKinds(syntax)
        val selections = mutableListOf<SymbolDiscoverySelection>()
        for (kind in kinds) {
            val childBudget = state.discoveryBudget()
                ?: return DiscoveryExecution.Discovered(selections)
            val request = SymbolDiscoveryRequest(
                scope = SymbolSearchScopeRequest(
                    state.request.lease,
                    SymbolSearchScope.Workspace(
                        sourceKinds(syntax.scope),
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                ),
                target = when (val match = syntax.match) {
                    QueryMatch.All -> SymbolDiscoveryTarget.All(kind)
                    is QueryMatch.Name -> SymbolDiscoveryTarget.Name(
                        kind,
                        match.pattern,
                        match.policy,
                    )
                },
                budget = childBudget,
                constraints = constraints(syntax),
            )
            val outcome = when (val result = discovery.discover(request)) {
                is SymbolDiscoveryResult.Discovered -> result.outcome
                is SymbolDiscoveryResult.Rejected -> return DiscoveryExecution.Rejected(
                    QueryExecutionResult.Rejected(
                        QueryExecutionRejection.DISCOVERY_REJECTED,
                        result.reason,
                    ),
                )
            }
            state.observeTime()
            val batch = outcome.batch()
            state.consume(batch.examinedWorkUnits.value, batch.encodedBytes.value)
            if (outcome is SymbolDiscoveryOutcome.Qualified) {
                state.limit(QueryLimitation.DISCOVERY_INCOMPLETE)
            }
            batch.candidates.indices.forEach { ordinal ->
                when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
                    is Refinement.Refined -> selections += selected.value
                    is Refinement.Rejected -> state.contractViolation = true
                }
            }
        }
        return DiscoveryExecution.Discovered(
            selections.distinctBy { it.candidate },
        )
    }

    private suspend fun executeCandidate(
        input: List<SymbolDiscoverySelection>,
        stage: CandidateQueryStage,
        state: QueryExecutionState,
        discovery: QueryDiscoverySyntax?,
    ): QueryExecutionResult = when (stage) {
        is CandidateQueryStage.Distinct -> executeCandidate(
            input.distinctBy { it.candidate },
            stage.next,
            state,
            discovery,
        )
        is CandidateQueryStage.Inspect -> executeExact(
            refine(input, discovery, state),
            stage.next,
            state,
        )
        is CandidateQueryStage.Emit -> finish(
            QueryResultSet.Candidates(
                state.boundResults(input).map(::QueryCandidate),
            ),
            state,
        )
    }

    private suspend fun executeExact(
        input: List<QuerySymbol>,
        stage: ExactQueryStage,
        state: QueryExecutionState,
    ): QueryExecutionResult = when (stage) {
        is ExactQueryStage.Distinct -> executeExact(distinct(input, state), stage.next, state)
        is ExactQueryStage.Where -> executeExact(
            where(input, stage.predicate, state),
            stage.next,
            state,
        )
        is ExactQueryStage.Related -> executeExact(
            related(input, stage.meaning, state),
            stage.next,
            state,
        )
        is ExactQueryStage.Emit -> finish(
            QueryResultSet.Symbols(
                state.boundResults(input).map { symbol ->
                    symbol.copy(connections = state.boundConnections(symbol.connections))
                },
            ),
            state,
        )
    }

    private suspend fun refine(
        candidates: List<SymbolDiscoverySelection>,
        discovery: QueryDiscoverySyntax?,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
        for (candidate in candidates) {
            if (!state.consumeUnit()) break
            when (val result = exact.resolve(SymbolResolutionRequest(candidate))) {
                is SymbolResolutionResult.Resolved -> {
                    val selector = result.symbol.selector
                    if (discovery == null || selector.kind in discovery.declarationKinds.values) {
                        add(QuerySymbol(SymbolDescription.from(selector), emptyList()))
                    }
                }
                is SymbolResolutionResult.Rejected -> {
                    state.failure(QueryItemFailure.Refinement(candidate, result.reason))
                    state.limit(QueryLimitation.REFINEMENT_INCOMPLETE)
                }
            }
            state.observeTime()
        }
    }

    private suspend fun revalidate(
        selectors: List<SymbolSelector>,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
        for (selector in selectors) {
            if (!state.consumeUnit()) break
            when (val result = exact.describe(ExactSymbolRequest(selector))) {
                is SymbolDescriptionResult.Described ->
                    add(QuerySymbol(result.description, emptyList()))
                is SymbolDescriptionResult.Rejected -> {
                    state.failure(QueryItemFailure.ExactReference(selector, result.reason))
                    state.limit(QueryLimitation.REFINEMENT_INCOMPLETE)
                    state.contractViolation = result.reason ==
                        io.github.amichne.kast.symbol.contract.SymbolExactRejection.COMPILER_CONTRACT_VIOLATION
                }
            }
            state.observeTime()
        }
    }

    private suspend fun where(
        input: List<QuerySymbol>,
        predicate: QueryPredicate,
        state: QueryExecutionState,
    ): List<QuerySymbol> = when (predicate) {
        is QueryPredicate.Visibility -> buildList {
            for (symbol in input) {
                if (!state.consumeUnit()) break
                val result = source.read(visibilityRequest(symbol.selector, predicate, state))
                when (result) {
                    is SourceReadResult.Complete -> if (
                        result.entities.any { entity ->
                            entity is SourceEntity.Declaration &&
                                entity.visibility in predicate.values.values
                        }
                    ) {
                        add(symbol)
                    }
                    is SourceReadResult.Qualified -> {
                        state.failure(QueryItemFailure.PredicateUnproven(symbol.selector))
                        state.limit(QueryLimitation.VISIBILITY_INCOMPLETE)
                    }
                    is SourceReadResult.Rejected -> {
                        state.failure(QueryItemFailure.Visibility(symbol.selector, result.reason))
                        state.limit(QueryLimitation.VISIBILITY_INCOMPLETE)
                    }
                }
                state.observeTime()
            }
        }
    }

    private suspend fun related(
        input: List<QuerySymbol>,
        meaning: RelationMeaning,
        state: QueryExecutionState,
    ): List<QuerySymbol> = buildList {
        for (symbol in input) {
            val budget = state.relationBudget() ?: break
            when (val result = relations.read(RelationRequest.start(symbol.selector, meaning, budget))) {
                is RelationReadResult.Complete -> {
                    state.consume(
                        result.batch.examinedWorkUnits.value,
                        result.batch.encodedBytes.value,
                    )
                    addAll(
                        result.batch.facts.map { fact ->
                            fact.toQuerySymbol(state.boundConnections(symbol.connections), state)
                        },
                    )
                }
                is RelationReadResult.Qualified -> {
                    state.consume(
                        result.batch.examinedWorkUnits.value,
                        result.batch.encodedBytes.value,
                    )
                    state.limit(QueryLimitation.RELATION_INCOMPLETE)
                    addAll(
                        result.batch.facts.map { fact ->
                            fact.toQuerySymbol(state.boundConnections(symbol.connections), state)
                        },
                    )
                }
                is RelationReadResult.Rejected -> {
                    state.failure(QueryItemFailure.Relation(symbol.selector, meaning, result.reason))
                    state.limit(QueryLimitation.RELATION_INCOMPLETE)
                }
                }
            }
            state.observeTime()
        }

    private fun finish(
        items: QueryResultSet,
        state: QueryExecutionState,
    ): QueryExecutionResult {
        state.observeTime()
        if (state.contractViolation) {
            return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
        val failures = state.boundedFailures()
        val boundedItems = when (items) {
            is QueryResultSet.Candidates -> QueryResultSet.Candidates(
                state.boundOutput(items.values, QueryCandidate::projectedUtf8Size),
            )
            is QueryResultSet.Symbols -> QueryResultSet.Symbols(
                state.boundOutput(items.values, QuerySymbol::projectedUtf8Size),
            )
        }
        val result = QueryResult(boundedItems, failures)
        val count = when (boundedItems) {
            is QueryResultSet.Candidates -> boundedItems.values.size
            is QueryResultSet.Symbols -> boundedItems.values.size
        }.queryCount()
        return if (state.limitations.isEmpty()) {
            QueryExecutionResult.Complete(result, QueryCoverage.Complete(count))
        } else {
            val coverage = when (val created = QueryCoverage.Qualified.create(
                count,
                state.limitations,
            )) {
                is Refinement.Refined -> created.value
                is Refinement.Rejected ->
                    return QueryExecutionResult.Rejected(
                        QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION,
                    )
            }
            QueryExecutionResult.Qualified(result, coverage)
        }
    }
}

private sealed interface DiscoveryExecution {
    data class Discovered(val values: List<SymbolDiscoverySelection>) : DiscoveryExecution
    data class Rejected(val result: QueryExecutionResult.Rejected) : DiscoveryExecution
}

private class QueryExecutionState(
    val request: QueryExecutionRequest,
    private val clock: QueryNanoClock,
) {
    private val startedAt = clock.now()
    private var usedWork = 0L
    private var usedBytes = 0L
    private val failures = mutableListOf<QueryItemFailure>()
    val limitations = linkedSetOf<QueryLimitation>()
    var contractViolation: Boolean = false

    fun consume(work: Long, bytes: Long) {
        usedWork = saturatedAdd(usedWork, work)
        usedBytes = saturatedAdd(usedBytes, bytes)
        if (usedWork > request.budget.resources.workUnitLimit.value) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
        }
        if (usedBytes > request.budget.returnedBytes.value) {
            limit(QueryLimitation.BYTE_LIMIT_REACHED)
        }
    }

    fun consumeUnit(): Boolean {
        if (remainingWork() < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return false
        }
        if (remainingMillis() < 1L) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return false
        }
        usedWork += 1L
        return true
    }

    fun discoveryBudget(): SymbolDiscoveryBudget? {
        val resources = remainingResources() ?: return null
        val bytes = childByteAllowance() ?: return null
        return SymbolDiscoveryBudget(resources, SymbolDiscoveryByteLimit.parse(bytes).refined())
    }

    fun relationBudget(): RelationBudget? {
        val resources = remainingResources() ?: return null
        val bytes = childByteAllowance() ?: return null
        return RelationBudget(resources, RelationByteLimit.parse(bytes).refined())
    }

    fun <Value> boundResults(values: List<Value>): List<Value> {
        val limit = request.budget.resources.resultLimit.value
        if (values.size > limit) this.limit(QueryLimitation.RESULT_LIMIT_REACHED)
        return values.take(limit)
    }

    fun boundConnections(values: List<RelationFact>): List<RelationFact> =
        boundResults(values.distinct().sorted())

    fun <Value> boundOutput(
        values: List<Value>,
        projectedUtf8Size: (Value) -> Long,
    ): List<Value> = buildList {
        for (value in values) {
            val bytes = projectedUtf8Size(value)
            if (!consumeOutput(bytes)) break
            add(value)
        }
    }

    fun boundedFailures(): List<QueryItemFailure> =
        boundOutput(failures.toList(), QueryItemFailure::projectedUtf8Size)

    fun failure(failure: QueryItemFailure) {
        if (failures.size >= request.budget.resources.resultLimit.value) {
            limit(QueryLimitation.RESULT_LIMIT_REACHED)
        } else {
            failures += failure
        }
    }

    fun observeTime(): Boolean {
        if (remainingMillis() >= 1L) return true
        limit(QueryLimitation.TIME_LIMIT_REACHED)
        return false
    }

    fun limit(limitation: QueryLimitation) {
        limitations += limitation
    }

    private fun remainingResources(): ResourceBudget? {
        val work = remainingWork()
        val millis = remainingMillis()
        if (work < 1L) {
            limit(QueryLimitation.WORK_LIMIT_REACHED)
            return null
        }
        if (millis < 1L) {
            limit(QueryLimitation.TIME_LIMIT_REACHED)
            return null
        }
        return ResourceBudget(
            request.budget.resources.resultLimit,
            WorkUnitLimit.parse(work).refined(),
            ElapsedTimeLimitMillis.parse(millis).refined(),
        )
    }

    private fun remainingWork(): Long =
        (request.budget.resources.workUnitLimit.value - usedWork).coerceAtLeast(0L)

    private fun remainingBytes(): Long? {
        val remaining = (request.budget.returnedBytes.value - usedBytes).coerceAtLeast(0L)
        if (remaining < 1L) {
            limit(QueryLimitation.BYTE_LIMIT_REACHED)
            return null
        }
        return remaining
    }

    /** Leaves at least half of remaining byte authority available for downstream projection. */
    private fun childByteAllowance(): Long? {
        val remaining = remainingBytes() ?: return null
        return (remaining / 2L).coerceAtLeast(1L)
    }

    private fun consumeOutput(bytes: Long): Boolean {
        val remaining = remainingBytes() ?: return false
        if (bytes > remaining) {
            limit(QueryLimitation.BYTE_LIMIT_REACHED)
            return false
        }
        usedBytes = saturatedAdd(usedBytes, bytes)
        return true
    }

    private fun remainingMillis(): Long {
        val elapsedNanos = (clock.now() - startedAt).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L
        return (request.budget.resources.elapsedTimeLimit.value - elapsedMillis).coerceAtLeast(0L)
    }
}

private fun discoveryKinds(syntax: QueryDiscoverySyntax): List<SymbolNameDiscoveryKind> = buildSet {
    if (CompilerSymbolKind.CLASSLIKE in syntax.declarationKinds.values) {
        add(SymbolNameDiscoveryKind.CLASS)
    }
    if (syntax.declarationKinds.values.any { it != CompilerSymbolKind.CLASSLIKE }) {
        add(SymbolNameDiscoveryKind.SYMBOL)
    }
}.sortedBy { it.ordinal }

private fun sourceKinds(scope: QueryScope): SymbolSourceKindPolicy = when (scope) {
    QueryScope.Unrestricted -> SymbolSourceKindPolicy.PRODUCTION_AND_TEST
    is QueryScope.Restricted -> when (val sourceSets = scope.sourceSets) {
        QuerySourceSets.All -> SymbolSourceKindPolicy.PRODUCTION_AND_TEST
        is QuerySourceSets.Exact -> when (sourceSets.values.toSet()) {
            setOf(QuerySourceSet.MAIN) -> SymbolSourceKindPolicy.PRODUCTION_ONLY
            setOf(QuerySourceSet.TEST) -> SymbolSourceKindPolicy.TEST_ONLY
            else -> SymbolSourceKindPolicy.PRODUCTION_AND_TEST
        }
    }
}

private fun constraints(syntax: QueryDiscoverySyntax): SymbolDiscoveryConstraints = when (
    val scope = syntax.scope
) {
    QueryScope.Unrestricted -> SymbolDiscoveryConstraints(
        directory = null,
        packageName = null,
        declarationKinds = SymbolDiscoveryDeclarationKinds.from(
            syntax.declarationKinds.values.toSet(),
        ).refined(),
    )
    is QueryScope.Restricted -> SymbolDiscoveryConstraints(
        directory = scope.directory?.let { restriction ->
            SymbolDiscoveryDirectoryConstraint(
                SymbolDiscoveryDirectory.parse(restriction.path.value).refined(),
                restriction.containment.toDiscoveryContainment(),
            )
        },
        packageName = scope.packageName?.let { restriction ->
            SymbolDiscoveryPackageConstraint(
                SymbolDiscoveryPackage.parse(restriction.name.value).refined(),
                restriction.containment.toDiscoveryContainment(),
            )
        },
        declarationKinds = SymbolDiscoveryDeclarationKinds.from(
            syntax.declarationKinds.values.toSet(),
        ).refined(),
    )
}

private fun QueryContainment.toDiscoveryContainment(): SymbolDiscoveryContainment = when (this) {
    QueryContainment.DIRECT -> SymbolDiscoveryContainment.DIRECT
    QueryContainment.DESCENDANTS -> SymbolDiscoveryContainment.DESCENDANTS
}

private fun visibilityRequest(
    symbol: SymbolSelector,
    predicate: QueryPredicate.Visibility,
    state: QueryExecutionState,
): SourceReadRequest = SourceReadRequest(
    anchor = SourceReadAnchor.Symbol(symbol),
    region = RegionSelection.Anchor,
    entities = EntitySelection.matching(
        Containment.DIRECT,
        listOf(
            EntityFilter.Declarations(
                DeclarationKindSelection.from(setOf(symbol.kind.toDeclarationKind())).refined(),
                VisibilitySelection.exact(predicate.values.values.toSet()).refined(),
            ),
        ),
    ).refined(),
    text = TextProjection.None,
    entityLimit = SourceEntityLimit.parse(1).refined(),
    textByteLimit = SourceTextByteLimit.parse(
        state.request.budget.returnedBytes.value.coerceAtLeast(1L),
    ).refined(),
    page = SourceReadPage.First,
)

private fun CompilerSymbolKind.toDeclarationKind(): DeclarationKind = when (this) {
    CompilerSymbolKind.CLASSLIKE -> DeclarationKind.CLASSLIKE
    CompilerSymbolKind.CONSTRUCTOR -> DeclarationKind.CONSTRUCTOR
    CompilerSymbolKind.FUNCTION -> DeclarationKind.FUNCTION
    CompilerSymbolKind.PROPERTY -> DeclarationKind.PROPERTY
    CompilerSymbolKind.TYPE_ALIAS -> DeclarationKind.TYPE_ALIAS
}

private fun RelationFact.toQuerySymbol(
    prior: List<RelationFact>,
    state: QueryExecutionState,
): QuerySymbol {
    val expanded = if (source.fingerprint == subject.fingerprint) target else source
    val selector = when (expanded) {
        is RelationEndpoint.Subject -> expanded.selector
        is RelationEndpoint.Resolved -> SymbolSelector.issue(
            expanded.lease,
            expanded.scope,
            expanded.evidence,
        )
    }
    return QuerySymbol(
        SymbolDescription.from(selector),
        state.boundConnections(prior + this),
    )
}

private fun distinct(
    values: List<QuerySymbol>,
    state: QueryExecutionState,
): List<QuerySymbol> = values
    .groupBy { it.selector.fingerprint }
    .values
    .map { group ->
        group.first().copy(connections = state.boundConnections(group.flatMap { it.connections }))
    }
    .sortedWith(
        compareBy(
            { it.description.file.stableValue },
            { it.description.range.startInclusive },
            { it.description.compilerIdentity.value },
        ),
    )

private fun QueryCandidate.projectedUtf8Size(): Long =
    selection.candidate.projectedUtf8Size().value

private fun QuerySymbol.projectedUtf8Size(): Long = saturatedSum(
    listOf(description.selector.projectedUtf8Size()) +
        connections.map { it.canonicalProjection().utf8Size() },
)

private fun QueryItemFailure.projectedUtf8Size(): Long = when (this) {
    is QueryItemFailure.Refinement -> saturatedAdd(
        candidate.candidate.projectedUtf8Size().value,
        reason.name.utf8Size(),
    )
    is QueryItemFailure.ExactReference -> saturatedAdd(
        selector.projectedUtf8Size(),
        reason.name.utf8Size(),
    )
    is QueryItemFailure.Visibility -> saturatedAdd(
        selector.projectedUtf8Size(),
        reason.name.utf8Size(),
    )
    is QueryItemFailure.PredicateUnproven -> selector.projectedUtf8Size()
    is QueryItemFailure.Relation -> saturatedSum(
        listOf(selector.projectedUtf8Size(), meaning.toString().utf8Size(), reason.name.utf8Size()),
    )
}

private fun SymbolSelector.projectedUtf8Size(): Long = buildString {
    append(lease.workspaceRoot.value)
    append('\u0000')
    append(lease.generation.value)
    append('\u0000')
    append(file.stableValue)
    append('\u0000')
    append(range.startInclusive)
    append('\u0000')
    append(range.endExclusive)
    append('\u0000')
    append(name.value)
    append('\u0000')
    append(kind.name)
    append('\u0000')
    append(signature.canonicalEncoding().value)
    append('\u0000')
    append(compilerIdentity.value)
    append('\u0000')
    append(fingerprint.value)
}.utf8Size()

private fun String.utf8Size(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

private fun saturatedSum(values: List<Long>): Long =
    values.fold(0L, ::saturatedAdd)

private fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch = when (this) {
    is SymbolDiscoveryOutcome.Complete -> batch
    is SymbolDiscoveryOutcome.Qualified -> batch
}

private fun Int.queryCount(): QueryCount = when (val parsed = QueryCount.parse(this)) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("A collection size cannot be negative")
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
