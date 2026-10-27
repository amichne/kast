package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Source transitions preserve field identity and the matched call context. */
sealed interface ValueSourceOperation {
    data class Enter(val frame: ValueCallFrame, val position: ValueFormalPosition) : ValueSourceOperation

    data class Return(val frame: ValueCallFrame) : ValueSourceOperation

    data class SeedReturn(val frame: ValueCallFrame) : ValueSourceOperation

    data class CallbackReturn(val invocation: ValueCaptureInvocation, val frame: ValueCallFrame) : ValueSourceOperation

    /** A source lambda return reaches its supplier expression through an exact binary result contract. */
    data class DeclaredCallbackResult(val binding: DeclaredCallbackValueBinding) : ValueSourceOperation

    data class CallbackArgumentEntry(
        val invocation: ValueCaptureInvocation,
        val supplierFrame: ValueCallFrame,
        val parameter: ValueFormalParameter,
        val argumentRange: ExactDeclarationTextRange,
    ) : ValueSourceOperation

    data class ReadField(val field: ValueField) : ValueSourceOperation

    data class ConstructField(val call: ValueInvocation, val storage: ValueConstructorStorage) : ValueSourceOperation
}

class ValueSourceTransfer private constructor(val operation: ValueSourceOperation) : ValueTransferEvidence {
    companion object {
        fun fromCompiler(operation: ValueSourceOperation): ValueSourceTransfer = ValueSourceTransfer(operation)
    }

    internal fun admitsSource(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        when (val op = operation) {
            is ValueSourceOperation.Enter -> op.admitsEntry(source, target, kind)
            is ValueSourceOperation.Return ->
                admitsReturn(op.frame, source, target, kind) &&
                    source.callStack.exit(op.frame).matches(target.callStack)
            is ValueSourceOperation.SeedReturn ->
                admitsReturn(op.frame, source, target, kind) &&
                    source.callStack == ValueCallStack.Empty &&
                    target.callStack == ValueCallStack.Empty
            is ValueSourceOperation.CallbackReturn -> op.admitsCallbackReturn(source, target, kind)
            is ValueSourceOperation.DeclaredCallbackResult -> op.admitsDeclaredCallbackResult(source, target, kind)
            is ValueSourceOperation.CallbackArgumentEntry -> op.admitsCallbackArgument(source, target, kind)
            is ValueSourceOperation.ReadField -> op.admitsField(source, target, kind)
            is ValueSourceOperation.ConstructField -> op.admitsConstruction(source, target, kind)
        }

    private fun ValueSourceOperation.Enter.admitsEntry(
        source: ValueSite,
        target: ValueSite,
        kind: ValueTransferKind,
    ): Boolean =
        kind == ValueTransferKind.SOURCE_CALL_ENTRY &&
            target.executionOwner == frame.callee &&
            target.role == ValueRole.Formal(position) &&
            actualMatches(source, this) &&
            source.valuePath == target.valuePath &&
            source.callStack.enter(frame).matches(target.callStack)

    private fun ValueSourceOperation.ReadField.admitsField(
        source: ValueSite,
        target: ValueSite,
        kind: ValueTransferKind,
    ): Boolean =
        kind == ValueTransferKind.FIELD_READ &&
            source.executionOwner == target.executionOwner &&
            field.basis == source.basis &&
            source.callStack == target.callStack &&
            source.valuePath.select(ValuePathSegment.Field(field)) == ValuePathSelection.Selected(target.valuePath)

    private fun ValueSourceOperation.ConstructField.admitsConstruction(
        source: ValueSite,
        target: ValueSite,
        kind: ValueTransferKind,
    ): Boolean =
        kind == ValueTransferKind.CONSTRUCTOR_FIELD &&
            source.role is ValueRole.Argument &&
            source.role.call == call &&
            target.isResultOf(call) &&
            source.callStack == target.callStack &&
            storage.constructor.valueIdentity == call.callable.valueIdentity &&
            storage.parameter.position == source.role.position &&
            source.valuePath.prepend(ValuePathSegment.Field(storage.field)).matches(target.valuePath)

    private fun actualMatches(source: ValueSite, operation: ValueSourceOperation.Enter): Boolean =
        when (val position = operation.position) {
            is ValueFormalPosition.Parameter ->
                position.parameter.executionOwner == operation.frame.callee &&
                    source.role is ValueRole.Argument &&
                    source.role.call == operation.frame.invocation &&
                    source.role.position == position.parameter.position
            is ValueFormalPosition.Receiver ->
                source.role is ValueRole.Receiver &&
                    source.role.call == operation.frame.invocation &&
                    source.role.kind == position.kind
        }

    private fun admitsReturn(
        frame: ValueCallFrame,
        source: ValueSite,
        target: ValueSite,
        kind: ValueTransferKind,
    ): Boolean =
        kind == ValueTransferKind.SOURCE_CALL_RETURN &&
            source.role == ValueRole.Return &&
            source.executionOwner == frame.callee &&
            target.isResultOf(frame.invocation) &&
            source.valuePath == target.valuePath

    private fun ValueSite.isResultOf(call: ValueInvocation): Boolean =
        enclosing.valueIdentity == call.enclosing.valueIdentity &&
            range == call.range &&
            basis == call.basis &&
            (role == ValueRole.ExpressionResult || (role as? ValueRole.LoopResult)?.result?.invocation == call) &&
            executionOwner == call.executionOwner

    private fun <T, F> Refinement<T, F>.matches(expected: T): Boolean = this is Refinement.Refined && value == expected

    override fun equals(other: Any?): Boolean = other is ValueSourceTransfer && operation == other.operation

    override fun hashCode(): Int = operation.hashCode()
}

private fun ValueSourceOperation.DeclaredCallbackResult.admitsDeclaredCallbackResult(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
): Boolean =
    kind == ValueTransferKind.SOURCE_CALL_RETURN &&
        binding.admitsReturn(source) &&
        target.executionOwner == ValueExecutionOwner.declaration(binding.enclosing) &&
        target.range == binding.contract.occurrence.range &&
        target.role == ValueRole.ExpressionResult &&
        source.valuePath == target.valuePath &&
        source.callStack == target.callStack &&
        target.conditions.facts.any {
            it is ValueCondition.DeclaredCallbackResult &&
                it.binding == binding &&
                it.origin.matches(source) &&
                it.useRange == target.range
        }
