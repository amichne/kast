package io.github.amichne.kast.protocol.contract

internal fun ImpactLoopProtocolDocument.admits(): Boolean =
    enclosing.validValueDeclaration() &&
        listOf(iterator, hasNext, next).all {
            it.callable.validValueDeclaration() &&
                it.isOnBasis(enclosing.basis) &&
                it.executionOwner.admits(enclosing, it.range) &&
                it.range == iterator.range &&
                it.executionOwner.hasSameExecutionIdentity(iterator.executionOwner)
        } &&
        listOf(iterator.callable, hasNext.callable, next.callable).distinct().size == LOOP_OPERATION_COUNT

private const val LOOP_OPERATION_COUNT = 3

internal fun ImpactValueRoleDocument.LoopResult.admits(site: ImpactValueSiteReferenceDocument): Boolean =
    result.protocol.admits() &&
        result.protocol.enclosing == site.enclosing &&
        result.invocation.range == site.range &&
        result.invocation.executionOwner.hasSameExecutionIdentity(site.executionOwner)

internal fun ImpactTransferEvidenceDocument.LoopAdvance.admits(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    iteration.protocol.admits() &&
        kind == ImpactTransferKindDocument.RECEIVER &&
        source.admitsExecutionOwner() &&
        target.admitsExecutionOwner() &&
        source.role.hasSameRoleIdentity(
            ImpactValueRoleDocument.LoopResult(ImpactLoopResultDocument.Iterator(iteration.protocol))
        ) &&
        target.role.hasSameRoleIdentity(ImpactValueRoleDocument.Receiver(iteration.protocol.next, receiverKind)) &&
        target.range == iteration.protocol.next.range &&
        source.sameLoopState(target)

internal fun ImpactValueSiteReferenceDocument.admitsDirectLoop(
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean {
    if (target.role is ImpactValueRoleDocument.LoopResult) return false
    val loop = role as? ImpactValueRoleDocument.LoopResult ?: return true
    val receiver = target.role as? ImpactValueRoleDocument.Receiver ?: return false
    val component = receiver.invocation
    return loop.result is ImpactLoopResultDocument.Element &&
        kind == ImpactTransferKindDocument.RECEIVER &&
        loop.admits(this) &&
        target.admitsExecutionOwner() &&
        sameLoopState(target) &&
        admitsComponentAnchor(target, component) &&
        component.callable !in
            listOf(
                loop.result.protocol.iterator.callable,
                loop.result.protocol.hasNext.callable,
                loop.result.protocol.next.callable,
            )
}

private fun ImpactValueSiteReferenceDocument.admitsComponentAnchor(
    target: ImpactValueSiteReferenceDocument,
    component: ImpactInvocationReferenceDocument,
): Boolean =
    component.isOnBasis(enclosing.basis) &&
        range.containsExecutionRange(component.range) &&
        range != component.range &&
        target.range == component.range

private fun ImpactValueSiteReferenceDocument.sameLoopState(target: ImpactValueSiteReferenceDocument): Boolean =
    enclosing == target.enclosing &&
        executionOwner.hasSameExecutionIdentity(target.executionOwner) &&
        valuePath == target.valuePath &&
        callStack.hasSameCallStackIdentity(target.callStack) &&
        conditions.hasSameConditions(target.conditions)
