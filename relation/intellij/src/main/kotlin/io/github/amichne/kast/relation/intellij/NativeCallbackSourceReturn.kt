package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCaptureInvocation
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** A callback returns through its exact supplier, reusing a frame already active during argument entry. */
internal fun nativeCallbackSourceReturn(
    source: ValueSite,
    invocation: ValueCaptureInvocation,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val supplier =
        when (val value = source.executionOwner.supplierOf(invocation)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val supplied = invocation.supply.invocation
    val rebound =
        when (
            val value = ValueInvocation.fromCompiler(supplied.enclosing, supplied.range, supplied.callable, supplier)
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return callbackReturnRejected()
        }
    val callee = ValueExecutionOwner.declaration(rebound.callable)
    val frame =
        when (val value = ValueCallFrame.direct(rebound, callee)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return callbackReturnRejected()
        }
    val stack =
        if (source.callStack.frames.lastOrNull() == frame) source.callStack
        else
            when (val value = source.callStack.enter(frame)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativeCallCause())
            }
    val target =
        when (
            val value =
                ValueSite.fromCompiler(
                    enclosing = callee.enclosing,
                    range = invocation.invocation.occurrence.range,
                    role = ValueRole.ExpressionResult,
                    executionOwner = callee,
                    valuePath = source.valuePath,
                    callStack = stack,
                    conditions = source.conditions,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return callbackReturnRejected()
        }
    return sourceTransfer(
        source,
        target,
        ValueTransferKind.SOURCE_CALL_RETURN,
        ValueSourceOperation.CallbackReturn(invocation, frame),
    )
}

private fun callbackReturnRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
