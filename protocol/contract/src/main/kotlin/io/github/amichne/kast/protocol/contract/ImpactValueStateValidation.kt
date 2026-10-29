package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

private const val MAX_VALUE_ACCESS_PATH_DEPTH = 8
internal const val MAX_VALUE_CALL_FRAMES = 32

internal fun ImpactValueSiteReferenceDocument.admitsValueState(): Boolean =
    valuePath.values.size <= MAX_VALUE_ACCESS_PATH_DEPTH &&
        callStack.values.size <= MAX_VALUE_CALL_FRAMES &&
        valuePath.values.all { it.isOnBasis(enclosing.basis) } &&
        callStack.values.withIndex().all { (index, frame) ->
            callStack.values.take(index).none { it.hasSameFrameIdentity(frame) }
        } &&
        callStack.values.all { it.admits(enclosing.basis) } &&
        admitsConditions() &&
        admitsLocalVersion()

internal fun ImpactValuePathSegmentDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        ImpactValuePathSegmentDocument.Element -> true
        is ImpactValuePathSegmentDocument.PairComponent -> origin.admits(basis)
        is ImpactValuePathSegmentDocument.MapEntryComponent -> entry.admits(basis)
        is ImpactValuePathSegmentDocument.Field -> declaration.basis == basis && declaration.validValueDeclaration()
    }

internal fun ImpactCallFrameDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    enclosing.basis == basis &&
        enclosing.validValueDeclaration() &&
        invocation.isOnBasis(basis) &&
        invocation.executionOwner.admits(enclosing, invocation.range) &&
        callee.admits(basis) &&
        admitsTarget(basis)

private fun ImpactExecutionReferenceDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    enclosing.basis == basis && enclosing.validValueDeclaration() && executionOwner.admits(enclosing, enclosing.range)

internal fun ImpactFormalParameterDocument.admits(): Boolean =
    callable.validValueDeclaration() &&
        name.value.isNotBlank() &&
        range.start.value < range.end.value &&
        executionOwner.admits(callable, range)

internal fun ImpactFormalPositionDocument.admits(site: ImpactValueSiteReferenceDocument): Boolean =
    when (this) {
        is ImpactFormalPositionDocument.Parameter ->
            parameter.admits() &&
                parameter.callable == site.enclosing &&
                parameter.range == site.range &&
                parameter.executionOwner.hasSameExecutionIdentity(site.executionOwner)
        is ImpactFormalPositionDocument.Receiver -> true
    }

internal fun ImpactDeclarationReferenceDocument.validValueDeclaration(): Boolean =
    declarationFailure() is Refinement.Refined

