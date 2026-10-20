package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver

/** A selected static callback route keeps its supplier and invocation evidence with its active source frame. */
class ValueCallbackTarget
private constructor(
    val flow: CallbackInvocationFlow,
    val selected: CallbackParameterInvocation,
    val supplyingFrame: ValueCallFrame,
) : ValueCallTarget {
    internal val canonical: String
        get() = buildString {
            field(flow.canonicalProjection())
            field(selected.canonicalProjection())
            callFrame(supplyingFrame)
        }

    private val canonicalHashCode = canonical.hashCode()
    val retainedIdentityBytes: Long = Int.SIZE_BYTES.toLong()

    companion object {
        fun fromCompiler(
            invocation: ValueFunctionInvocation,
            callee: ValueExecutionOwner,
            flow: CallbackInvocationFlow,
            selected: CallbackParameterInvocation,
            supplyingFrame: ValueCallFrame,
        ): Refinement<ValueCallbackTarget, ValueCallBindingFailure> {
            if (!CallbackValueProofLimits.admits(flow, selected, supplyingFrame))
                return Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_LIMIT_REACHED)
            val bases = listOf(invocation.basis, callee.basis, flow.basis, supplyingFrame.invocation.basis)
            if (bases.distinct().size != 1) return Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH)
            val body = callee.body as? ValueExecutionBody.Anonymous ?: return mismatch()
            val binding = flow.binding as? CallbackBindingEvidence.Bound ?: return mismatch()
            if (!matchesSupply(binding.binding, supplyingFrame, invocation)) return mismatch()
            if (!matchesSelection(flow, selected, invocation)) return mismatch()
            val receiverCount = if (body.callable.signature.receiver is CanonicalCompilerReceiver.Present) 1 else 0
            if (
                flow.body != body.callable ||
                    body.callable.signature.valueParameters.size + receiverCount !=
                        invocation.signature.valueParameters.size
            )
                return mismatch()
            return Refinement.Refined(ValueCallbackTarget(flow, selected, supplyingFrame))
        }

        private fun mismatch() = Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH)
    }

    override fun equals(other: Any?): Boolean = other is ValueCallbackTarget && canonical == other.canonical

    override fun hashCode(): Int = canonicalHashCode
}

private fun matchesSupply(
    binding: CallbackArgumentBinding,
    supplyingFrame: ValueCallFrame,
    invocation: ValueFunctionInvocation,
): Boolean = matchesSupplyingCall(binding, supplyingFrame) && supplyingFrame.callee == invocation.executionOwner

private fun matchesSupplyingCall(binding: CallbackArgumentBinding, frame: ValueCallFrame): Boolean =
    frame.invocation is ValueInvocation &&
        binding.invocation == frame.invocation &&
        binding.invocation.callable.valueIdentity == frame.callee.enclosing.valueIdentity

private fun matchesSelection(
    flow: CallbackInvocationFlow,
    selected: CallbackParameterInvocation,
    invocation: ValueFunctionInvocation,
): Boolean =
    selected in flow.invocations &&
        matchesSelectedOccurrence(selected, invocation) &&
        matchesSelectedOwner(selected, invocation.executionOwner)

private fun matchesSelectedOccurrence(
    selected: CallbackParameterInvocation,
    invocation: ValueFunctionInvocation,
): Boolean = selected.occurrence.file == invocation.enclosing.file && selected.occurrence.range == invocation.range

private fun matchesSelectedOwner(selected: CallbackParameterInvocation, execution: ValueExecutionOwner): Boolean =
    selected.owner.compilerIdentity == execution.identity.body.compiler && selected.owner.range == execution.range
