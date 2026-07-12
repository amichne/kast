package io.github.amichne.kast.shared.proofloss.analysis

import io.github.amichne.kast.shared.proofloss.ir.Block
import io.github.amichne.kast.shared.proofloss.ir.FunctionIr
import io.github.amichne.kast.shared.proofloss.ir.PredicateCondition
import io.github.amichne.kast.shared.proofloss.ir.Statement
import io.github.amichne.kast.shared.proofloss.ir.TrackedValueId
import io.github.amichne.kast.shared.proofloss.ir.ValueExpression
import io.github.amichne.kast.shared.proofloss.model.PredicateId
import io.github.amichne.kast.shared.proofloss.model.ProofModel

class ProofLossAnalyzer(private val model: ProofModel) {
    fun analyze(function: FunctionIr): List<ProofLossFinding> =
        block(
            function,
            function.body,
            State(function.parameters.associateWith { Value(it, emptySet(), listOf(it)) }, emptyMap()),
        ).findings
            .toList()
            .distinct()
            .sortedWith(compareBy({ it.boundaryCall.filePath }, { it.boundaryCall.startOffset }, { it.argumentIndex }))

    private fun block(
        function: FunctionIr,
        block: Block,
        start: State,
    ): AnalysisStep = block.statements.fold(AnalysisStep(start)) { accumulated, statement ->
        accumulated.state
            ?.let { analyze(function, statement, it) }
            ?.let { current ->
                AnalysisStep(current.state, accumulated.findings + current.findings)
            }
            ?: accumulated
    }

    private fun analyze(
        function: FunctionIr,
        statement: Statement,
        state: State,
    ): AnalysisStep = when (statement) {
        is Statement.Let -> AnalysisStep(bind(statement, state))
        is Statement.If -> branch(function, statement, state)
        is Statement.BoundaryCall -> AnalysisStep(state, boundary(function, statement, state).asSequence())
        is Statement.Exit -> AnalysisStep(null)
        is Statement.NoOp -> AnalysisStep(state)
    }

    private fun bind(
        statement: Statement.Let,
        state: State,
    ): State? = when (val expression = statement.expression) {
        is ValueExpression.Alias -> state.copy(
            values = state.values + (statement.target to state.resolve(expression.source)
                .let { it.copy(path = it.path + statement.target) })
        )
        is ValueExpression.Materialize -> {
            requireNotNull(model.materializer(expression.callable))
            require(model.predicateForMaterializer(expression.callable)?.id == expression.predicate)
            val source = state.resolve(expression.source)
            val key = FactKey(expression.predicate, source.origin)
            val old = state.facts[key]
            if (old?.truth == false) null else state.copy(
                values = state.values + (statement.target to source.copy(
                    proofs = source.proofs + expression.predicate,
                    path = source.path + statement.target
                )),
                facts = if (old == null) state.facts + (key to Fact(
                    true,
                    ProofEstablishment.MaterializationSuccess(
                        statement.location,
                        statement.target
                    )
                )) else state.facts,
            )
        }
    }

    private fun branch(
        function: FunctionIr,
        statement: Statement.If,
        state: State,
    ): AnalysisStep = mergeBranches(
        state.assume(statement.condition, true)
            ?.let { block(function, statement.thenBranch, it) }
            ?: AnalysisStep(null),
        state.assume(statement.condition, false)
            ?.let { block(function, statement.elseBranch, it) }
            ?: AnalysisStep(null),
    )

    private fun mergeBranches(
        thenStep: AnalysisStep,
        elseStep: AnalysisStep,
    ): AnalysisStep = AnalysisStep(
        join(thenStep.state, elseStep.state),
        thenStep.findings + elseStep.findings,
    )

    private fun boundary(
        function: FunctionIr,
        call: Statement.BoundaryCall,
        state: State,
    ): List<ProofLossFinding> = model.boundary(call.boundary).let { boundary ->
        boundary.obligations.mapNotNull { obligation ->
            val argumentId = requireNotNull(call.arguments[obligation.argumentIndex])
            val argument = state.resolve(argumentId)
            val fact = state.facts[FactKey(obligation.predicate, argument.origin)]?.takeIf { it.truth }
            fact?.takeUnless { obligation.predicate in argument.proofs }?.let {
                val predicate = model.predicate(obligation.predicate)
                ProofLossFinding(
                    function.id,
                    predicate.id,
                    predicate.callable,
                    boundary.id,
                    boundary.callable,
                    obligation.argumentIndex,
                    argument.origin,
                    argumentId,
                    fact.proof,
                    call.location,
                    argument.path,
                    predicate.materializers.map { it.callable }.toSet(),
                )
            }
        }
    }

    private fun State.assume(
        condition: PredicateCondition,
        result: Boolean,
    ): State? = resolve(condition.subject).let { subject ->
        val truth = condition.polarity.predicateHoldsWhen(result)
        if (condition.predicate in subject.proofs && !truth) null
        else FactKey(condition.predicate, subject.origin).let { key ->
            when (val fact = facts[key]) {
                null -> copy(
                    facts = facts + (key to Fact(
                        truth,
                        ProofEstablishment.PredicateGuard(condition.location),
                    )),
                )
                else -> takeIf { fact.truth == truth }
            }
        }
    }

    private fun join(
        a: State?,
        b: State?,
    ): State? = when {
        a == null -> b
        b == null -> a
        else -> State(
            a.values.filter { b.values[it.key] == it.value },
            a.facts.filter { b.facts[it.key] == it.value },
        )
    }

    private data class AnalysisStep(
        val state: State?,
        val findings: Sequence<ProofLossFinding> = emptySequence(),
    )

    private data class State(
        val values: Map<TrackedValueId, Value>,
        val facts: Map<FactKey, Fact>,
    ) {
        fun resolve(id: TrackedValueId) = requireNotNull(values[id])
    }

    private data class Value(
        val origin: TrackedValueId,
        val proofs: Set<PredicateId>,
        val path: List<TrackedValueId>,
    )

    private data class FactKey(
        val predicate: PredicateId,
        val origin: TrackedValueId,
    )

    private data class Fact(
        val truth: Boolean,
        val proof: ProofEstablishment,
    )
}
