package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import java.util.Collections

/** Formal identity is the mapped callable and parameter position, never the argument's lexical position. */
@ConsistentCopyVisibility
data class CallbackArgumentBinding
private constructor(
    val frame: ValueCallFrame,
    val invocationOwner: RelationCallableBody,
    val position: ValueArgumentPosition,
    val parameter: RelationOccurrence,
) {
    /** The compiler-resolved call target remains distinct from the selected implementation body. */
    val invocation: ValueInvocation
        get() = frame.invocation

    val callable: RelationEndpoint
        get() = frame.callee.enclosing

    companion object {
        fun fromCompiler(
            invocation: ValueInvocation,
            invocationOwner: RelationCallableBody,
            position: ValueArgumentPosition,
            parameter: RelationOccurrence,
        ): Refinement<CallbackArgumentBinding, CallbackInvocationFlowFailure> =
            when (val frame = ValueCallFrame.direct(invocation, ValueExecutionOwner.declaration(invocation.callable))) {
                is Refinement.Refined ->
                    fromCompiler(
                        frame = frame.value,
                        invocationOwner = invocationOwner,
                        position = position,
                        parameter = parameter,
                    )
                is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
            }

        fun fromCompiler(
            frame: ValueCallFrame,
            invocationOwner: RelationCallableBody,
            position: ValueArgumentPosition,
            parameter: RelationOccurrence,
        ): Refinement<CallbackArgumentBinding, CallbackInvocationFlowFailure> {
            val invocation = frame.invocation
            val callable = frame.callee.enclosing
            return when {
                frame.callee.context != ValueExecutionContext.Declaration ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
                invocationOwner.file != invocation.enclosing.file ||
                    !invocationOwner.range.containsValueRange(invocation.range) ||
                    !invocation.enclosing.range.containsValueRange(invocationOwner.range) ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_SUPPLYING_OWNER)
                parameter.file != callable.file || !callable.range.containsValueRange(parameter.range) ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.PARAMETER_OUTSIDE_CALLABLE)
                position.value !in (callable.signature as CanonicalCompilerCallableSignature).valueParameters.indices ||
                    position.value !in
                        (invocation.callable.signature as CanonicalCompilerCallableSignature).valueParameters.indices ->
                    Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_PARAMETER_POSITION)
                else -> Refinement.Refined(CallbackArgumentBinding(frame, invocationOwner, position, parameter))
            }
        }
    }
}

/** An invocation inside a nested body keeps that body, even when its execution behavior is unknown. */
@ConsistentCopyVisibility
data class CallbackParameterInvocation
private constructor(
    val occurrence: RelationOccurrence,
    val owner: RelationCallableBody,
    val callableTransfers: List<ValueTransfer>,
    val forwardings: List<CallbackParameterForwarding>,
) {
    val retainedBytes: Long
        get() = 4096L + canonicalProjection().toByteArray(Charsets.UTF_8).size * 4L

    companion object {
        fun fromCompiler(
            occurrence: RelationOccurrence,
            owner: RelationCallableBody,
            callableTransfers: List<ValueTransfer> = emptyList(),
            forwardings: List<CallbackParameterForwarding> = emptyList(),
        ): Refinement<CallbackParameterInvocation, CallbackInvocationFlowFailure> {
            if (owner.file != occurrence.file || !owner.range.containsValueRange(occurrence.range))
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER)
            if (
                callableTransfers.any {
                    it.kind != ValueTransferKind.LOCAL_BINDING && it.kind != ValueTransferKind.LOCAL_READ
                }
            )
                return Refinement.Rejected(CallbackInvocationFlowFailure.UNSUPPORTED_CALLABLE_TRANSFER)
            if (!admitsCallableRoute(callableTransfers, occurrence))
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH)
            return Refinement.Refined(
                CallbackParameterInvocation(
                    occurrence,
                    owner,
                    Collections.unmodifiableList(callableTransfers.toList()),
                    Collections.unmodifiableList(forwardings.toList()),
                )
            )
        }
    }
}

internal fun admitsCallableRoute(transfers: List<ValueTransfer>, occurrence: RelationOccurrence): Boolean {
    if (transfers.isEmpty()) return true
    if (transfers.zipWithNext().any { (left, right) -> left.target != right.source }) return false
    if (transfers.first().source.role != ValueRole.ExpressionResult) return false
    val arrival = transfers.last().target
    return arrival.role == ValueRole.LocalRead &&
        arrival.enclosing.file == occurrence.file &&
        occurrence.range.containsValueRange(arrival.range)
}
