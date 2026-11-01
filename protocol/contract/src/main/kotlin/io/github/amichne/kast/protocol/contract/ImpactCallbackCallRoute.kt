package io.github.amichne.kast.protocol.contract

/** A retained supplier and its terminal callback invoker occupy one exact active suffix. */
internal data class ImpactCallbackCallRoute(
    val supplierFrame: ImpactCallFrameDocument,
    val invocationFrame: ImpactCallFrameDocument,
)

internal fun ImpactCaptureInvocationDocument.activeCallRoute(
    stack: BoundedProtocolList<ImpactCallFrameDocument>
): ImpactCallbackCallRoute? {
    if (!admitsForwardings(enclosing.basis)) return null
    val expected = listOf(supplyFrame()) + forwardings.values.map { it.targetFrame }
    if (expected.size > stack.values.size) return null
    val actual = stack.values.takeLast(expected.size)
    if (!matchesSupplyFrame(actual.first())) return null
    if (!actual.drop(1).hasSameFrames(expected.drop(1))) return null
    if (
        actual.zipWithNext().any { (caller, callee) ->
            !caller.callee.hasSameExecutionIdentity(
                ImpactExecutionReferenceDocument(callee.enclosing, callee.invocation.executionOwner)
            )
        }
    )
        return null
    if (!invocationOwner.matchesCallbackDeclaration(actual.last().callee.enclosing)) return null
    return ImpactCallbackCallRoute(actual.first(), actual.last())
}

internal fun ImpactCaptureInvocationDocument.matchesSupplyFrame(frame: ImpactCallFrameDocument): Boolean {
    val invocation = (frame.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation ?: return false
    return supplyFrame(invocation.executionOwner).hasSameFrameIdentity(frame)
}

internal fun ImpactCaptureInvocationDocument.supplyFrame(
    execution: ImpactExecutionOwnerDocument = supply.executionOwner
): ImpactCallFrameDocument {
    val callable =
        when (val target = supplyTarget) {
            ImpactCallTargetDocument.Direct -> supply.callable
            is ImpactCallTargetDocument.Implementation -> target.relation.source.declaration
        }
    return ImpactCallFrameDocument(
        enclosing = enclosing,
        invocation = supply.copy(executionOwner = execution),
        callee = ImpactExecutionReferenceDocument(callable),
        target = supplyTarget,
    )
}

internal fun ImpactCaptureInvocationDocument.admitsForwardings(basis: ImpactSemanticBasisDocument): Boolean {
    if (forwardings.values.size >= MAX_VALUE_CALL_FRAMES) return false
    var frame = supplyFrame()
    if (!frame.admits(basis) || !frame.callee.enclosing.range.containsExecutionRange(parameter)) return false
    var formalRange = parameter
    var formalPosition = position
    for (forwarding in forwardings.values) {
        if (forwarding.sourceParameter != formalRange || forwarding.sourcePosition != formalPosition) return false
        if (!forwarding.admitsFrom(frame.callee, basis)) return false
        frame = forwarding.targetFrame
        formalRange = forwarding.parameter
        formalPosition = forwarding.position
    }
    return invocationOwner.file == frame.callee.enclosing.file &&
        frame.callee.enclosing.range.containsExecutionRange(invocationOwner.range) &&
        invocationOwner.range.containsExecutionRange(invocation)
}

private fun ImpactCallbackForwardingDocument.admitsFrom(
    caller: ImpactExecutionReferenceDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    caller.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        targetFrame.enclosing == caller.enclosing &&
        targetFrame.invocation.executionOwner.hasSameExecutionIdentity(caller.executionOwner) &&
        targetFrame.admits(basis) &&
        targetFrame.callee.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        targetFrame.invocation.range.containsExecutionRange(argument) &&
        targetFrame.callee.enclosing.range.containsExecutionRange(parameter) &&
        callableTransfers.values.admitsCallableForwarding(caller.enclosing, argument)

private fun List<ImpactCompilerTransferDocument>.admitsCallableForwarding(
    owner: ImpactDeclarationReferenceDocument,
    argument: ImpactSourceRangeDocument,
): Boolean {
    if (isEmpty()) return true
    if (any { !it.admitsCallableForwarding(owner) }) return false
    if (zipWithNext().any { (left, right) -> !left.target.hasSameValueIdentity(right.source) }) return false
    return first().source.role == ImpactValueRoleDocument.ExpressionResult &&
        last().target.role == ImpactValueRoleDocument.LocalRead &&
        argument.containsExecutionRange(last().target.range)
}

private fun ImpactCompilerTransferDocument.admitsCallableForwarding(
    owner: ImpactDeclarationReferenceDocument
): Boolean =
    kind in setOf(ImpactTransferKindDocument.LOCAL_BINDING, ImpactTransferKindDocument.LOCAL_READ) &&
        source.enclosing == owner &&
        target.enclosing == owner &&
        source.admitsExecutionOwner() &&
        target.admitsExecutionOwner() &&
        admitsEvidence()

internal fun ImpactCapturedBodyDocument.matchesCallbackDeclaration(
    declaration: ImpactDeclarationReferenceDocument
): Boolean = file == declaration.file && range == declaration.range && compilerIdentity == declaration.compilerIdentity
