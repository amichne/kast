package io.github.amichne.kast.protocol.contract

/** Formal and invocation roles can themselves retain a callback execution with observed dispatch counters. */
internal fun ImpactValueRoleDocument.hasSameRoleIdentity(other: ImpactValueRoleDocument): Boolean =
    when (this) {
        is ImpactValueRoleDocument.Argument ->
            other is ImpactValueRoleDocument.Argument && hasSameArgumentIdentity(other)
        is ImpactValueRoleDocument.Receiver ->
            other is ImpactValueRoleDocument.Receiver && hasSameReceiverIdentity(other)
        is ImpactValueRoleDocument.Formal ->
            other is ImpactValueRoleDocument.Formal && position.hasSameFormalIdentity(other.position)
        is ImpactValueRoleDocument.LoopResult ->
            other is ImpactValueRoleDocument.LoopResult && result.hasSameLoopIdentity(other.result)
        ImpactValueRoleDocument.ExpressionResult,
        ImpactValueRoleDocument.LocalBinding,
        ImpactValueRoleDocument.LocalRead,
        ImpactValueRoleDocument.Return,
        ImpactValueRoleDocument.PropertyAssignment -> this == other
    }

private fun ImpactValueRoleDocument.Argument.hasSameArgumentIdentity(other: ImpactValueRoleDocument.Argument): Boolean =
    index == other.index && invocation.hasSameInvocationIdentity(other.invocation)

private fun ImpactValueRoleDocument.Receiver.hasSameReceiverIdentity(other: ImpactValueRoleDocument.Receiver): Boolean =
    kind == other.kind && invocation.hasSameInvocationIdentity(other.invocation)

private fun ImpactFormalPositionDocument.hasSameFormalIdentity(other: ImpactFormalPositionDocument): Boolean =
    when (this) {
        is ImpactFormalPositionDocument.Receiver -> other == this
        is ImpactFormalPositionDocument.Parameter ->
            other is ImpactFormalPositionDocument.Parameter && parameter.hasSameParameterIdentity(other.parameter)
    }

private fun ImpactFormalParameterDocument.hasSameParameterIdentity(other: ImpactFormalParameterDocument): Boolean =
    callable == other.callable &&
        index == other.index &&
        name == other.name &&
        range == other.range &&
        executionOwner.hasSameExecutionIdentity(other.executionOwner)

private fun ImpactLoopResultDocument.hasSameLoopIdentity(other: ImpactLoopResultDocument): Boolean =
    when (this) {
        is ImpactLoopResultDocument.Iterator ->
            other is ImpactLoopResultDocument.Iterator && protocol.hasSameLoopIdentity(other.protocol)
        is ImpactLoopResultDocument.Element ->
            other is ImpactLoopResultDocument.Element &&
                iteration.condition == other.iteration.condition &&
                iteration.protocol.hasSameLoopIdentity(other.iteration.protocol)
    }

private fun ImpactLoopProtocolDocument.hasSameLoopIdentity(other: ImpactLoopProtocolDocument): Boolean =
    enclosing == other.enclosing &&
        iterator.hasSameInvocationIdentity(other.iterator) &&
        hasNext.hasSameInvocationIdentity(other.hasNext) &&
        next.hasSameInvocationIdentity(other.next)
