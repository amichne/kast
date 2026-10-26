package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType

internal fun BoundedProtocolList<ImpactValueConditionDocument>.hasSameConditions(
    other: BoundedProtocolList<ImpactValueConditionDocument>
): Boolean = values.hasSameConditions(other.values)

private fun List<ImpactValueConditionDocument>.hasSameConditions(other: List<ImpactValueConditionDocument>): Boolean =
    size == other.size && zip(other).all { (first, second) -> first.hasSameCondition(second) }

private fun ImpactValueConditionDocument.hasSameCondition(other: ImpactValueConditionDocument): Boolean =
    origin.site().hasSameValueIdentity(other.origin.site()) &&
        useRange == other.useRange &&
        originalType == other.originalType &&
        hasSameRefinement(other)

private fun ImpactValueConditionDocument.hasSameRefinement(other: ImpactValueConditionDocument): Boolean =
    when (this) {
        is ImpactValueConditionDocument.StableReceiver ->
            other is ImpactValueConditionDocument.StableReceiver &&
                kind == other.kind &&
                refinedType == other.refinedType
        is ImpactValueConditionDocument.CastSuccess ->
            other is ImpactValueConditionDocument.CastSuccess && kind == other.kind && targetType == other.targetType
    }

internal fun ImpactValueSiteReferenceDocument.admitsConditions(): Boolean =
    conditions.values.withIndex().all { (index, condition) ->
        condition.admits(enclosing.basis) && conditions.values.take(index).none { it.hasSameCondition(condition) }
    }

private fun ImpactValueConditionDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    origin.execution.enclosing.declarationFailure() is Refinement.Refined &&
        origin.site().isOnBasis(basis) &&
        origin.execution.executionOwner.admits(origin.execution.enclosing, useRange) &&
        originalType.isCanonicalType() &&
        admitsRefinement()

private fun ImpactValueConditionDocument.admitsRefinement(): Boolean =
    when (this) {
        is ImpactValueConditionDocument.StableReceiver ->
            origin.role == ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(kind)) &&
                refinedType.isCanonicalType()
        is ImpactValueConditionDocument.CastSuccess ->
            (origin.role == ImpactValueRoleDocument.ExpressionResult ||
                origin.role == ImpactValueRoleDocument.LocalRead) &&
                useRange.containsExecutionRange(origin.range) &&
                targetType.isCanonicalType()
    }

private fun ProtocolText.isCanonicalType(): Boolean =
    when (val admitted = CanonicalCompilerType.fromCompiler(value)) {
        is Refinement.Refined -> admitted.value.value == value
        is Refinement.Rejected -> false
    }

internal fun ImpactValueSiteReferenceDocument.conservesConditions(
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean {
    if (!admitsConditions() || !target.admitsConditions()) return false
    if (conditions.hasSameConditions(target.conditions)) return kind != ImpactTransferKindDocument.CAST_RESULT
    if (target.conditions.values.size != conditions.values.size + 1) return false
    if (!conditions.values.hasSameConditions(target.conditions.values.dropLast(1))) return false
    val added = target.conditions.values.last()
    return added.matchesTransferKind(kind) &&
        added.origin.site().hasSameValueIdentity(copy(conditions = EmptyImpactConditions)) &&
        added.useRange == target.range
}

private fun ImpactValueConditionDocument.matchesTransferKind(kind: ImpactTransferKindDocument): Boolean =
    when (this) {
        is ImpactValueConditionDocument.StableReceiver -> kind == ImpactTransferKindDocument.FIELD_READ
        is ImpactValueConditionDocument.CastSuccess -> kind == ImpactTransferKindDocument.CAST_RESULT
    }

private fun ImpactValueConditionOriginDocument.site(): ImpactValueSiteReferenceDocument =
    ImpactValueSiteReferenceDocument(
        execution.enclosing,
        range,
        role,
        execution.executionOwner,
        valuePath,
        callStack,
    )
