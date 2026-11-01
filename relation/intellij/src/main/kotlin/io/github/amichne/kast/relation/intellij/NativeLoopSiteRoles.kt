package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLoopResultPhase
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import org.jetbrains.kotlin.psi.KtForExpression

/** Restoration reacquires the selected protocol and guard; a structural claim cannot supply either proof. */
internal fun nativeLoopSiteRole(
    loop: KtForExpression,
    claim: ValueSiteRoleClaim,
    execution: ValueExecutionOwner,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val calls =
        when (val read = NativeForLoopCalls.read(loop, execution, scope, projection, permit)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val invocation =
        when (claim) {
            is ValueSiteRoleClaim.LoopResult ->
                when (claim.phase) {
                    ValueLoopResultPhase.ITERATOR -> calls.protocol.iterator
                    ValueLoopResultPhase.ELEMENT -> calls.protocol.next
                }
            is ValueSiteRoleClaim.Receiver ->
                listOf(calls.protocol.iterator, calls.protocol.next).singleOrNull {
                    it.callable.compilerIdentity == claim.expectedCallable.compilerIdentity &&
                        it.callable.file == claim.expectedCallable.file &&
                        it.callable.range == claim.expectedCallable.range
                } ?: return loopRoleRejected()
            else -> return loopRoleRejected()
        }
    return when (val result = calls.result(invocation, scope, projection, permit)) {
        is Refinement.Rejected -> result
        is Refinement.Refined ->
            when (claim) {
                is ValueSiteRoleClaim.LoopResult -> Refinement.Refined(ValueRole.LoopResult(result.value))
                is ValueSiteRoleClaim.Receiver ->
                    Refinement.Refined(ValueRole.Receiver(invocation, ValueReceiverKind.DISPATCH))
                else -> loopRoleRejected()
            }
    }
}

private fun loopRoleRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
