package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Java int dividend / nonzero int literal yields int on normal completion. */
@ConsistentCopyVisibility
data class ValueJavaIntDivision
private constructor(
    val inputRange: ExactDeclarationTextRange,
    val divisorRange: ExactDeclarationTextRange,
    val resultRange: ExactDeclarationTextRange,
    val divisor: Int,
    val execution: ValueExecutionOwner,
    val callStack: ValueCallStack,
) : ValueTransferEvidence {
    internal fun admitsDivision(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        kind == ValueTransferKind.PRIMITIVE_DERIVATION &&
            source.role == ValueRole.LocalRead &&
            target.role == ValueRole.ExpressionResult &&
            source.range == inputRange &&
            target.range == resultRange &&
            source.executionOwner == execution &&
            target.executionOwner == execution &&
            source.callStack == callStack &&
            target.callStack == callStack &&
            source.valuePath.segments.isEmpty() &&
            target.valuePath.segments.isEmpty()

    internal val storageBytes: Long
        get() =
            callStack.frames.fold(2048L.addBytes(valueExecutionStorageBytes(execution))) { bytes, frame ->
                bytes.addBytes(valueFrameStorageBytes(frame))
            }

    companion object {
        fun fromJavaIntOperands(
            source: ValueSite,
            divisorRange: ExactDeclarationTextRange,
            resultRange: ExactDeclarationTextRange,
            divisor: Int,
        ): Refinement<ValueJavaIntDivision, ValueTransferFailure> {
            if (source.role != ValueRole.LocalRead || divisor == 0)
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            if (source.callStack.frames.lastOrNull()?.callee != source.executionOwner)
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            if (
                !resultRange.containsValueRange(source.range) ||
                    !resultRange.containsValueRange(divisorRange) ||
                    source.range.endExclusive >= divisorRange.startInclusive
            )
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            return Refinement.Refined(
                ValueJavaIntDivision(
                    inputRange = source.range,
                    divisorRange = divisorRange,
                    resultRange = resultRange,
                    divisor = divisor,
                    execution = source.executionOwner,
                    callStack = source.callStack,
                )
            )
        }
    }
}
