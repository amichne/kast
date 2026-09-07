package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCandidate
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySourceSet
import io.github.amichne.kast.query.contract.QuerySourceSets
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import java.nio.charset.StandardCharsets

internal fun discoveryKinds(syntax: QueryDiscoverySyntax): List<SymbolNameDiscoveryKind> = buildSet {
    if (CompilerSymbolKind.CLASSLIKE in syntax.declarationKinds.values) {
        add(SymbolNameDiscoveryKind.CLASS)
    }
    if (syntax.declarationKinds.values.any { it != CompilerSymbolKind.CLASSLIKE }) {
        add(SymbolNameDiscoveryKind.SYMBOL)
    }
}.sortedBy { it.ordinal }

internal fun sourceKinds(scope: QueryScope): SymbolSourceKindPolicy = when (scope) {
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

/**
 * A multi-family query must not spend class candidates once through the class index and again
 * through the broad symbol index. Each child request receives only the declaration families it
 * semantically owns.
 */
internal fun constraints(
    syntax: QueryDiscoverySyntax,
    discoveryKind: SymbolNameDiscoveryKind,
): SymbolDiscoveryConstraints {
    val declarationKinds = when (discoveryKind) {
        SymbolNameDiscoveryKind.CLASS ->
            syntax.declarationKinds.values.filterTo(linkedSetOf()) {
                it == CompilerSymbolKind.CLASSLIKE
            }
        SymbolNameDiscoveryKind.SYMBOL ->
            syntax.declarationKinds.values.filterTo(linkedSetOf()) {
                it != CompilerSymbolKind.CLASSLIKE
            }
        SymbolNameDiscoveryKind.FILE -> emptySet()
    }
    val admittedKinds = SymbolDiscoveryDeclarationKinds.from(declarationKinds).refined()
    return when (val scope = syntax.scope) {
        QueryScope.Unrestricted -> SymbolDiscoveryConstraints(
            directory = null,
            packageName = null,
            declarationKinds = admittedKinds,
        )
        is QueryScope.Restricted -> SymbolDiscoveryConstraints(
            directory = scope.directory,
            packageName = scope.packageName,
            declarationKinds = admittedKinds,
        )
    }
}

internal fun visibilityRequest(
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

internal fun RelationFact.toQuerySymbol(
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

internal fun distinct(
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

internal fun QueryCandidate.projectedUtf8Size(): Long =
    selection.candidate.projectedUtf8Size().value

internal fun QuerySymbol.projectedUtf8Size(): Long = saturatedSum(
    listOf(description.selector.projectedUtf8Size()) +
        connections.map { it.canonicalProjection().utf8Size() },
)

internal fun QueryItemFailure.projectedUtf8Size(): Long = when (this) {
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

private fun saturatedSum(values: List<Long>): Long = values.fold(0L, ::saturatedAdd)

internal fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch = when (this) {
    is SymbolDiscoveryOutcome.Complete -> batch
    is SymbolDiscoveryOutcome.Qualified -> batch
}

internal fun Int.queryCount(): QueryCount = when (val parsed = QueryCount.parse(this)) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("A collection size cannot be negative")
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
}
