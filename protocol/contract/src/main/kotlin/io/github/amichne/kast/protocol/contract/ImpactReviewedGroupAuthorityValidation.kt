package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ImpactReviewedValueBindingDocument.admitsGroupRule(): Boolean =
    invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Unestablished &&
        selected.admitsGroupFunction(rule) &&
        artifacts.admitsRule(rule)

private fun ImpactReviewedFunctionDocument.admitsGroupFunction(rule: ImpactReviewedValueRuleDocument): Boolean {
    val transformed = rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1
    val parameters = if (transformed) listOf("(T)->K", "(T)->V") else listOf("(T)->K")
    val typeCount = if (transformed) GROUP_TRANSFORM_TYPE_PARAMETERS else GROUP_TYPE_PARAMETERS
    if (
        qualifiedIdentity.value != "kotlin.collections.groupBy" ||
            !receiver.hasType("kotlin/collections/Iterable<T>") ||
            !declaration.file.value.endsWith("!/kotlin/collections/CollectionsKt.class")
    )
        return false
    if (contextReceivers.values.isNotEmpty() || valueParameters.values.map { it.value } != parameters) return false
    if (typeParameterCount.value != typeCount.toLong() || formalIndex.value >= parameters.size.toLong()) return false
    val count =
        when (val parsed = CompilerTypeParameterCountDocument.parse(typeCount)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return false
        }
    return CompilerSymbolEvidenceDocument.restore(
        declaration.compilerIdentity,
        CompilerSignatureDocument.Function(
            qualifiedIdentity,
            receiver.compilerReceiverDocument(),
            contextReceivers,
            valueParameters,
            count,
        ),
    ) is Refinement.Refined
}

internal fun ImpactReviewedCollectionSectionsDocument.admitsGroupSections(
    rule: ImpactReviewedValueRuleDocument
): Boolean =
    when (this) {
        is ImpactReviewedCollectionSectionsDocument.GroupBy ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1 &&
                groupByLines.matches(GROUP_FIRST_LINE, GROUP_LAST_LINE) &&
                groupByToLines.matches(GROUP_TO_FIRST_LINE, GROUP_TO_LAST_LINE)
        is ImpactReviewedCollectionSectionsDocument.GroupByTransform ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1 &&
                groupByLines.matches(GROUP_TRANSFORM_FIRST_LINE, GROUP_TRANSFORM_LAST_LINE) &&
                groupByToLines.matches(GROUP_TRANSFORM_TO_FIRST_LINE, GROUP_TRANSFORM_TO_LAST_LINE)
        else -> false
    }

internal fun ImpactReviewedLibraryHelperDocument.admitsGroupLookup(): Boolean =
    sourceEntry.value == "commonMain/kotlin/collections/Maps.kt" &&
        sourceEntrySha256.value == "1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(GROUP_LOOKUP_FIRST_LINE to GROUP_LOOKUP_LAST_LINE) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/collections/MapsKt.class" to "520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4",
                "kotlin/collections/MapsKt__MapsKt.class" to
                    "bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389",
            )

private const val GROUP_TYPE_PARAMETERS = 2
private const val GROUP_TRANSFORM_TYPE_PARAMETERS = 3
private const val GROUP_FIRST_LINE = 1511
private const val GROUP_LAST_LINE = 1513
private const val GROUP_TO_FIRST_LINE = 1537
private const val GROUP_TO_LAST_LINE = 1544
private const val GROUP_TRANSFORM_FIRST_LINE = 1524
private const val GROUP_TRANSFORM_LAST_LINE = 1526
private const val GROUP_TRANSFORM_TO_FIRST_LINE = 1556
private const val GROUP_TRANSFORM_TO_LAST_LINE = 1563
private const val GROUP_LOOKUP_FIRST_LINE = 382
private const val GROUP_LOOKUP_LAST_LINE = 391
