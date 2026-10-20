package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface ValueFlowObligationEvidence {
    data object None : ValueFlowObligationEvidence

    data class ImplementationDispatch internal constructor(val dispatch: ValueImplementationDispatch) :
        ValueFlowObligationEvidence

    data class CallbackFlow internal constructor(val flow: CallbackInvocationFlow) : ValueFlowObligationEvidence

    data class CallbackUnavailable internal constructor(val cause: CallbackInvocationFlowCause) :
        ValueFlowObligationEvidence

    data class CallbackContractRejected internal constructor(val cause: CallbackInvocationFlowFailure) :
        ValueFlowObligationEvidence
}

enum class ValueFlowObligationFailure {
    BASIS_MISMATCH,
    SOURCE_MISMATCH,
}

/** Unresolved target-set evidence cannot confer a positive transfer or a terminal model. */
@ConsistentCopyVisibility
data class ValueFlowObligation
private constructor(
    val site: ValueSite,
    val cause: ValueFlowUnsupportedCause,
    val evidence: ValueFlowObligationEvidence,
) {
    constructor(site: ValueSite, cause: ValueFlowUnsupportedCause) : this(site, cause, ValueFlowObligationEvidence.None)

    internal fun shareCallableEvidence(shareSite: (ValueSite) -> ValueSite): ValueFlowObligation {
        val shared = shareSite(site)
        return if (shared === site) this else ValueFlowObligation(shared, cause, evidence)
    }

    companion object {
        fun fromCallbackContractRejected(
            site: ValueSite,
            cause: CallbackInvocationFlowFailure,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            if (!site.isCallbackRefusalSite()) return Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
            val outer =
                if (cause == CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED)
                    ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED
                else ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN
            return Refinement.Refined(
                ValueFlowObligation(site, outer, ValueFlowObligationEvidence.CallbackContractRejected(cause))
            )
        }

        fun fromCallbackUnavailable(
            site: ValueSite,
            cause: CallbackInvocationFlowCause,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            if (!site.isCallbackRefusalSite()) return Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
            return Refinement.Refined(
                ValueFlowObligation(
                    site,
                    callbackUnavailableCause(cause),
                    ValueFlowObligationEvidence.CallbackUnavailable(cause),
                )
            )
        }

        fun fromCallback(
            site: ValueSite,
            flow: CallbackInvocationFlow,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            if (site.basis != flow.basis) return Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)
            if (!site.matchesCallbackQuestion(flow))
                return Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
            return Refinement.Refined(
                ValueFlowObligation(
                    site,
                    ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN,
                    ValueFlowObligationEvidence.CallbackFlow(flow),
                )
            )
        }

        /** A seed question keeps its actual bound supplier without claiming an active caller or a positive edge. */
        fun fromSeedCallback(
            site: ValueSite,
            flow: CallbackInvocationFlow,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            if (site.basis != flow.basis) return Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)
            if (!site.matchesSeedCallbackQuestion(flow))
                return Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
            return Refinement.Refined(
                ValueFlowObligation(
                    site,
                    ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN,
                    ValueFlowObligationEvidence.CallbackFlow(flow),
                )
            )
        }

        /** An anonymous return question keeps its bound supplier without an active callback frame. */
        fun fromSeedCallbackReturn(
            site: ValueSite,
            flow: CallbackInvocationFlow,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            if (site.basis != flow.basis) return Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)
            if (!site.matchesSeedCallbackReturnQuestion(flow))
                return Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
            return Refinement.Refined(
                ValueFlowObligation(
                    site,
                    ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN,
                    ValueFlowObligationEvidence.CallbackFlow(flow),
                )
            )
        }

        fun fromImplementation(
            site: ValueSite,
            cause: ValueFlowUnsupportedCause,
            dispatch: ValueImplementationDispatch,
        ): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
            val question = dispatch.batch.request
            if (site.basis != question.subject.lease.identity)
                return Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH)
            val matches =
                when (val role = site.role) {
                    is ValueRole.Argument -> role.call.callable.valueIdentity == question.subject.valueIdentity
                    is ValueRole.Receiver -> role.call.callable.valueIdentity == question.subject.valueIdentity
                    // A return can preserve an unresolved caller investigation without asserting its relationship.
                    ValueRole.Return -> true
                    is ValueRole.FunctionArgument,
                    is ValueRole.Formal,
                    ValueRole.ExpressionResult,
                    ValueRole.LocalBinding,
                    ValueRole.LocalRead,
                    ValueRole.PropertyAssignment -> false
                }
            return if (matches)
                Refinement.Refined(
                    ValueFlowObligation(site, cause, ValueFlowObligationEvidence.ImplementationDispatch(dispatch))
                )
            else Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH)
        }
    }
}

