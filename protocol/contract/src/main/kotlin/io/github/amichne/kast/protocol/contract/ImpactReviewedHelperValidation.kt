package io.github.amichne.kast.protocol.contract

internal fun BoundedProtocolList<ImpactReviewedLibraryHelperDocument>.admitsHelpers(
    rule: ImpactReviewedValueRuleDocument
): Boolean {
    val expected =
        when (rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1 ->
                listOf(ImpactReviewedLibraryHelperKindDocument.GROUP_LOOKUP)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 ->
                listOf(ImpactReviewedLibraryHelperKindDocument.NON_NULL_LET)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_VALUES_V1 ->
                listOf(ImpactReviewedLibraryHelperKindDocument.MAP_CAPACITY)
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 ->
                listOf(
                    ImpactReviewedLibraryHelperKindDocument.SORT_COMPARISON,
                    ImpactReviewedLibraryHelperKindDocument.SORT_ARRAY,
                    ImpactReviewedLibraryHelperKindDocument.SORT_MUTABLE_LIST,
                )
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
                listOf(
                    ImpactReviewedLibraryHelperKindDocument.MAP_INSERTION,
                    ImpactReviewedLibraryHelperKindDocument.MAP_CAPACITY,
                    ImpactReviewedLibraryHelperKindDocument.COLLECTION_CAPACITY,
                )
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
                listOf(ImpactReviewedLibraryHelperKindDocument.COLLECTION_CAPACITY)
            else -> emptyList()
        }
    return values.map { it.kind } == expected && values.all { it.admitsHelper() }
}

private fun ImpactReviewedLibraryHelperDocument.admitsHelper(): Boolean =
    when (kind) {
        ImpactReviewedLibraryHelperKindDocument.GROUP_LOOKUP -> admitsGroupLookup()
        ImpactReviewedLibraryHelperKindDocument.NON_NULL_LET -> admitsNonNullLet()
        ImpactReviewedLibraryHelperKindDocument.SORT_COMPARISON -> admitsSortComparison()
        ImpactReviewedLibraryHelperKindDocument.SORT_ARRAY -> admitsSortArray()
        ImpactReviewedLibraryHelperKindDocument.SORT_MUTABLE_LIST -> admitsSortMutableList()
        ImpactReviewedLibraryHelperKindDocument.MAP_INSERTION -> admitsMapInsertion()
        ImpactReviewedLibraryHelperKindDocument.MAP_CAPACITY -> admitsMapCapacity()
        ImpactReviewedLibraryHelperKindDocument.COLLECTION_CAPACITY -> admitsCollectionCapacity()
    }

private fun ImpactReviewedLibraryHelperDocument.admitsSortComparison(): Boolean =
    sourceEntry.value == "commonMain/kotlin/comparisons/Comparisons.kt" &&
        sourceEntrySha256.value == "a4d437cb59c2a9ee1277211ad895545ec65842f307790358900ac4d22fde67a3" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(
                COMPARE_VALUES_FIRST_LINE to COMPARE_VALUES_LAST_LINE,
                COMPARE_BY_FIRST_LINE to COMPARE_BY_LAST_LINE,
            ) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/comparisons/ComparisonsKt.class" to
                    "dd0351f13c5626b2dece3c20c3239485ebffa13e61559724b366905f2f8fc6bc",
                "kotlin/comparisons/ComparisonsKt__ComparisonsKt.class" to
                    "1a526f8888a57fdc059f9377097408584095429b7170f6a4484bebb4af2e6934",
                "kotlin/comparisons/ComparisonsKt__ComparisonsKt\$compareBy\$2.class" to
                    "ceb7251222b0a46eb3856648a2de0ade5342badda8aeed260f817ae7a1743feb",
            )

