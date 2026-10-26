package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class ValueLoopProtocolFailure {
    CALL_CONTEXT_MISMATCH,
    OPERATION_IDENTITY_MISMATCH,
}

/** Compiler-selected implicit calls share one authored loop anchor, without sharing result identity. */
@ConsistentCopyVisibility
data class ValueLoopProtocol
private constructor(
    val iterator: ValueInvocation,
    val hasNext: ValueInvocation,
    val next: ValueInvocation,
) {
    val iteratorResult: ValueLoopResult.Iterator
        get() = ValueLoopResult.Iterator(this)

    companion object {
        /** The native boundary supplies KaForLoopCall selections, not a name-based operator lookup. */
        fun fromCompiler(
            iterator: ValueInvocation,
            hasNext: ValueInvocation,
            next: ValueInvocation,
        ): Refinement<ValueLoopProtocol, ValueLoopProtocolFailure> {
            if (listOf(hasNext, next).any { !iterator.sameLoop(it) })
                return Refinement.Rejected(ValueLoopProtocolFailure.CALL_CONTEXT_MISMATCH)
            if (
                iterator.callable.name.value != "iterator" ||
                    hasNext.callable.name.value != "hasNext" ||
                    next.callable.name.value != "next"
            )
                return Refinement.Rejected(ValueLoopProtocolFailure.OPERATION_IDENTITY_MISMATCH)
            if (
                listOf(iterator, hasNext, next).any {
                    it.operation !is ValueInvocationOperation.Function || it.operation.valueParameters.isNotEmpty()
                }
            )
                return Refinement.Rejected(ValueLoopProtocolFailure.OPERATION_IDENTITY_MISMATCH)
            return Refinement.Refined(ValueLoopProtocol(iterator, hasNext, next))
        }

        private fun ValueInvocation.sameLoop(other: ValueInvocation): Boolean =
            basis == other.basis &&
                enclosing.valueIdentity == other.enclosing.valueIdentity &&
                executionOwner == other.executionOwner &&
                range == other.range
    }
}

/** Source-backed normal-true completion of the selected hasNext; this proves neither repetition nor termination. */
@ConsistentCopyVisibility
data class ValueLoopIteration private constructor(val protocol: ValueLoopProtocol) {
    val elementResult: ValueLoopResult.Element
        get() = ValueLoopResult.Element(this)

    companion object {
        /** The native owner must establish normal true completion before detaching this capability. */
        fun fromCompilerNormalTrue(protocol: ValueLoopProtocol): ValueLoopIteration = ValueLoopIteration(protocol)
    }
}

/** Synthetic iterator and item values are not results of the authored Kotlin loop expression. */
sealed interface ValueLoopResult {
    val protocol: ValueLoopProtocol
    val invocation: ValueInvocation

    @ConsistentCopyVisibility
    data class Iterator internal constructor(override val protocol: ValueLoopProtocol) : ValueLoopResult {
        override val invocation: ValueInvocation
            get() = protocol.iterator
    }

    @ConsistentCopyVisibility
    data class Element internal constructor(val iteration: ValueLoopIteration) : ValueLoopResult {
        override val protocol: ValueLoopProtocol
            get() = iteration.protocol

        override val invocation: ValueInvocation
            get() = protocol.next
    }
}
