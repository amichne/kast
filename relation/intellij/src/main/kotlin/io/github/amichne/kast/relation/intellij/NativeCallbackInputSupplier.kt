package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

/** Follow only adjacent active calls until the selected callback has an immutable source value. */
internal fun nativeCallbackInputSupplier(
    frames: List<ValueCallFrame>,
    formal: NativeCallbackSupplierFormal.Found,
    context: IntellijCallbackFlowContext,
    projection: IntellijK2RelationProjection,
    summaries: CallbackParameterSummaries,
): Refinement<CallbackParameterSupplier, NativeCallbackInputFailure> {
    var selectedFormal = formal
    for (index in frames.indices.reversed()) {
        val frame = frames[index]
        if (!frame.admitsCallbackFormal(selectedFormal))
            return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        val selected =
            when (
                val read =
                    callbackSupplierCall(
                        frame = frame,
                        formal = selectedFormal,
                        context = context,
                        projection = projection,
                        summaries = summaries,
                    )
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val upstream =
            when (val read = callbackSupplierUpstream(selected, context)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        when (upstream) {
            NativeCallbackSupplierFormal.NotFormal ->
                return callbackSupplierValue(
                    selected = selected,
                    formal = selectedFormal,
                    context = context,
                    summaries = summaries,
                )
            is NativeCallbackSupplierFormal.Found -> {
                if (frames.getOrNull(index - 1)?.callee != frame.invocation.executionOwner)
                    return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                selectedFormal = upstream
            }
        }
    }
    return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
}

private fun ValueCallFrame.admitsCallbackFormal(formal: NativeCallbackSupplierFormal.Found): Boolean =
    callee.context == ValueExecutionContext.Declaration &&
        invocation.executionOwner.context == ValueExecutionContext.Declaration &&
        callee.enclosing.valueIdentity == formal.formal.callable.valueIdentity

private fun callbackSupplierUpstream(
    selected: NativeCallbackSupplierCall,
    context: IntellijCallbackFlowContext,
): Refinement<NativeCallbackSupplierFormal, NativeCallbackInputFailure> =
    if (selected.selection == NativeCallbackSupplierSelection.EXPLICIT)
        when (val read = IntellijCallbackSupplierFormal(context).read(selected.value, selected.lexicalOwner)) {
            is Refinement.Refined -> read
            is Refinement.Rejected -> Refinement.Rejected(NativeCallbackInputFailure.Callback(read.failure))
        }
    else Refinement.Refined(NativeCallbackSupplierFormal.NotFormal)

private fun callbackSupplierCall(
    frame: ValueCallFrame,
    formal: NativeCallbackSupplierFormal.Found,
    context: IntellijCallbackFlowContext,
    projection: IntellijK2RelationProjection,
    summaries: CallbackParameterSummaries,
): Refinement<NativeCallbackSupplierCall, NativeCallbackInputFailure> {
    val host =
        when (val found = projection.subject(context.scope, frame.invocation.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> found.declaration
            is IntellijRelationSubjectLookup.Rejected -> return callbackInputRejected()
        }
    val syntax = host.exactValueElement(frame.invocation.range)?.nativeValueCallSyntax()
    if (syntax !is NativeValueCallSyntax.Explicit) return callbackInputRejected()
    val selected =
        when (
            val read =
                IntellijCallbackSupplierCalls(context, summaries)
                    .readExact(
                        call = syntax.call,
                        function = formal.function,
                        parameter = formal.parameter,
                        formal = formal.formal,
                        frame = frame,
                    )
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return Refinement.Rejected(NativeCallbackInputFailure.Callback(read.failure))
        }
    return if (selected.binding.frame == frame) Refinement.Refined(selected) else callbackInputRejected()
}

private fun callbackSupplierValue(
    selected: NativeCallbackSupplierCall,
    formal: NativeCallbackSupplierFormal.Found,
    context: IntellijCallbackFlowContext,
    summaries: CallbackParameterSummaries,
): Refinement<CallbackParameterSupplier, NativeCallbackInputFailure> =
    when (val values = IntellijCallbackSupplierValues(context, summaries).read(selected, formal.formal)) {
        is Refinement.Rejected -> Refinement.Rejected(NativeCallbackInputFailure.Callback(values.failure))
        is Refinement.Refined ->
            if (values.value.size == 1) Refinement.Refined(values.value.single())
            else callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    }
