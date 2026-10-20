package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueTransfer

/** One native return read reports its facts and diagnostic questions to the existing transfer collector. */
internal sealed interface NativeValueReturnEffect {
    data class Transfer(val value: Refinement<ValueTransfer, ValueFlowUnsupportedCause>) : NativeValueReturnEffect

    data class Dispatch(val value: ValueImplementationDispatch) : NativeValueReturnEffect

    data class UnsupportedDispatch(
        val cause: ValueFlowUnsupportedCause,
        val dispatch: ValueImplementationDispatch,
    ) : NativeValueReturnEffect

    data class Rejected(val cause: RelationCompilerRejection) : NativeValueReturnEffect

    data class InventoryIncomplete(val limitations: Set<RelationLimitation>) : NativeValueReturnEffect
}

/** Borrows caller enumeration and capacity from the enclosing one-hop coordinator. */
internal class NativeValueReturnFlow(
    private val owner: PsiElement,
    private val returns: NativeValueSourceReturns,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Boolean,
) {
    private var result: Refinement<Unit, ValueFlowRejection> = Refinement.Refined(Unit)

    fun read(): Refinement<Unit, ValueFlowRejection> {
        val exhausted =
            when {
                returns.isMatched -> returns.matched(consume = ::accept)
                else -> namedCallers()
            }
        if (!exhausted && retained.obligations.isEmpty() && result is Refinement.Refined)
            retained.unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        return result
    }

    private fun namedCallers(): Boolean {
        val named = owner as? PsiNamedElement
        if (named == null) {
            retained.unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            return true
        }
        return returns.forEachCaller(owner = named, permit = permit, consume = ::accept)
    }

    private fun accept(effect: NativeValueReturnEffect): Boolean {
        when (effect) {
            is NativeValueReturnEffect.Transfer -> retained.adopt(effect.value)
            is NativeValueReturnEffect.Dispatch -> recordCoverage(effect.value)
            is NativeValueReturnEffect.UnsupportedDispatch ->
                result = retained.unresolvedImplementation(effect.cause, effect.dispatch)
            is NativeValueReturnEffect.Rejected -> result = Refinement.Rejected(effect.cause.nativeFlowRejection())
            is NativeValueReturnEffect.InventoryIncomplete ->
                effect.limitations.map { it.nativeImplementationCause() }.distinct().forEach(retained::unresolved)
        }
        return result is Refinement.Refined
    }

    private fun recordCoverage(dispatch: ValueImplementationDispatch) {
        val causes =
            when (val coverage = dispatch.coverage) {
                is ValueImplementationCoverage.Exhausted ->
                    if (dispatch.batch.facts.isEmpty()) listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)
                    else emptyList()
                is ValueImplementationCoverage.Incomplete ->
                    coverage.evidence.limitations.map { it.nativeImplementationCause() }.distinct()
            }
        for (cause in causes) {
            result = retained.unresolvedImplementation(cause, dispatch)
            if (result is Refinement.Rejected) return
        }
    }
}