private fun ImpactReviewedLibraryHelperDocument.admitsSortArray(): Boolean =
    sourceEntry.value == "jvmMain/generated/_ArraysJvm.kt" &&
        sourceEntrySha256.value == "11c62590490748672aed289ff84aa4c74c2adbf961e701e95d69742b0518fef5" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(ARRAY_SORT_FIRST_LINE to ARRAY_SORT_LAST_LINE) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/collections/ArraysKt.class" to
                    "76a3f1b2f8341ceb3d595cb4a827171bc632b711c9037f48d04c6260dfbf3fed",
                "kotlin/collections/ArraysKt___ArraysJvmKt.class" to
                    "c953c2eec8057b0621601d50e3d4b7b6187b8d37c5e6633588b1c3efe59e92a6",
            )

private fun ImpactReviewedLibraryHelperDocument.admitsSortMutableList(): Boolean =
    sourceEntry.value == "jvmMain/kotlin/collections/MutableCollectionsJVM.kt" &&
        sourceEntrySha256.value == "31cdda1ac47db969471ce55cd1639e263d964cf56ff4ff1df86bb76a68110fd4" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(MUTABLE_SORT_FIRST_LINE to MUTABLE_SORT_LAST_LINE) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/collections/CollectionsKt__MutableCollectionsJVMKt.class" to
                    "1e34bbb7b2eed8b307c414c334dcd693468a098e249d253b23710cb0833f2939"
            )

private fun ImpactReviewedLibraryHelperDocument.admitsMapInsertion(): Boolean =
    sourceEntry.value == "commonMain/kotlin/collections/Maps.kt" &&
        sourceEntrySha256.value == "1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(MAP_INSERTION_FIRST_LINE to MAP_INSERTION_LAST_LINE) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/collections/MapsKt.class" to "520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4",
                "kotlin/collections/MapsKt__MapsKt.class" to
                    "bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389",
            )

private fun ImpactReviewedLibraryHelperDocument.admitsMapCapacity(): Boolean =
    sourceEntry.value == "jvmMain/kotlin/collections/MapsJVM.kt" &&
        sourceEntrySha256.value == "27253b4fc002268413750e06ca5c026496d2c4face80ac0b952e2621d536ec54" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(MAP_CAPACITY_FIRST_LINE to MAP_CAPACITY_LAST_LINE) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/collections/MapsKt__MapsJVMKt.class" to
                    "7d26ea9cedb542a3fa31d8b976f442fe4767e988c52c04ec8d36e865478deda5"
            )

private fun ImpactReviewedLibraryHelperDocument.admitsCollectionCapacity(): Boolean =
    sourceEntry.value == "commonMain/kotlin/collections/Iterables.kt" &&
        sourceEntrySha256.value == "db36d783a35dc062366af184d7e54cbc70f57e3cf570329696931c7008c1e244" &&
        sourceSections.values.map { it.first.value to it.last.value } ==
            listOf(COLLECTION_CAPACITY_LINE to COLLECTION_CAPACITY_LINE) &&
        classes.values.map { it.entry.value to it.sha256.value } ==
            listOf(
                "kotlin/collections/CollectionsKt__IterablesKt.class" to
                    "813642e022be0e7327b93fb9eb92ed8c1500059b4b22747525267e9f6bae1154"
            )

private const val COMPARE_VALUES_FIRST_LINE = 41
private const val COMPARE_VALUES_LAST_LINE = 43
private const val COMPARE_BY_FIRST_LINE = 101
private const val COMPARE_BY_LAST_LINE = 102
private const val ARRAY_SORT_FIRST_LINE = 2197
private const val ARRAY_SORT_LAST_LINE = 2199
private const val MUTABLE_SORT_FIRST_LINE = 41
private const val MUTABLE_SORT_LAST_LINE = 43
private const val MAP_INSERTION_FIRST_LINE = 705
private const val MAP_INSERTION_LAST_LINE = 707
private const val MAP_CAPACITY_FIRST_LINE = 147
private const val MAP_CAPACITY_LAST_LINE = 156
private const val COLLECTION_CAPACITY_LINE = 39
