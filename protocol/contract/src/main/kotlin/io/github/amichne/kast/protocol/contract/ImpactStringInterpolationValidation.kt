package io.github.amichne.kast.protocol.contract

/** Range admission does not prove K2 scalar type or immediate-entry semantics. */
internal fun ImpactTransferEvidenceDocument.StringInterpolation.admitsInterpolation(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.DERIVATION &&
        (source.role == ImpactValueRoleDocument.LocalRead || source.role == ImpactValueRoleDocument.ExpressionResult) &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.sameInterpolationContext(target) &&
        source.valuePath.values.isEmpty() &&
        target.valuePath.values.isEmpty() &&
        target.range == templateRange &&
        admitsEntryRange(source.range)

private fun ImpactValueSiteReferenceDocument.sameInterpolationContext(
    target: ImpactValueSiteReferenceDocument
): Boolean =
    enclosing == target.enclosing &&
        executionOwner == target.executionOwner &&
        callStack.hasSameCallStackIdentity(target.callStack)

private fun ImpactTransferEvidenceDocument.StringInterpolation.admitsEntryRange(
    source: ImpactSourceRangeDocument
): Boolean =
    templateRange.start.value < entryRange.start.value &&
        entryRange.start.value < entryRange.end.value &&
        entryRange.end.value < templateRange.end.value &&
        source.start.value >= entryRange.start.value &&
        source.start.value < source.end.value &&
        source.end.value <= entryRange.end.value
