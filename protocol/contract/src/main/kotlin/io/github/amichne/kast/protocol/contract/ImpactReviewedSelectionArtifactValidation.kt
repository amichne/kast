package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedCollectionSectionsDocument.admitsSelectionSections(
    rule: ImpactReviewedValueRuleDocument
): Boolean =
    when (this) {
        is ImpactReviewedCollectionSectionsDocument.Filter ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1 &&
                filterLines.matches(FILTER_FIRST, FILTER_LAST) &&
                filterToLines.matches(FILTER_TO_FIRST, FILTER_TO_LAST)
        is ImpactReviewedCollectionSectionsDocument.MapNotNull ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 &&
                mapNotNullLines.matches(MAP_NOT_NULL_FIRST, MAP_NOT_NULL_LAST) &&
                mapNotNullToLines.matches(MAP_NOT_NULL_TO_FIRST, MAP_NOT_NULL_TO_LAST) &&
                forEachLines.matches(FOR_EACH_FIRST, FOR_EACH_LAST)
        else -> false
    }

internal fun ImpactReviewedLibraryHelperDocument.admitsNonNullLet(): Boolean =
    sourceEntry.value == "commonMain/kotlin/util/Standard.kt" &&
        sourceEntrySha256.value == "86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0" &&
        sourceSections.values.map { it.first.value to it.last.value } == listOf(LET_FIRST to LET_LAST) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/StandardKt.class" to "f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05",
                "kotlin/StandardKt__StandardKt.class" to
                    "bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0",
            )

private const val FILTER_FIRST = 776
private const val FILTER_LAST = 778
private const val FILTER_TO_FIRST = 872
private const val FILTER_TO_LAST = 875
private const val MAP_NOT_NULL_FIRST = 1641
private const val MAP_NOT_NULL_LAST = 1643
private const val MAP_NOT_NULL_TO_FIRST = 1650
private const val MAP_NOT_NULL_TO_LAST = 1653
private const val FOR_EACH_FIRST = 1914
private const val FOR_EACH_LAST = 1916
private const val LET_FIRST = 114
private const val LET_LAST = 119
