package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType

internal fun BoundedProtocolList<ImpactValueConditionDocument>.hasSameConditions(
    other: BoundedProtocolList<ImpactValueConditionDocument>
): Boolean = values.hasSameConditions(other.values)

private fun List<ImpactValueConditionDocument>.hasSameConditions(other: List<ImpactValueConditionDocument>): Boolean =
    size == other.size && zip(other).all { (first, second) -> first.hasSameCondition(second) }

private fun ImpactValueConditionDocument.hasSameCondition(other: ImpactValueConditionDocument): Boolean =
    sourceSite().hasSameValueIdentity(other.sourceSite()) && useRange == other.useRange && hasSameRefinement(other)

private fun ImpactValueConditionDocument.hasSameRefinement(other: ImpactValueConditionDocument): Boolean =
    when (this) {
        is ImpactValueConditionDocument.DeclaredCallbackResult ->
            other is ImpactValueConditionDocument.DeclaredCallbackResult && binding == other.binding
        is ImpactValueConditionDocument.StableLocal ->
            other is ImpactValueConditionDocument.StableLocal &&
                originalType == other.originalType &&
                version == other.version &&
                refinedType == other.refinedType
        is ImpactValueConditionDocument.StableReceiver ->
            other is ImpactValueConditionDocument.StableReceiver &&
                originalType == other.originalType &&
                kind == other.kind &&
                refinedType == other.refinedType
        is ImpactValueConditionDocument.CastSuccess ->
            other is ImpactValueConditionDocument.CastSuccess &&
                originalType == other.originalType &&
                kind == other.kind &&
                targetType == other.targetType
    }

internal fun ImpactValueSiteReferenceDocument.admitsConditions(): Boolean =
    conditions.values.withIndex().all { (index, condition) ->
        condition.admits(enclosing.basis) && conditions.values.take(index).none { it.hasSameCondition(condition) }
    }

private fun ImpactValueConditionDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    origin.execution.enclosing.declarationFailure() is Refinement.Refined &&
        sourceSite().isOnBasis(basis) &&
        (this is ImpactValueConditionDocument.DeclaredCallbackResult ||
            origin.execution.executionOwner.admits(origin.execution.enclosing, useRange)) &&
        admitsRefinement()

private fun ImpactValueConditionDocument.admitsRefinement(): Boolean =
    when (this) {
        is ImpactValueConditionDocument.DeclaredCallbackResult -> admitsResult()
        is ImpactValueConditionDocument.StableLocal ->
            sourceSite().admitsLocalVersion() &&
                version.admitsLocalRead(useRange) &&
                originalType.isCanonicalType() &&
                refinedType.isCanonicalType()
        is ImpactValueConditionDocument.StableReceiver ->
            origin.role == ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(kind)) &&
                originalType.isCanonicalType() &&
                refinedType.isCanonicalType()
        is ImpactValueConditionDocument.CastSuccess ->
            (origin.role == ImpactValueRoleDocument.ExpressionResult ||
                origin.role == ImpactValueRoleDocument.LocalRead) &&
                useRange.containsExecutionRange(origin.range) &&
                originalType.isCanonicalType() &&
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
        added.sourceSite().hasSameValueIdentity(copy(conditions = EmptyImpactConditions)) &&
        added.useRange == target.range
}

private fun ImpactValueConditionDocument.matchesTransferKind(kind: ImpactTransferKindDocument): Boolean =
    when (this) {
        is ImpactValueConditionDocument.DeclaredCallbackResult -> kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN
        is ImpactValueConditionDocument.StableLocal -> kind == ImpactTransferKindDocument.LOCAL_READ
        is ImpactValueConditionDocument.StableReceiver -> kind == ImpactTransferKindDocument.FIELD_READ
        is ImpactValueConditionDocument.CastSuccess -> kind == ImpactTransferKindDocument.CAST_RESULT
    }

private fun ImpactValueConditionDocument.sourceSite(): ImpactValueSiteReferenceDocument =
    ImpactValueSiteReferenceDocument(
        origin.execution.enclosing,
        origin.range,
        origin.role,
        origin.execution.executionOwner,
        origin.valuePath,
        origin.callStack,
        localVersion =
            when (this) {
                is ImpactValueConditionDocument.StableLocal -> version
                is ImpactValueConditionDocument.DeclaredCallbackResult,
                is ImpactValueConditionDocument.StableReceiver,
                is ImpactValueConditionDocument.CastSuccess -> ImpactLocalVersionDocument.Unversioned
            },
    )
