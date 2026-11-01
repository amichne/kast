package io.github.amichne.kast.protocol.contract

internal fun ImpactTransferEvidenceDocument.StringContent.admitsContent(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.STRING_CONTENT &&
        admitsRanges(source, target) &&
        admitsOwners(source, target) &&
        admitsInput(source, target)

private fun ImpactTransferEvidenceDocument.StringContent.admitsRanges(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
): Boolean =
    templateRange.containsExecutionRange(entryRange) &&
        entryRange.containsExecutionRange(inputRange) &&
        templateRange != entryRange &&
        entryRange != inputRange &&
        inputRange.containsExecutionRange(source.range) &&
        target.range == templateRange

private fun admitsOwners(source: ImpactValueSiteReferenceDocument, target: ImpactValueSiteReferenceDocument): Boolean =
    target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.enclosing == target.enclosing &&
        source.executionOwner.hasSameExecutionIdentity(target.executionOwner) &&
        source.valuePath.values.isEmpty() &&
        target.valuePath.values.isEmpty() &&
        source.callStack.hasSameCallStackIdentity(target.callStack)

private fun ImpactTransferEvidenceDocument.StringContent.admitsInput(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
): Boolean =
    when (input) {
        ImpactStringContentInputDocument.STRING ->
            nullAlternative == ImpactStringNullAlternativeDocument.NOT_NULLABLE &&
                source.conditions.hasSameConditions(target.conditions)
        ImpactStringContentInputDocument.NULLABLE_STRING ->
            nullAlternative == ImpactStringNullAlternativeDocument.CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT &&
                target.conditions.values.lastOrNull().let { condition ->
                    condition is ImpactValueConditionDocument.StringContentNonNull &&
                        condition.entryRange == entryRange &&
                        condition.useRange == templateRange
                }
    }
