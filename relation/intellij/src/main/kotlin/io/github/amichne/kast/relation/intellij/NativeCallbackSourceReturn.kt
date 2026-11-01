package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueCallbackCallRoute
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
    val frame =
        when (val value = callbackReturnFrame(source, invocation, supplier)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val callee = frame.callee
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
        source = source,
        target = target,
        kind = ValueTransferKind.SOURCE_CALL_RETURN,
        operation = ValueSourceOperation.CallbackReturn(invocation, frame),
    )
}

private fun callbackReturnFrame(
    source: ValueSite,
    invocation: ValueCaptureInvocation,
    supplier: ValueExecutionOwner,
): Refinement<ValueCallFrame, ValueFlowUnsupportedCause> =
    when (val route = ValueCallbackCallRoute.fromActiveStack(invocation, source.callStack)) {
        is Refinement.Refined ->
            if (route.value.supplierFrame.invocation.executionOwner == supplier)
                Refinement.Refined(route.value.invocationFrame)
            else callbackReturnRejected()
        is Refinement.Rejected ->
            if (invocation.invocation.forwardings.isEmpty()) callbackReturnSupplierFrame(invocation, supplier)
            else callbackReturnRejected()
    }

private fun callbackReturnSupplierFrame(
    invocation: ValueCaptureInvocation,
    supplier: ValueExecutionOwner,
): Refinement<ValueCallFrame, ValueFlowUnsupportedCause> {
    val supplied = invocation.supply.invocation
    val rebound =
        when (
            val value =
                ValueInvocation.fromCompiler(
                    enclosing = supplied.enclosing,
                    range = supplied.range,
                    callable = supplied.callable,
                    executionOwner = supplier,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return callbackReturnRejected()
        }
    val frame = invocation.supply.frame
    val selected =
        when (val target = frame.target) {
            ValueCallTarget.Direct -> ValueCallFrame.direct(rebound, frame.callee)
            is ValueCallTarget.Implementation ->
                ValueCallFrame.implementation(
                    invocation = rebound,
                    dispatch = target.dispatch,
                    relation = target.relation,
                    ownership = target.ownership,
                )
        }
    return when (selected) {
        is Refinement.Refined -> selected
        is Refinement.Rejected -> callbackReturnRejected()
    }
}

private fun callbackReturnRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
