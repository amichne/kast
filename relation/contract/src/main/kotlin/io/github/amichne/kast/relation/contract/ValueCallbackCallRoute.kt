package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** One admitted callback route corresponds to an exact contiguous suffix of active source calls. */
class ValueCallbackCallRoute
private constructor(
    val supplierFrame: ValueCallFrame,
    val invocationFrame: ValueCallFrame,
) {
    companion object {
        fun fromActiveStack(
            invocation: ValueCaptureInvocation,
            stack: ValueCallStack,
        ): Refinement<ValueCallbackCallRoute, ValueFlowUnsupportedCause> {
            val bindings = listOf(invocation.supply) + invocation.invocation.forwardings.map { it.target }
            if (bindings.size > stack.frames.size) return rejected()
            val frames = stack.frames.takeLast(bindings.size)
            if (frames.zip(bindings).any { (frame, binding) -> !frame.matchesBinding(binding) }) return rejected()
            if (frames.zipWithNext().any { (caller, callee) -> caller.callee != callee.invocation.executionOwner })
                return rejected()
            if (!invocation.admitsFormalRoute()) return rejected()
            val owner = invocation.invocation.owner as? RelationCallableBody.Named ?: return rejected()
            if (!frames.last().callee.enclosing.matchesCallbackRoot(owner.evidence)) return rejected()
            return Refinement.Refined(ValueCallbackCallRoute(frames.first(), frames.last()))
        }

        private fun ValueCaptureInvocation.admitsFormalRoute(): Boolean {
            var formal = (supply.formalIdentity() as? Refinement.Refined)?.value ?: return false
            for (forwarding in invocation.forwardings) {
                if (forwarding.source != formal) return false
                val owner = forwarding.target.invocationOwner as? RelationCallableBody.Named ?: return false
                if (!formal.callable.matchesCallbackRoot(owner.evidence)) return false
                formal = (forwarding.target.formalIdentity() as? Refinement.Refined)?.value ?: return false
            }
            return true
        }

        private fun ValueCallFrame.matchesBinding(binding: CallbackArgumentBinding): Boolean =
            callee == binding.frame.callee &&
                target == binding.frame.target &&
                callee.context == ValueExecutionContext.Declaration &&
                invocation.basis == binding.invocation.basis &&
                invocation.enclosing.valueIdentity == binding.invocation.enclosing.valueIdentity &&
                invocation.range == binding.invocation.range &&
                invocation.callable.valueIdentity == binding.invocation.callable.valueIdentity

        private fun rejected() = Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    }
}
