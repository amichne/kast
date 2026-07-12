package io.github.amichne.kast.shared.proofloss.analysis

import io.github.amichne.kast.shared.proofloss.ir.Block
import io.github.amichne.kast.shared.proofloss.ir.BoundaryCall
import io.github.amichne.kast.shared.proofloss.ir.ExitStatement
import io.github.amichne.kast.shared.proofloss.ir.FunctionIr
import io.github.amichne.kast.shared.proofloss.ir.IfStatement
import io.github.amichne.kast.shared.proofloss.ir.LetStatement
import io.github.amichne.kast.shared.proofloss.ir.NoOpStatement
import io.github.amichne.kast.shared.proofloss.ir.PredicateCondition
import io.github.amichne.kast.shared.proofloss.ir.ProofFunctionId
import io.github.amichne.kast.shared.proofloss.ir.ProofSourceSpan
import io.github.amichne.kast.shared.proofloss.ir.TrackedValueId
import io.github.amichne.kast.shared.proofloss.ir.ValueExpression
import io.github.amichne.kast.shared.proofloss.model.ArgumentIndex
import io.github.amichne.kast.shared.proofloss.model.BoundaryId
import io.github.amichne.kast.shared.proofloss.model.PredicateId
import io.github.amichne.kast.shared.proofloss.model.ProofCallableKey
import io.github.amichne.kast.shared.proofloss.model.ProofModel

sealed interface ProofEstablishment {
    val location: ProofSourceSpan

    data class PredicateGuard(override val location: ProofSourceSpan) : ProofEstablishment
    data class MaterializationSuccess(
        override val location: ProofSourceSpan,
        val producedValue: TrackedValueId,
    ) : ProofEstablishment
}

data class ProofLossFinding(
    val functionId: ProofFunctionId,
    val predicate: PredicateId,
    val predicateCallable: ProofCallableKey,
    val boundary: BoundaryId,
    val boundaryCallable: ProofCallableKey,
    val argumentIndex: ArgumentIndex,
    val subject: TrackedValueId,
    val boundaryArgument: TrackedValueId,
    val proof: ProofEstablishment,
    val boundaryCall: ProofSourceSpan,
    val valuePath: List<TrackedValueId>,
    val suggestedMaterializers: Set<ProofCallableKey>,
)

class ProofLossAnalyzer(private val model: ProofModel) {
    fun analyze(function: FunctionIr): List<ProofLossFinding> {
        val initial = State(function.parameters.associateWith { Value(it, emptySet(), listOf(it)) }, emptyMap())
        val findings = mutableListOf<ProofLossFinding>()
        block(function, function.body, initial, findings)
        return findings.distinct()
            .sortedWith(compareBy({ it.boundaryCall.filePath }, { it.boundaryCall.startOffset }, { it.argumentIndex }))
    }

    private fun block(
        fn: FunctionIr,
        block: Block,
        start: State,
        out: MutableList<ProofLossFinding>,
    ): State? {
        var state: State? = start
        block.statements.forEach { statement ->
            state = state?.let { current ->
                when (statement) {
                    is LetStatement -> let(statement, current)
                    is IfStatement -> branch(fn, statement, current, out)
                    is BoundaryCall -> current.also { boundary(fn, statement, it, out) }
                    is ExitStatement -> null
                    is NoOpStatement -> current
                }
            }
        }
        return state
    }

    private fun let(
        s: LetStatement,
        state: State,
    ): State? = when (val e = s.expression) {
        is ValueExpression.Alias -> state.copy(
            values = state.values + (s.target to state.resolve(e.source)
                .let { it.copy(path = it.path + s.target) })
        )
        is ValueExpression.Materialize -> {
            requireNotNull(model.materializer(e.callable))
            require(model.predicateForMaterializer(e.callable)?.id == e.predicate)
            val source = state.resolve(e.source)
            val key = FactKey(e.predicate, source.origin)
            val old = state.facts[key]
            if (old?.truth == false) null else state.copy(
                values = state.values + (s.target to source.copy(
                    proofs = source.proofs + e.predicate,
                    path = source.path + s.target
                )),
                facts = if (old == null) state.facts + (key to Fact(
                    true,
                    ProofEstablishment.MaterializationSuccess(
                        s.location,
                        s.target
                    )
                )) else state.facts,
            )
        }
    }

    private fun branch(
        fn: FunctionIr,
        s: IfStatement,
        state: State,
        out: MutableList<ProofLossFinding>,
    ): State? =
        join(
            state.assume(s.condition, true)?.let { block(fn, s.thenBranch, it, out) },
            state.assume(s.condition, false)?.let { block(fn, s.elseBranch, it, out) })

    private fun boundary(
        fn: FunctionIr,
        call: BoundaryCall,
        state: State,
        out: MutableList<ProofLossFinding>,
    ) {
        val boundary = model.boundary(call.boundary)
        boundary.obligations.forEach { obligation ->
            val argumentId = requireNotNull(call.arguments[obligation.argumentIndex])
            val argument = state.resolve(argumentId)
            val fact = state.facts[FactKey(obligation.predicate, argument.origin)]?.takeIf { it.truth }
                       ?: return@forEach
            if (obligation.predicate in argument.proofs) return@forEach
            val predicate = model.predicate(obligation.predicate)
            out += ProofLossFinding(
                fn.id,
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
                predicate.materializers.mapTo(mutableSetOf()) { it.callable })
        }
    }

    private fun State.assume(
        c: PredicateCondition,
        result: Boolean,
    ): State? {
        val subject = resolve(c.subject)
        val truth = if (result) c.conditionTrueMeansPredicate else !c.conditionTrueMeansPredicate
        if (c.predicate in subject.proofs && !truth) return null
        val key = FactKey(c.predicate, subject.origin)
        val old = facts[key]
        return if (old != null) if (old.truth == truth) this else null else copy(
            facts = facts + (key to Fact(
                truth,
                ProofEstablishment.PredicateGuard(
                    c.location
                )
            ))
        )
    }

    private fun join(
        a: State?,
        b: State?,
    ): State? = when {
        a == null -> b; b == null -> a; else -> State(
            a.values.filter { b.values[it.key] == it.value },
            a.facts.filter { b.facts[it.key] == it.value })
    }

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
