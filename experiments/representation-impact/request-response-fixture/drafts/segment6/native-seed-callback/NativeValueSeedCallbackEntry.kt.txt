package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** The seed stays context-free. Compiler supplier proof establishes both frames on the exact callback target. */
internal fun nativeSeedCallbackEntry(
    source: ValueSite,
    frame: ValueCallFrame,
    formal: ValueFormalPosition,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    if (source.callStack != ValueCallStack.Empty) return rejectedSeedEntry()
    val callback = frame.target as? ValueCallbackTarget ?: return rejectedSeedEntry()
    val supplied =
        when (val value = source.callStack.enter(callback.supplyingFrame)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativeCallCause())
        }
    val stack =
        when (val value = supplied.enter(frame)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativeCallCause())
        }
    val target =
        when (val value = sourceFormalTarget(source, formal, frame, stack)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return sourceTransfer(
        source = source,
        target = target,
        kind = ValueTransferKind.SOURCE_CALL_ENTRY,
        operation = ValueSourceOperation.SeedCallbackEnter(frame, formal),
    )
}

private fun rejectedSeedEntry(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
