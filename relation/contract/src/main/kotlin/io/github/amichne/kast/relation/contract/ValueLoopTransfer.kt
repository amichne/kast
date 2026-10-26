package io.github.amichne.kast.relation.contract

private const val BYTES_PER_TEXT_UNIT = 2L
private const val PROTOCOL_STORAGE_BYTES = 4096L

/** Implicit loop receivers retain the exact normal-true guard and selected invocation, not runtime iteration counts. */
sealed interface ValueLoopTransfer : ValueTransferEvidence {
    val iteration: ValueLoopIteration
    val receiverKind: ValueReceiverKind

    @ConsistentCopyVisibility
    data class Advance
    private constructor(
        override val iteration: ValueLoopIteration,
        override val receiverKind: ValueReceiverKind,
    ) : ValueLoopTransfer {
        companion object {
            /** Native loop resolution establishes the receiver slot of the selected next call. */
            fun fromCompiler(iteration: ValueLoopIteration, receiverKind: ValueReceiverKind): Advance =
                Advance(iteration, receiverKind)
        }
    }

    fun admitsLoop(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        kind == ValueTransferKind.RECEIVER &&
            source.executionOwner == target.executionOwner &&
            source.valuePath == target.valuePath &&
            source.callStack == target.callStack &&
            source.conditions == target.conditions &&
            when (this) {
                is Advance ->
                    source.role == ValueRole.LoopResult(iteration.protocol.iteratorResult) &&
                        target.role == ValueRole.Receiver(iteration.protocol.next, receiverKind) &&
                        target.range == iteration.protocol.next.range
            }
}

internal fun ValueSite.admitsDirectLoop(target: ValueSite, kind: ValueTransferKind): Boolean {
    if (target.role is ValueRole.LoopResult) return false
    val loop = role as? ValueRole.LoopResult ?: return true
    val receiver = target.role as? ValueRole.Receiver ?: return false
    return loop.result is ValueLoopResult.Element &&
        kind == ValueTransferKind.RECEIVER &&
        loop.result.protocol.admitsComponent(receiver.call) &&
        target.range == receiver.call.range &&
        conditions == target.conditions
}

internal fun ValueLoopProtocol.admitsComponent(component: ValueInvocation): Boolean =
    component.basis == next.basis &&
        component.executionOwner == next.executionOwner &&
        component.enclosing.valueIdentity == next.enclosing.valueIdentity &&
        next.range.containsValueRange(component.range) &&
        component.range != next.range &&
        component.callable.name.value.matches(Regex("component[1-9][0-9]*")) &&
        component.operation is ValueInvocationOperation.Function &&
        component.operation.valueParameters.isEmpty()

internal fun ValueLoopProtocol.invocations(): List<ValueInvocation> = listOf(iterator, hasNext, next)

internal fun ValueLoopProtocol.retainedBytes(): Long =
    invocations().fold(PROTOCOL_STORAGE_BYTES) { bytes, invocation ->
        bytes
            .addBytes(valueSiteStorageBytes(invocation.resultSite()))
            .addBytes(invocation.callable.detachedTextUnits().multiplyBytes(BYTES_PER_TEXT_UNIT))
    }

internal fun ValueLoopTransfer.storageBytes(): Long = iteration.protocol.retainedBytes()
