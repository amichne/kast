package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLoopIteration
import io.github.amichne.kast.relation.contract.ValueLoopResult
import io.github.amichne.kast.relation.contract.ValueLoopTransfer
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtForExpression

/** The payload remains in the iterator across its control guard; the Boolean never carries it. */
internal class NativeValueLoopInput(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    fun range(loop: KtForExpression) {
        val calls =
            when (val selected = calls(loop)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> {
                    retained.unresolvedAt(selected.failure, loop)
                    return
                }
            }
        retained.adopt(
            nativeImplicitReceiverArrival(
                source,
                ValueRole.Receiver(calls.protocol.iterator, ValueReceiverKind.DISPATCH),
            )
        )
    }

    fun result(loop: KtForExpression, expected: ValueLoopResult) {
        val calls =
            when (val selected = calls(loop)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> {
                    retained.unresolvedAt(selected.failure, loop)
                    return
                }
            }
        if (calls.protocol != expected.protocol) {
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, loop)
            return
        }
        when (expected) {
            is ValueLoopResult.Iterator -> advance(loop, calls)
            is ValueLoopResult.Element -> components(loop, calls, expected)
        }
    }

    private fun advance(loop: KtForExpression, calls: NativeForLoopCalls) {
        when (val guard = calls.guard(projection, permit)) {
            is Refinement.Rejected -> retained.unresolvedAt(guard.failure, loop)
            is Refinement.Refined ->
                when (val outcome = guard.value) {
                    NativeLoopGuard.Empty -> Unit
                    is NativeLoopGuard.Iteration -> retained.adopt(nativeLoopAdvance(source, outcome.proof))
                }
        }
    }

    private fun components(loop: KtForExpression, calls: NativeForLoopCalls, expected: ValueLoopResult.Element) {
        when (val result = calls.result(expected.invocation, scope, projection, permit)) {
            is Refinement.Rejected -> {
                retained.unresolvedAt(result.failure, loop)
                return
            }
            is Refinement.Refined ->
                if (result.value != expected) {
                    retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, loop)
                    return
                }
        }
        val declaration = loop.destructuringDeclaration
        if (declaration == null) {
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, loop)
            return
        }
        NativeValueDestructuring(source, scope, projection)
            .readIteration(
                declaration = declaration,
                iteration = expected.iteration,
                permit = permit,
                consume = { retained.emitTarget(it.target, it.kind, it.evidence) },
                unsupported = { cause, at -> retained.unresolvedAt(cause, at) },
            )
    }

    private fun calls(loop: KtForExpression): Refinement<NativeForLoopCalls, ValueFlowUnsupportedCause> =
        NativeForLoopCalls.read(loop, source.executionOwner, scope, projection, permit)
}

private fun nativeLoopAdvance(
    source: ValueSite,
    iteration: ValueLoopIteration,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val target =
        when (
            val value =
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = iteration.protocol.next.range,
                    role = ValueRole.Receiver(iteration.protocol.next, ValueReceiverKind.DISPATCH),
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                    conditions = source.conditions,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    return when (
        val transfer =
            ValueTransfer.fromCompiler(
                source,
                target,
                ValueTransferKind.RECEIVER,
                ValueLoopTransfer.Advance.fromCompiler(iteration, ValueReceiverKind.DISPATCH),
            )
    ) {
        is Refinement.Refined -> transfer
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
}
