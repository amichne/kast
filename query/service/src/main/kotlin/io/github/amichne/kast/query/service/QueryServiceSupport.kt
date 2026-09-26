package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.LineCount
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets

private const val SOURCE_CONTEXT_LINES = 5
private const val SOURCE_WINDOW_PROJECTION_OVERHEAD_BYTES = 64L
private const val RELATION_OMISSION_PROJECTION_MULTIPLIER = 4

/** SYMBOL already owns every Kotlin declaration family, including classes. */
internal fun discoveryKinds(syntax: QueryDiscoverySyntax): List<SymbolNameDiscoveryKind> =
    listOf(
        if (syntax.declarationKinds.values.toSet() == setOf(CompilerSymbolKind.CLASSLIKE)) SymbolNameDiscoveryKind.CLASS
        else SymbolNameDiscoveryKind.SYMBOL
    )

internal fun constraints(
    syntax: QueryDiscoverySyntax,
    discoveryKind: SymbolNameDiscoveryKind,
): SymbolDiscoveryConstraints {
    check(discoveryKind != SymbolNameDiscoveryKind.FILE)
    val admittedKinds = SymbolDiscoveryDeclarationKinds.from(syntax.declarationKinds.values.toSet()).refined()
    return when (val scope = syntax.scope) {
        QueryScope.Unrestricted ->
            SymbolDiscoveryConstraints(directory = null, packageName = null, declarationKinds = admittedKinds)
        is QueryScope.Restricted ->
            SymbolDiscoveryConstraints(
                sourceSets = scope.sourceSets,
                directory = scope.directory,
                packageName = scope.packageName,
                declarationKinds = admittedKinds,
            )
    }
}

internal fun visibilityRequest(
    symbol: SymbolSelector,
    state: QueryExecutionState,
    resources: io.github.amichne.kast.kernel.ResourceBudget,
): SourceReadRequest =
    SourceReadRequest(
        anchor = SourceReadAnchor.Symbol(symbol),
        region = RegionSelection.Anchor,
        entities =
            EntitySelection.matching(
                    Containment.SELF,
                    listOf(
                        EntityFilter.Declarations(
                            DeclarationKindSelection.from(setOf(symbol.kind.toDeclarationKind())).refined(),
                            VisibilitySelection.Any,
                        )
                    ),
                )
                .refined(),
        text = TextProjection.None,
        entityLimit = SourceEntityLimit.parse(1).refined(),
        textByteLimit = SourceTextByteLimit.parse(state.request.budget.returnedBytes.value.coerceAtLeast(1L)).refined(),
        page = SourceReadPage.First,
        resources = resources,
    )

/** Five whole lines on either side of the exact symbol, clipped to its admitted file. */
internal fun sourceWindowRequest(
    symbol: SymbolSelector,
    state: QueryExecutionState,
    resources: io.github.amichne.kast.kernel.ResourceBudget,
): SourceReadRequest =
    SourceReadRequest(
        anchor = SourceReadAnchor.Symbol(symbol),
        region = RegionSelection.File,
        entities = EntitySelection.None,
        text =
            TextProjection.window(
                LineCount.parse(SOURCE_CONTEXT_LINES).refined(),
                LineCount.parse(SOURCE_CONTEXT_LINES).refined(),
            ),
        entityLimit = SourceEntityLimit.parse(1).refined(),
        textByteLimit = SourceTextByteLimit.parse(state.request.budget.returnedBytes.value).refined(),
        page = SourceReadPage.First,
        resources = resources,
    )

private fun CompilerSymbolKind.toDeclarationKind(): DeclarationKind =
    when (this) {
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
    val selector =
        when (expanded) {
            is RelationEndpoint.Subject -> expanded.selector
            is RelationEndpoint.Resolved ->
                SymbolSelector.issue(
                    expanded.lease,
                    expanded.scope,
                    expanded.evidence,
                    expanded.constraints,
                )
        }
    return QuerySymbol(
        SymbolDescription.from(selector),
        state.boundConnections(prior + this),
        arrival = QueryArrivalEvidence.Proven.one(this),
    )
}

internal fun QuerySymbol.projectedUtf8Size(): Long =
    saturatedSum(
        listOf(
            description.selector.projectedUtf8Size(),
            when (val projected = source) {
                is io.github.amichne.kast.query.contract.QuerySymbolSource.Returned ->
                    projected.value.text.utf8Size() + SOURCE_WINDOW_PROJECTION_OVERHEAD_BYTES
                else -> 0L
            },
        ) + connections.map { it.canonicalProjection().utf8Size() }
    )

internal fun QueryItemFailure.projectedUtf8Size(): Long =
    when (this) {
        is QueryItemFailure.Refinement ->
            saturatedAdd(
                candidate.candidate.projectedUtf8Size().value,
                reason.name.utf8Size(),
            )
        is QueryItemFailure.ExactReference ->
            saturatedAdd(
                selector.projectedUtf8Size(),
                reason.name.utf8Size(),
            )
        is QueryItemFailure.Visibility ->
            saturatedAdd(
                selector.projectedUtf8Size(),
                reason.name.utf8Size(),
            )
        is QueryItemFailure.PredicateUnproven -> selector.projectedUtf8Size()
        is QueryItemFailure.Source -> selector.projectedUtf8Size() + reason.toString().utf8Size()
        is QueryItemFailure.Relation ->
            saturatedSum(listOf(selector.projectedUtf8Size(), meaning.toString().utf8Size(), reason.name.utf8Size()))
    }

internal fun QueryRelationOmission.projectedUtf8Size(): Long {
    val evidenceBytes = evidence.toString().utf8Size()
    return saturatedAdd(
        subject.projectedUtf8Size(),
        saturatedSum(List(RELATION_OMISSION_PROJECTION_MULTIPLIER) { evidenceBytes }),
    )
}

private fun SymbolSelector.projectedUtf8Size(): Long = buildString {
    append(lease.workspaceRoot.value)
    append('\u0000')
    append(lease.identity.revisionKey.value)
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
}
    .utf8Size()

private fun String.utf8Size(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

private fun saturatedSum(values: List<Long>): Long = values.fold(0L, ::saturatedAdd)

internal fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch =
    when (this) {
        is SymbolDiscoveryOutcome.Complete -> batch
        is SymbolDiscoveryOutcome.Qualified -> batch
    }

internal fun Int.queryCount(): QueryCount =
    when (val parsed = QueryCount.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A collection size cannot be negative")
    }

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internally derived query value violated its invariant: $failure")
    }
