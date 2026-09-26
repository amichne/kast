package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalStrategy
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

sealed interface QueryOutputSyntax {
    data class Symbols(val fields: QuerySymbolFields) : QueryOutputSyntax

    data object Occurrences : QueryOutputSyntax

    data object TraversalRecords : QueryOutputSyntax

    data object BindingRows : QueryOutputSyntax
}

data class QueryPlanSyntax(
    val source: QuerySourceSyntax,
    val steps: List<QueryStepSyntax>,
    val output: QueryOutputSyntax,
)

sealed interface QueryPlanAdmissionFailure {
    data class UnsupportedDeclarationKind(val kind: CompilerSymbolKind) : QueryPlanAdmissionFailure

    data object IncompleteRightInput : QueryPlanAdmissionFailure

    data class DuplicateBindingName(val name: QueryBindingName) : QueryPlanAdmissionFailure

    data class UnknownBindingName(val name: QueryBindingName) : QueryPlanAdmissionFailure

    data object OutputTypeMismatch : QueryPlanAdmissionFailure
}

enum class QuerySetOperator {
    INTERSECTION,
    DIFFERENCE,
}

sealed interface ExactQueryStage {
    data class ProjectBinding(val name: QueryBindingName, val next: ExactQueryStage) : ExactQueryStage

    data class Where(val predicate: QueryPredicate, val next: ExactQueryStage) : ExactQueryStage

    data class Related(val meaning: RelationMeaning, val next: ExactQueryStage) : ExactQueryStage

    data class Walk(
        val meaning: RelationMeaning,
        val maximumDepth: TraversalDepthLimit,
        val strategy: TraversalStrategy,
        val next: ExactQueryStage,
    ) : ExactQueryStage

    data class Distinct(val next: ExactQueryStage) : ExactQueryStage

    data class Concat(val input: QueryCompositionInput, val next: ExactQueryStage) : ExactQueryStage

    data class Set
    internal constructor(
        val operator: QuerySetOperator,
        val right: QueryRetainedResult.Symbols,
        val next: ExactQueryStage,
    ) : ExactQueryStage

    data class Bind(val name: QueryBindingName, val next: ExactQueryStage) : ExactQueryStage

    data class Join(
        val mode: QueryJoinMode,
        val right: QueryJoinInput,
        val next: ExactQueryStage,
    ) : ExactQueryStage

    data class Emit(val output: QueryOutputSyntax) : ExactQueryStage
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

private sealed interface AdmittedRowKind {
    data object Symbol : AdmittedRowKind

    data class Binding(val mode: QueryJoinMode.Inner) : AdmittedRowKind
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
        admitStages(source, syntax.steps, syntax.output)?.let {
            return QueryPlanAdmission.Rejected(it)
        }
        val stage = exactStage(syntax.steps, syntax.output)
        val plan =
            when (source) {
                is QuerySourceSyntax.Symbols -> AdmittedQueryPlan.Symbols(source.discovery, stage)
                is QuerySourceSyntax.ExactReferences -> AdmittedQueryPlan.ExactReferences(source.references, stage)
                is QuerySourceSyntax.Retained -> AdmittedQueryPlan.Retained(source.result, stage)
            }
        return QueryPlanAdmission.Admitted(plan)
    }

    private fun admitStages(
        source: QuerySourceSyntax,
        steps: List<QueryStepSyntax>,
        output: QueryOutputSyntax,
    ): QueryPlanAdmissionFailure? {
        admitBindingOrder(steps)?.let {
            return it
        }
        if (steps.any(::hasIncompleteRight)) return QueryPlanAdmissionFailure.IncompleteRightInput
        var rowKind: AdmittedRowKind =
            when (source) {
                is QuerySourceSyntax.Symbols,
                is QuerySourceSyntax.ExactReferences -> AdmittedRowKind.Symbol
                is QuerySourceSyntax.Retained ->
                    when (val result = source.result) {
                        is QueryRetainedResult.Symbols -> AdmittedRowKind.Symbol
                        is QueryRetainedResult.Bindings -> AdmittedRowKind.Binding(result.mode)
                    }
            }
        for (step in steps) {
            rowKind =
                when (val current = rowKind) {
                    AdmittedRowKind.Symbol ->
                        when (step) {
                            is QueryStepSyntax.ProjectBinding -> return QueryPlanAdmissionFailure.OutputTypeMismatch
                            is QueryStepSyntax.Join ->
                                (step.mode as? QueryJoinMode.Inner)?.let(AdmittedRowKind::Binding)
                                    ?: AdmittedRowKind.Symbol
                            else -> AdmittedRowKind.Symbol
                        }
                    is AdmittedRowKind.Binding ->
                        when (step) {
                            is QueryStepSyntax.ProjectBinding -> {
                                if (step.name != current.mode.leftName && step.name != current.mode.rightName) {
                                    return QueryPlanAdmissionFailure.UnknownBindingName(step.name)
                                }
                                AdmittedRowKind.Symbol
                            }
                            else -> return QueryPlanAdmissionFailure.OutputTypeMismatch
                        }
                    }
        }
        return when (rowKind) {
            AdmittedRowKind.Symbol ->
                if (output is QueryOutputSyntax.BindingRows) QueryPlanAdmissionFailure.OutputTypeMismatch else null
            is AdmittedRowKind.Binding ->
                if (output is QueryOutputSyntax.BindingRows) null else QueryPlanAdmissionFailure.OutputTypeMismatch
        }
    }

