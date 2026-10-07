package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentOmission
import io.github.amichne.kast.relation.contract.CallbackDefaultBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierSelection
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Resolves and retains every compiler-selected immutable value for one exact argument or omission. */
internal class IntellijCallbackSupplierValues(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    private val values = IntellijImmutableCallbackValueResolver(context, summaries)

    fun read(
        call: NativeCallbackSupplierCall,
        formal: CallbackParameterIdentity,
    ): Refinement<List<CallbackParameterSupplier>, CallbackInvocationFlowCause> {
        val lexical =
            when (call.selection) {
                NativeCallbackSupplierSelection.EXPLICIT -> call.lexicalOwner
                NativeCallbackSupplierSelection.DEFAULT ->
                    (formal.callable as? RelationEndpoint.Resolved)?.evidence ?: return unresolved()
            }
        val resolved =
            when (val result = values.resolve(call.value, lexical)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        if (resolved.isEmpty()) return Refinement.Rejected(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)
        val result = mutableListOf<CallbackParameterSupplier>()
        for (origin in resolved) {
            when (val read = selected(call, formal, origin)) {
                is Refinement.Refined -> result += read.value
                is Refinement.Rejected -> return read
            }
        }
        return Refinement.Refined(result)
    }

    private fun selected(
        call: NativeCallbackSupplierCall,
        formal: CallbackParameterIdentity,
        origin: ImmutableCallbackValue,
    ): Refinement<CallbackParameterSupplier, CallbackInvocationFlowCause> {
        val admitted =
            when (call.selection) {
                NativeCallbackSupplierSelection.EXPLICIT -> explicit(call, origin)
                NativeCallbackSupplierSelection.DEFAULT -> default(call, formal, origin)
            }
        val supplier =
            when (admitted) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        when (val retained = summaries.retention.admit(supplier.retainedBytes)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return retained
        }
        summaries.observation.count(IntellijReadCounter.CALLBACK_SUPPLIER_VALUES_CONFIRMED)
        return Refinement.Refined(supplier)
    }

    private fun explicit(
        call: NativeCallbackSupplierCall,
        origin: ImmutableCallbackValue,
    ): Refinement<CallbackParameterSupplier, CallbackInvocationFlowCause> {
        val argument =
            context.site(
                call.value,
                call.binding.invocation.enclosing,
                ValueRole.Argument(call.binding.invocation, call.binding.position),
            ) ?: return unresolved()
        val transfer =
            when (val admitted = ValueTransfer.fromCompiler(origin.destination, argument, ValueTransferKind.ARGUMENT)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unresolved()
            }
        val transported =
            when (
                val admitted =
                    ImmutableCallbackValue.fromCompiler(
                        origin.origin,
                        origin.source,
                        argument,
                        origin.transfers + transfer,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unresolved()
            }
        return admit(call, CallbackSupplierSelection.Explicit(argument), transported)
    }

    private fun default(
        call: NativeCallbackSupplierCall,
        formal: CallbackParameterIdentity,
        origin: ImmutableCallbackValue,
    ): Refinement<CallbackParameterSupplier, CallbackInvocationFlowCause> {
        val occurrence = context.occurrence(call.value) ?: return unresolved()
        val default =
            when (val admitted = CallbackDefaultBinding.fromCompiler(formal, occurrence)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unresolved()
            }
        return admit(
            call,
            CallbackSupplierSelection.Default(CallbackArgumentOmission.fromCompiler(call.binding), default),
            origin,
        )
    }

    private fun admit(
        call: NativeCallbackSupplierCall,
        selection: CallbackSupplierSelection,
        value: ImmutableCallbackValue,
    ): Refinement<CallbackParameterSupplier, CallbackInvocationFlowCause> =
        when (val admitted = CallbackParameterSupplier.fromCompiler(call.binding, selection, value)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> unresolved()
        }

    private fun unresolved() = Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
}
