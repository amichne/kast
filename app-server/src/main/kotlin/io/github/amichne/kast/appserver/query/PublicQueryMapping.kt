package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPackageScopeDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument

/** Pure lowering. The caller must first establish the public schema identity. */
internal fun PublicQueryDocument.toCanonicalQuery(): QueryRunRequest =
    QueryRunRequest(
        from =
            when (val source = from) {
                is PublicQuerySearch ->
                    QueryFromDocument.Symbols(
                        QueryMatchDocument.Name(source.query, source.match.canonical()),
                        source.scope.canonical(),
                        source.kinds.mapBounded { it.canonical() },
                    )
                is PublicQueryAll ->
                    QueryFromDocument.Symbols(
                        QueryMatchDocument.All,
                        source.scope.canonical(),
                        source.kinds.mapBounded { it.canonical() },
                    )
                is PublicQueryRefs ->
                    QueryFromDocument.References(source.refs.mapBounded { QueryReferenceDocument.ExactSymbol(it) })
            },
        steps =
            steps.mapBounded { step ->
                when (step) {
                    is PublicQueryFilter ->
                        QueryStepDocument.Where(
                            QueryPredicateDocument.Visibility(step.visibility.mapBounded { it.canonical() })
                        )
                    is PublicQueryExpand -> QueryStepDocument.Related(step.relation.canonical())
                    PublicQueryDistinct -> QueryStepDocument.Distinct
                }
            },
        output = QueryOutputDocument.Symbols(select.mapBounded { it.canonical() }),
        execution =
            QueryExecutionDocument(
                QueryExecutionKindDocument.EXHAUSTIVE,
                QueryExecutionBudgetDocument.INTERACTIVE,
            ),
    )

private fun PublicQueryScope.canonical(): QueryScopeDocument =
    when (this) {
        is PublicQueryScope.Directory ->
            QueryScopeDocument(
                sourceSets,
                QueryDirectoryScopeDocument(protocolText(value.value), containment.canonical()),
                null,
            )
        is PublicQueryScope.Package ->
            QueryScopeDocument(
                sourceSets,
                null,
                QueryPackageScopeDocument(protocolText(value.value), containment.canonical()),
            )
    }

/** Extraction into the canonical transport occurs only after the target-specific proof. */
private fun protocolText(raw: String): io.github.amichne.kast.protocol.contract.ProtocolText =
    when (val result = io.github.amichne.kast.protocol.contract.ProtocolText.parse(raw)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("A refined scope value violated the transport text bound")
    }

private fun <T, R> BoundedProtocolList<T>.mapBounded(transform: (T) -> R): BoundedProtocolList<R> =
    when (val result = BoundedProtocolList.create(values.map(transform))) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("A cardinality-preserving map exceeded its input bound")
    }