private fun ValueSite.isCallbackRefusalSite(): Boolean =
    role is ValueRole.FunctionArgument ||
        role == ValueRole.Return &&
            (callStack == ValueCallStack.Empty && executionOwner.body is ValueExecutionBody.Anonymous ||
                callStack.frames.lastOrNull()?.let { frame ->
                    executionOwner == frame.callee && frame.target is ValueCallbackTarget
                } == true)

private fun ValueSite.matchesSeedCallbackReturnQuestion(flow: CallbackInvocationFlow): Boolean {
    if (role != ValueRole.Return || callStack != ValueCallStack.Empty) return false
    val body = executionOwner.body as? ValueExecutionBody.Anonymous ?: return false
    if (flow.body != body.callable || !enclosing.hasSameProofAs(executionOwner.enclosing)) return false
    val binding = (flow.binding as? CallbackBindingEvidence.Bound)?.binding ?: return false
    val supplier = binding.invocation
    return supplier.enclosing.hasSameProofAs(enclosing) &&
        supplier.executionOwner.body == ValueExecutionBody.Declaration &&
        supplier.executionOwner.enclosing.hasSameProofAs(enclosing) &&
        supplier.range.containsValueRange(body.callable.range)
}

private fun callbackUnavailableCause(cause: CallbackInvocationFlowCause): ValueFlowUnsupportedCause =
    when (cause) {
        CallbackInvocationFlowCause.FINALLY_UNSUPPORTED -> ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED
        CallbackInvocationFlowCause.ABRUPT_COMPLETION -> ValueFlowUnsupportedCause.ABRUPT_COMPLETION
        CallbackInvocationFlowCause.EXTERNAL_CALLABLE -> ValueFlowUnsupportedCause.EXTERNAL_CALL
        CallbackInvocationFlowCause.OUTSIDE_DOMAIN -> ValueFlowUnsupportedCause.OUTSIDE_DOMAIN
        CallbackInvocationFlowCause.WORK_LIMIT_REACHED -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        CallbackInvocationFlowCause.TIME_LIMIT_REACHED -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        CallbackInvocationFlowCause.RESULT_LIMIT_REACHED -> ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED
        CallbackInvocationFlowCause.BYTE_LIMIT_REACHED -> ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED
        CallbackInvocationFlowCause.PROOF_LIMIT_REACHED -> ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED
        CallbackInvocationFlowCause.STORED_CALLBACK,
        CallbackInvocationFlowCause.RETURNED_CALLBACK,
        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
        CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING,
        CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE,
        CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN,
        CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN,
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE,
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED,
        CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE,
        CallbackInvocationFlowCause.PARAMETER_ESCAPES,
        CallbackInvocationFlowCause.CALLBACK_CYCLE,
        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
        CallbackInvocationFlowCause.NO_INVOCATION_PROVEN -> ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN
    }

private fun ValueSite.matchesCallbackQuestion(flow: CallbackInvocationFlow): Boolean =
    when (val position = role) {
        is ValueRole.FunctionArgument -> {
            val binding = flow.binding as? CallbackBindingEvidence.Bound
            binding != null &&
                position.call.executionOwner == callStack.frames.lastOrNull()?.callee &&
                binding.binding.invocation == callStack.frames.lastOrNull()?.invocation
        }
        ValueRole.Return ->
            callStack.frames.lastOrNull()?.let { frame ->
                executionOwner == frame.callee &&
                    (frame.target as? ValueCallbackTarget)?.flow?.hasSameCallbackQuestion(flow) == true
            } == true
        is ValueRole.Argument,
        is ValueRole.Receiver,
        is ValueRole.Formal,
        ValueRole.ExpressionResult,
        ValueRole.LocalBinding,
        ValueRole.LocalRead,
        ValueRole.PropertyAssignment -> false
    }

private fun ValueSite.matchesSeedCallbackQuestion(flow: CallbackInvocationFlow): Boolean {
    val actual = role as? ValueRole.FunctionArgument ?: return false
    if (callStack != ValueCallStack.Empty || executionOwner.body != ValueExecutionBody.Declaration) return false
    if (
        !enclosing.hasSameProofAs(actual.call.enclosing) ||
            !executionOwner.enclosing.hasSameProofAs(actual.call.executionOwner.enclosing)
    )
        return false
    if (executionOwner.body != actual.call.executionOwner.body) return false
    val binding = (flow.binding as? CallbackBindingEvidence.Bound)?.binding ?: return false
    if (!binding.invocation.callable.hasSameProofAs(executionOwner.enclosing)) return false
    return binding.parameter.file == executionOwner.enclosing.file &&
        executionOwner.range.containsValueRange(binding.parameter.range)
}
