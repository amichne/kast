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
internal fun PublicQueryDocument.toCanonicalQuery(): QueryRunRequest = QueryRunRequest(
    from = when (val source = from) {
        is PublicQuerySearch -> QueryFromDocument.Symbols(
            QueryMatchDocument.Name(source.query, (source.match ?: PublicQueryDefaults.match).canonical()),
            source.scope.canonical(),
            (source.kinds ?: PublicQueryDefaults.kinds).mapBounded { it.canonical() },
        )
        is PublicQueryAll -> QueryFromDocument.Symbols(
            QueryMatchDocument.All,
            source.scope.canonical(),
            (source.kinds ?: PublicQueryDefaults.kinds).mapBounded { it.canonical() },
        )
        is PublicQueryRefs -> QueryFromDocument.References(
            source.refs.mapBounded { QueryReferenceDocument.ExactSymbol(it) },
        )
    },
    steps = (steps ?: PublicQueryDefaults.steps).mapBounded { step ->
        when (step) {
            is PublicQueryFilter -> QueryStepDocument.Where(
                QueryPredicateDocument.Visibility(step.visibility.mapBounded { it.canonical() }),
            )
            is PublicQueryExpand -> QueryStepDocument.Related(step.relation.canonical())
            PublicQueryDistinct -> QueryStepDocument.Distinct
        }
    },
    output = QueryOutputDocument.Symbols(
        (select ?: PublicQueryDefaults.selection).mapBounded { it.canonical() },
    ),
    execution = QueryExecutionDocument(
        QueryExecutionKindDocument.EXHAUSTIVE,
        QueryExecutionBudgetDocument.INTERACTIVE,
    ),
)

private fun PublicQueryScope?.canonical(): QueryScopeDocument = QueryScopeDocument(
    this?.sourceSets ?: PublicQueryDefaults.sourceSets,
    this?.directory?.let { directory ->
        QueryDirectoryScopeDocument(
            directory.path,
            (directory.containment ?: PublicQueryDefaults.containment).canonical(),
        )
    },
    this?.`package`?.let { packageScope ->
        QueryPackageScopeDocument(
            packageScope.name,
            (packageScope.containment ?: PublicQueryDefaults.containment).canonical(),
        )
    },
)

/** Defaults are normalized once, before lowering. Empty selection and ordered stages survive. */
internal fun PublicQueryDocument.withDefaults(): PublicQueryDocument = copy(
    from = when (val source = from) {
        is PublicQuerySearch -> source.copy(
            match = source.match ?: PublicQueryDefaults.match,
            kinds = source.kinds ?: PublicQueryDefaults.kinds,
            scope = source.scope.withDefaults(),
        )
        is PublicQueryAll -> source.copy(
            kinds = source.kinds ?: PublicQueryDefaults.kinds,
            scope = source.scope.withDefaults(),
        )
        is PublicQueryRefs -> source
    },
    steps = steps ?: PublicQueryDefaults.steps,
    select = select ?: PublicQueryDefaults.selection,
)

private fun PublicQueryScope?.withDefaults(): PublicQueryScope = PublicQueryScope(
    PublicQueryScopeType.SCOPE,
    this?.sourceSets ?: PublicQueryDefaults.sourceSets,
    this?.directory?.let { it.copy(containment = it.containment ?: PublicQueryDefaults.containment) },
    this?.`package`?.let { it.copy(containment = it.containment ?: PublicQueryDefaults.containment) },
)

private fun <T, R> BoundedProtocolList<T>.mapBounded(transform: (T) -> R): BoundedProtocolList<R> =
    when (val result = BoundedProtocolList.create(values.map(transform))) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("A cardinality-preserving map exceeded its input bound")
    }
