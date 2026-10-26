package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

/** Restore one exact supplying call before reading its selected immutable callback value. */
internal fun nativeCallbackInputSupplier(
    frame: ValueCallFrame,
    formal: NativeCallbackSupplierFormal.Found,
    context: IntellijCallbackFlowContext,
    projection: IntellijK2RelationProjection,
    summaries: CallbackParameterSummaries,
): Refinement<CallbackParameterSupplier, NativeCallbackInputFailure> {
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
                    .readExact(syntax.call, formal.function, formal.parameter, formal.formal)
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return Refinement.Rejected(NativeCallbackInputFailure.Callback(read.failure))
        }
    if (selected.binding.invocation != frame.invocation) return callbackInputRejected()
    return when (val values = IntellijCallbackSupplierValues(context, summaries).read(selected, formal.formal)) {
        is Refinement.Rejected -> Refinement.Rejected(NativeCallbackInputFailure.Callback(values.failure))
        is Refinement.Refined ->
            if (values.value.size == 1) Refinement.Refined(values.value.single())
            else callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    }
}