internal fun ImpactSourceOperationDocument.admits(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean {
    if (!source.isOnBasis(source.enclosing.basis) || !target.isOnBasis(source.enclosing.basis)) return false
    return when (this) {
        is ImpactSourceOperationDocument.Enter -> admitsEntry(source, target, kind)
        is ImpactSourceOperationDocument.Return -> admitsReturn(source, target, kind)
        is ImpactSourceOperationDocument.SeedReturn -> admitsSeedReturn(source, target, kind)
        is ImpactSourceOperationDocument.DeclaredCallbackResult -> admitsDeclaredResult(source, target, kind)
        is ImpactSourceOperationDocument.CallbackReturn -> admitsCallbackReturn(source, target, kind)
        is ImpactSourceOperationDocument.CallbackArgumentEntry -> admitsCallbackArgument(source, target, kind)
        is ImpactSourceOperationDocument.ReadField -> admitsFieldRead(source, target, kind)
        is ImpactSourceOperationDocument.ConstructField -> admitsConstruction(source, target, kind)
    }
}

private fun ImpactSourceOperationDocument.Enter.admitsEntry(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
        frame.admits(source.enclosing.basis) &&
        target.isIn(frame.callee) &&
        target.role.hasSameRoleIdentity(ImpactValueRoleDocument.Formal(position)) &&
        position.admits(target) &&
        source.actualMatches(this) &&
        source.valuePath == target.valuePath &&
        source.callStack.values.size < MAX_VALUE_CALL_FRAMES &&
        source.callStack.values.none { it.hasSameFrameIdentity(frame) } &&
        (source.callStack.values + frame).hasSameFrames(target.callStack.values)

private fun ImpactSourceOperationDocument.Return.admitsReturn(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
        frame.admits(source.enclosing.basis) &&
        source.role == ImpactValueRoleDocument.Return &&
        source.isIn(frame.callee) &&
        target.isResultOf(frame) &&
        source.valuePath == target.valuePath &&
        source.callStack.values.lastOrNull()?.hasSameFrameIdentity(frame) == true &&
        source.callStack.values.dropLast(1).hasSameFrames(target.callStack.values)

private fun ImpactSourceOperationDocument.SeedReturn.admitsSeedReturn(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
        frame.admits(source.enclosing.basis) &&
        source.role == ImpactValueRoleDocument.Return &&
        source.isIn(frame.callee) &&
        target.isResultOf(frame) &&
        source.valuePath == target.valuePath &&
        source.callStack.values.isEmpty() &&
        target.callStack.values.isEmpty()

private fun ImpactSourceOperationDocument.ReadField.admitsFieldRead(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.FIELD_READ &&
        field.basis == source.enclosing.basis &&
        field.validValueDeclaration() &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.enclosing == target.enclosing &&
        source.executionOwner.hasSameExecutionIdentity(target.executionOwner) &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        source.valuePath.selects(ImpactValuePathSegmentDocument.Field(field), target.valuePath)

private fun ImpactSourceOperationDocument.ConstructField.admitsConstruction(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
        storage.admits(source.enclosing.basis) &&
        invocation.callable == storage.constructor &&
        source.actualConstructs(invocation, storage.parameter) &&
        source.enclosing == target.enclosing &&
        target.range == invocation.range &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.executionOwner.hasSameExecutionIdentity(invocation.executionOwner) &&
        target.executionOwner.hasSameExecutionIdentity(invocation.executionOwner) &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        listOf(ImpactValuePathSegmentDocument.Field(storage.field)) + source.valuePath.values == target.valuePath.values

private fun ImpactConstructorStorageDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    constructor.basis == basis &&
        constructor.validValueDeclaration() &&
        parameter.admits() &&
        parameter.callable == constructor &&
        field.basis == basis &&
        field.validValueDeclaration() &&
        field.range == parameter.range &&
        field.file == constructor.file

private fun ImpactValueSiteReferenceDocument.actualConstructs(
    invocation: ImpactInvocationReferenceDocument,
    parameter: ImpactFormalParameterDocument,
): Boolean =
    (role as? ImpactValueRoleDocument.Argument)?.let {
        it.invocation.hasSameInvocationIdentity(invocation) && it.index == parameter.index
    } == true

private fun ImpactValueSiteReferenceDocument.actualMatches(operation: ImpactSourceOperationDocument.Enter): Boolean {
    val frame = operation.frame
    if (enclosing != frame.enclosing || !executionOwner.hasSameExecutionIdentity(frame.invocation.executionOwner))
        return false
    return when (val position = operation.position) {
        is ImpactFormalPositionDocument.Parameter ->
            position.parameter.callable == frame.callee.enclosing &&
                position.parameter.executionOwner.hasSameExecutionIdentity(frame.callee.executionOwner) &&
                matchesParameter(frame, position.parameter)
        is ImpactFormalPositionDocument.Receiver -> matchesReceiver(frame, position.kind)
    }
}

private fun ImpactValueSiteReferenceDocument.matchesParameter(
    frame: ImpactCallFrameDocument,
    parameter: ImpactFormalParameterDocument,
): Boolean =
    when (val actual = role) {
        is ImpactValueRoleDocument.Argument ->
            actual.invocation.hasSameInvocationIdentity(frame.invocation) && actual.index == parameter.index
        else -> false
    }

private fun ImpactValueSiteReferenceDocument.matchesReceiver(
    frame: ImpactCallFrameDocument,
    kind: ImpactReceiverKindDocument,
): Boolean =
    when (val actual = role) {
        is ImpactValueRoleDocument.Receiver ->
            actual.kind == kind && actual.invocation.hasSameInvocationIdentity(frame.invocation)
        else -> false
    }

internal fun ImpactValueSiteReferenceDocument.isIn(owner: ImpactExecutionReferenceDocument): Boolean =
    enclosing == owner.enclosing && executionOwner.hasSameExecutionIdentity(owner.executionOwner)

internal fun ImpactValueSiteReferenceDocument.isResultOf(frame: ImpactCallFrameDocument): Boolean =
    enclosing == frame.enclosing &&
        range == frame.invocation.range &&
        (role == ImpactValueRoleDocument.ExpressionResult ||
            (role as? ImpactValueRoleDocument.StringConversionResult)
                ?.invocation
                ?.hasSameInvocationIdentity(frame.invocation) == true ||
            (role as? ImpactValueRoleDocument.LoopResult)?.let {
                it.admits(this) && it.result.invocation.hasSameInvocationIdentity(frame.invocation)
            } == true) &&
        executionOwner.hasSameExecutionIdentity(frame.invocation.executionOwner)

internal fun BoundedProtocolList<ImpactValuePathSegmentDocument>.selects(
    selected: ImpactValuePathSegmentDocument,
    target: BoundedProtocolList<ImpactValuePathSegmentDocument>,
): Boolean =
    when {
        values.isEmpty() -> target.values.isEmpty()
        values.first() == selected -> values.drop(1) == target.values
        else -> false
    }