    private fun admitBindingOrder(steps: List<QueryStepSyntax>): QueryPlanAdmissionFailure? {
        val bindings = mutableSetOf<QueryBindingName>()
        for (step in steps) {
            val failure =
                when (step) {
                    is QueryStepSyntax.Bind ->
                        if (bindings.add(step.name)) null else QueryPlanAdmissionFailure.DuplicateBindingName(step.name)
                    is QueryStepSyntax.Join -> admitJoin(step, bindings)
                    else -> null
                }
            if (failure != null) return failure
        }
        return null
    }

    private fun hasIncompleteRight(step: QueryStepSyntax): Boolean =
        when (step) {
            is QueryStepSyntax.Difference -> QueryCompleteMembership.from(step.right) is Refinement.Rejected
            is QueryStepSyntax.Join -> {
                val right = step.right
                right is QueryJoinInput.Retained &&
                    step.mode is QueryJoinMode.Anti &&
                    QueryCompleteMembership.from(right.result) is Refinement.Rejected
            }
            else -> false
        }

    private fun admitJoin(
        step: QueryStepSyntax.Join,
        bindings: Set<QueryBindingName>,
    ): QueryPlanAdmissionFailure? {
        val right = step.right
        if (right is QueryJoinInput.Named && right.name !in bindings) {
            return QueryPlanAdmissionFailure.UnknownBindingName(right.name)
        }
        return null
    }

    private fun exactStage(steps: List<QueryStepSyntax>, output: QueryOutputSyntax): ExactQueryStage {
        var stage: ExactQueryStage = ExactQueryStage.Emit(output)
        for (step in steps.asReversed()) {
            stage =
                when (step) {
                    is QueryStepSyntax.ProjectBinding -> ExactQueryStage.ProjectBinding(step.name, stage)
                    QueryStepSyntax.Distinct -> ExactQueryStage.Distinct(stage)
                    is QueryStepSyntax.Where -> ExactQueryStage.Where(step.predicate, stage)
                    is QueryStepSyntax.Related -> ExactQueryStage.Related(step.meaning, stage)
                    is QueryStepSyntax.Walk ->
                        ExactQueryStage.Walk(step.meaning, step.maximumDepth, step.strategy, stage)
                    is QueryStepSyntax.Concat -> ExactQueryStage.Concat(step.input, stage)
                    is QueryStepSyntax.Intersect ->
                        ExactQueryStage.Set(QuerySetOperator.INTERSECTION, step.right, stage)
                    is QueryStepSyntax.Union ->
                        ExactQueryStage.Concat(QueryCompositionInput.Retained(step.right), ExactQueryStage.Distinct(stage))
                    is QueryStepSyntax.Difference -> ExactQueryStage.Set(QuerySetOperator.DIFFERENCE, step.right, stage)
                    is QueryStepSyntax.Bind -> ExactQueryStage.Bind(step.name, stage)
                    is QueryStepSyntax.Join -> ExactQueryStage.Join(step.mode, step.right, stage)
                }
        }
        return stage
    }
}

internal fun AdmittedQueryPlan.composedInputLeases(): List<SemanticReadAuthority> =
    when (this) {
        is AdmittedQueryPlan.Symbols -> stage.composedInputLeases()
        is AdmittedQueryPlan.ExactReferences -> stage.composedInputLeases()
        is AdmittedQueryPlan.Retained -> stage.composedInputLeases()
    }

private fun ExactQueryStage.composedInputLeases(): List<SemanticReadAuthority> =
    when (this) {
        is ExactQueryStage.ProjectBinding -> next.composedInputLeases()
        is ExactQueryStage.Concat ->
            (when (val source = input) {
                is QueryCompositionInput.ExactReferences -> source.references.values.map { it.lease }
                is QueryCompositionInput.Retained -> listOf(source.result.lease)
            }) + next.composedInputLeases()
        is ExactQueryStage.Set -> listOf(right.lease) + next.composedInputLeases()
        is ExactQueryStage.Bind -> next.composedInputLeases()
        is ExactQueryStage.Join ->
            (when (val input = right) {
                is QueryJoinInput.Named -> emptyList()
                is QueryJoinInput.Retained -> listOf(input.result.lease)
            }) + next.composedInputLeases()
        is ExactQueryStage.Distinct -> next.composedInputLeases()
        is ExactQueryStage.Where -> next.composedInputLeases()
        is ExactQueryStage.Related -> next.composedInputLeases()
        is ExactQueryStage.Walk -> next.composedInputLeases()
        is ExactQueryStage.Emit -> emptyList()
    }
