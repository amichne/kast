package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** An actual source literal executes through one reviewed library call, without a compiled-body invocation. */
class ValueReviewedCallbackTarget
private constructor(
    val binding: ReviewedLibraryBinding,
    val argument: RelationOccurrence,
    val formal: ValueFormalParameter,
) : ValueCallTarget {
    companion object {
        internal fun fromCompiler(
            binding: ReviewedLibraryBinding,
            argument: RelationOccurrence,
            formal: ValueFormalParameter,
        ): Refinement<ValueReviewedCallbackTarget, ValueCallBindingFailure> {
            val call = binding.invocation
            val execution = formal.executionOwner
            if (call.basis != execution.basis) return Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH)
            val body = execution.body as? ValueExecutionBody.Anonymous ?: return mismatch()
            if (binding.model != ReviewedValueModel.Let) return mismatch()
            if (
                !matchesFormal(call, formal) ||
                    !matchesAnonymous(body.callable) ||
                    !matchesArgument(call, body.callable, argument)
            )
                return mismatch()
            return Refinement.Refined(ValueReviewedCallbackTarget(binding, argument, formal))
        }

        private fun mismatch() = Refinement.Rejected(ValueCallBindingFailure.CALLBACK_PROOF_MISMATCH)

        private fun matchesFormal(call: ValueInvocation, formal: ValueFormalParameter): Boolean =
            formal.position.value == 0 &&
                formal.callable == formal.executionOwner.enclosing &&
                formal.executionOwner.enclosing == call.enclosing

        private fun matchesAnonymous(body: RelationCallableBody.Anonymous): Boolean =
            body.file is SymbolDiscoveryFileIdentity.Workspace &&
                body.signature.receiver == CanonicalCompilerReceiver.Absent &&
                body.signature.valueParameters.size == 1

        private fun matchesArgument(
            call: ValueInvocation,
            body: RelationCallableBody.Anonymous,
            argument: RelationOccurrence,
        ): Boolean =
            argument.file == body.file && argument.range == body.range && call.range.containsValueRange(argument.range)
    }

    override fun equals(other: Any?): Boolean =
        other is ValueReviewedCallbackTarget &&
            binding == other.binding &&
            argument == other.argument &&
            formal == other.formal &&
            formal.callable == other.formal.callable &&
            formal.executionOwner.enclosing == other.formal.executionOwner.enclosing &&
            formal.executionOwner.body == other.formal.executionOwner.body

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + argument.hashCode()
        result = 31 * result + formal.hashCode()
        result = 31 * result + formal.callable.hashCode()
        result = 31 * result + formal.executionOwner.enclosing.hashCode()
        return 31 * result + formal.executionOwner.body.hashCode()
    }
}
