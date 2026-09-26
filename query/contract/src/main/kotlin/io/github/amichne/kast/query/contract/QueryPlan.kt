package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

enum class QuerySymbolField {
    NAME,
    LOCATION,
    SIGNATURE,
    SOURCE,
}

/** Optional presentation fields. Exact identity remains present through the returned ref. */
class QuerySymbolFields private constructor(val values: List<QuerySymbolField>) {
    companion object {
        fun from(raw: Set<QuerySymbolField>): Refinement<QuerySymbolFields, QueryCollectionFailure> =
            Refinement.Refined(QuerySymbolFields(raw.sortedBy { it.ordinal }))
    }

    override fun equals(other: Any?): Boolean = other is QuerySymbolFields && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

data class QueryOutputSyntax(val fields: QuerySymbolFields)

data class QueryPlanSyntax(
    val source: QuerySourceSyntax,
    val steps: List<QueryStepSyntax>,
    val output: QueryOutputSyntax,
)

sealed interface QueryPlanAdmissionFailure {
    data class UnsupportedDeclarationKind(val kind: CompilerSymbolKind) : QueryPlanAdmissionFailure
}

sealed interface ExactQueryStage {
    data class Where(val predicate: QueryPredicate, val next: ExactQueryStage) : ExactQueryStage

    data class Related(val meaning: RelationMeaning, val next: ExactQueryStage) : ExactQueryStage

    data class Distinct(val next: ExactQueryStage) : ExactQueryStage

    data class AppendReferences(val references: QueryExactReferences, val next: ExactQueryStage) : ExactQueryStage

    data class Emit(val fields: QuerySymbolFields) : ExactQueryStage
}

sealed interface AdmittedQueryPlan {
    data class Symbols
    internal constructor(
        val source: QueryDiscoverySyntax,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class ExactReferences
    internal constructor(
        val source: QueryExactReferences,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class Retained
    internal constructor(
        val source: QueryRetainedResult,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan
}

sealed interface QueryPlanAdmission {
    data class Admitted(val plan: AdmittedQueryPlan) : QueryPlanAdmission

    data class Rejected(val failure: QueryPlanAdmissionFailure) : QueryPlanAdmission
}

/** Pure plan compiler; every admitted stage consumes exact semantic symbols. */
object QueryPlanCompiler {
    fun admit(syntax: QueryPlanSyntax): QueryPlanAdmission {
        val source = syntax.source
        if (
            source is QuerySourceSyntax.Symbols &&
                CompilerSymbolKind.CONSTRUCTOR in source.discovery.declarationKinds.values
        ) {
            return QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.UnsupportedDeclarationKind(CompilerSymbolKind.CONSTRUCTOR)
            )
        }
        val stage = exactStage(syntax.steps, syntax.output.fields)
        val plan =
            when (source) {
                is QuerySourceSyntax.Symbols -> AdmittedQueryPlan.Symbols(source.discovery, stage)
                is QuerySourceSyntax.ExactReferences -> AdmittedQueryPlan.ExactReferences(source.references, stage)
                is QuerySourceSyntax.Retained -> AdmittedQueryPlan.Retained(source.result, stage)
            }
        return QueryPlanAdmission.Admitted(plan)
    }

    private fun exactStage(steps: List<QueryStepSyntax>, fields: QuerySymbolFields): ExactQueryStage {
        var stage: ExactQueryStage = ExactQueryStage.Emit(fields)
        for (step in steps.asReversed()) {
            stage =
                when (step) {
                    QueryStepSyntax.Distinct -> ExactQueryStage.Distinct(stage)
                    is QueryStepSyntax.Where -> ExactQueryStage.Where(step.predicate, stage)
                    is QueryStepSyntax.Related -> ExactQueryStage.Related(step.meaning, stage)
                    is QueryStepSyntax.AppendReferences -> ExactQueryStage.AppendReferences(step.references, stage)
                }
        }
        return stage
    }
}

internal fun AdmittedQueryPlan.appendedReferenceLeases(): List<SemanticReadAuthority> =
    when (this) {
        is AdmittedQueryPlan.Symbols -> stage.appendedReferenceLeases()
        is AdmittedQueryPlan.ExactReferences -> stage.appendedReferenceLeases()
        is AdmittedQueryPlan.Retained -> stage.appendedReferenceLeases()
    }

private fun ExactQueryStage.appendedReferenceLeases(): List<SemanticReadAuthority> =
    when (this) {
        is ExactQueryStage.AppendReferences -> references.values.map { it.lease } + next.appendedReferenceLeases()
        is ExactQueryStage.Distinct -> next.appendedReferenceLeases()
        is ExactQueryStage.Where -> next.appendedReferenceLeases()
        is ExactQueryStage.Related -> next.appendedReferenceLeases()
        is ExactQueryStage.Emit -> emptyList()
    }
