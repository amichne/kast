package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** The reviewed call supplies a literal parameter. Its source body determines all later value flow. */
internal fun reviewedCallbackInput(
    source: ValueSite,
    frame: ValueCallFrame,
): Refinement<ValueSite, ValueModelApplicationFailure> {
    val proof = frame.target as? ValueReviewedCallbackTarget ?: return wrongRule()
    val call = proof.binding.invocation
    if (source.basis != call.basis) return wrongBasis()
    val role = source.role as? ValueRole.Receiver ?: return wrongSource()
    if (role.kind != ValueReceiverKind.EXTENSION)
        return Refinement.Rejected(ValueModelApplicationFailure.RECEIVER_KIND_MISMATCH)
    if (
        !proof.binding.matchesInvocation(role.call) ||
            source.enclosing != call.enclosing ||
            !source.executionOwner.matchesFullExecution(call.executionOwner)
    )
        return wrongSource()
    val stack =
        when (val entered = source.callStack.enter(frame)) {
            is Refinement.Refined -> entered.value
            is Refinement.Rejected -> return wrongSource()
        }
    return when (
        val target =
            ValueSite.fromCompiler(
                enclosing = frame.callee.enclosing,
                range = proof.formal.range,
                role = ValueRole.Formal(ValueFormalPosition.Parameter(proof.formal)),
                executionOwner = frame.callee,
                valuePath = source.valuePath,
                callStack = stack,
                predicates = source.predicates,
            )
    ) {
        is Refinement.Refined -> target
        is Refinement.Rejected -> wrongBasis()
    }
}

/** Only a genuine normal Return under the same reviewed frame can supply that call's result. */
internal fun reviewedCallbackReturn(
    source: ValueSite,
    frame: ValueCallFrame,
): Refinement<ValueSite, ValueModelApplicationFailure> {
    val proof = frame.target as? ValueReviewedCallbackTarget ?: return wrongRule()
    if (source.basis != proof.binding.invocation.basis) return wrongBasis()
    if (
        source.role != ValueRole.Return ||
            source.enclosing != frame.callee.enclosing ||
            !source.executionOwner.matchesFullExecution(frame.callee)
    )
        return wrongSource()
    val stack =
        when (val exited = source.callStack.exit(frame)) {
            is Refinement.Refined -> exited.value
            is Refinement.Rejected -> return wrongSource()
        }
    return when (
        val target =
            proof.binding.invocation
                .resultSite()
                .withState(valuePath = source.valuePath, callStack = stack, predicates = source.predicates)
    ) {
        is Refinement.Refined -> target
        is Refinement.Rejected -> wrongBasis()
    }
}

private fun ValueExecutionOwner.matchesFullExecution(other: ValueExecutionOwner): Boolean =
    this == other && enclosing == other.enclosing && body == other.body

private fun wrongSource() = Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH)

private fun wrongBasis() = Refinement.Rejected(ValueModelApplicationFailure.BASIS_MISMATCH)

private fun wrongRule() = Refinement.Rejected(ValueModelApplicationFailure.RULE_MISMATCH)
