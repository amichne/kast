package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Retains the exact formal read supplied to the destination formal; containment alone is not value identity. */
@ConsistentCopyVisibility
data class CallbackParameterForwarding
private constructor(
    val source: CallbackParameterIdentity,
    val argument: RelationOccurrence,
    val target: CallbackArgumentBinding,
    val callableTransfers: List<ValueTransfer>,
) {
    /** Both endpoints are detached storage; a call result site accounts only for its enclosing endpoint. */
    val retainedBytes: Long
        get() =
            2048L
                .addBytes(source.callable.detachedTextUnits().multiplyBytes(2))
                .addBytes(target.invocation.enclosing.detachedTextUnits().multiplyBytes(2))
                .addBytes(target.invocation.callable.detachedTextUnits().multiplyBytes(2))
                .addBytes(
                    callableTransfers.fold(0L) { bytes, transfer ->
                        bytes.addBytes(transfer.source.retainedBytes).addBytes(transfer.target.retainedBytes)
                    }
                )

    companion object {
        fun fromCompiler(
            source: CallbackParameterIdentity,
            argument: RelationOccurrence,
            target: CallbackArgumentBinding,
            callableTransfers: List<ValueTransfer> = emptyList(),
        ): Refinement<CallbackParameterForwarding, CallbackInvocationFlowFailure> =
            when {
                source.callable.lease.identity != target.invocation.basis ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
                source.callable.valueIdentity != target.invocation.enclosing.valueIdentity ||
                    argument.file != source.callable.file ||
                    !target.invocation.range.containsValueRange(argument.range) ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
                callableTransfers.any {
                    it.kind != ValueTransferKind.LOCAL_BINDING && it.kind != ValueTransferKind.LOCAL_READ
                } -> Refinement.Rejected(CallbackInvocationFlowFailure.UNSUPPORTED_CALLABLE_TRANSFER)
                callableTransfers.any {
                    it.source.basis != source.callable.lease.identity ||
                        it.source.enclosing.valueIdentity != source.callable.valueIdentity ||
                        it.target.enclosing.valueIdentity != source.callable.valueIdentity
                } -> Refinement.Rejected(CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH)
                !admitsCallableRoute(callableTransfers, argument) ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH)
                else ->
                    Refinement.Refined(
                        CallbackParameterForwarding(
                            source,
                            argument,
                            target,
                            Collections.unmodifiableList(callableTransfers.toList()),
                        )
                    )
            }
    }
}
