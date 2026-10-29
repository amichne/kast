package io.github.amichne.kast.relation.contract

/** Exact reviewed helper evidence stays under the selected artifact owner. */
data class ReviewedClassEvidence(val entry: String, val sha256: String)

enum class ReviewedLibraryHelper(
    val sourceEntry: String,
    val sourceEntrySha256: String,
    val sourceSections: List<ReviewedSourceSection>,
    val classes: List<ReviewedClassEvidence>,
) {
    SORT_COMPARISON(
        "commonMain/kotlin/comparisons/Comparisons.kt",
        "a4d437cb59c2a9ee1277211ad895545ec65842f307790358900ac4d22fde67a3",
        listOf(ReviewedSourceSection.COMPARE_VALUES_BY, ReviewedSourceSection.COMPARE_BY),
        listOf(
            ReviewedClassEvidence(
                "kotlin/comparisons/ComparisonsKt.class",
                "dd0351f13c5626b2dece3c20c3239485ebffa13e61559724b366905f2f8fc6bc",
            ),
            ReviewedClassEvidence(
                "kotlin/comparisons/ComparisonsKt__ComparisonsKt.class",
                "1a526f8888a57fdc059f9377097408584095429b7170f6a4484bebb4af2e6934",
            ),
            ReviewedClassEvidence(
                "kotlin/comparisons/ComparisonsKt__ComparisonsKt\$compareBy\$2.class",
                "ceb7251222b0a46eb3856648a2de0ade5342badda8aeed260f817ae7a1743feb",
            ),
        ),
    ),
    SORT_ARRAY(
        "jvmMain/generated/_ArraysJvm.kt",
        "11c62590490748672aed289ff84aa4c74c2adbf961e701e95d69742b0518fef5",
        listOf(ReviewedSourceSection.ARRAY_SORT_WITH),
        listOf(
            ReviewedClassEvidence(
                "kotlin/collections/ArraysKt.class",
                "76a3f1b2f8341ceb3d595cb4a827171bc632b711c9037f48d04c6260dfbf3fed",
            ),
            ReviewedClassEvidence(
                "kotlin/collections/ArraysKt___ArraysJvmKt.class",
                "c953c2eec8057b0621601d50e3d4b7b6187b8d37c5e6633588b1c3efe59e92a6",
            ),
        ),
    ),
    SORT_MUTABLE_LIST(
        "jvmMain/kotlin/collections/MutableCollectionsJVM.kt",
        "31cdda1ac47db969471ce55cd1639e263d964cf56ff4ff1df86bb76a68110fd4",
        listOf(ReviewedSourceSection.MUTABLE_LIST_SORT_WITH),
        listOf(
            ReviewedClassEvidence(
                "kotlin/collections/CollectionsKt__MutableCollectionsJVMKt.class",
                "1e34bbb7b2eed8b307c414c334dcd693468a098e249d253b23710cb0833f2939",
            )
        ),
    ),
    MAP_INSERTION(
        "commonMain/kotlin/collections/Maps.kt",
        "1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7",
        listOf(ReviewedSourceSection.MAP_PLUS_ASSIGN),
        listOf(
            ReviewedClassEvidence(
                "kotlin/collections/MapsKt.class",
                "520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4",
            ),
            ReviewedClassEvidence(
                "kotlin/collections/MapsKt__MapsKt.class",
                "bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389",
            ),
        ),
    ),
    MAP_CAPACITY(
        "jvmMain/kotlin/collections/MapsJVM.kt",
        "27253b4fc002268413750e06ca5c026496d2c4face80ac0b952e2621d536ec54",
        listOf(ReviewedSourceSection.MAP_CAPACITY),
        listOf(
            ReviewedClassEvidence(
                "kotlin/collections/MapsKt__MapsJVMKt.class",
                "7d26ea9cedb542a3fa31d8b976f442fe4767e988c52c04ec8d36e865478deda5",
            )
        ),
    ),
    COLLECTION_CAPACITY(
        "commonMain/kotlin/collections/Iterables.kt",
        "db36d783a35dc062366af184d7e54cbc70f57e3cf570329696931c7008c1e244",
        listOf(ReviewedSourceSection.COLLECTION_SIZE_OR_DEFAULT),
        listOf(
            ReviewedClassEvidence(
                "kotlin/collections/CollectionsKt__IterablesKt.class",
                "813642e022be0e7327b93fb9eb92ed8c1500059b4b22747525267e9f6bae1154",
            )
        ),
    ),
}

internal fun ReviewedLibraryRule.reviewedHelpers(): List<ReviewedLibraryHelper> =
    when (this) {
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 ->
            listOf(
                ReviewedLibraryHelper.SORT_COMPARISON,
                ReviewedLibraryHelper.SORT_ARRAY,
                ReviewedLibraryHelper.SORT_MUTABLE_LIST,
            )
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ->
            listOf(
                ReviewedLibraryHelper.MAP_INSERTION,
                ReviewedLibraryHelper.MAP_CAPACITY,
                ReviewedLibraryHelper.COLLECTION_CAPACITY,
            )
        ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1 -> listOf(ReviewedLibraryHelper.COLLECTION_CAPACITY)
        ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1,
        ReviewedLibraryRule.KOTLIN_2_3_0_LET_V1,
        ReviewedLibraryRule.KOTLIN_2_3_0_ALSO_V1,
        ReviewedLibraryRule.KOTLIN_2_3_0_TO_V1,
        ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
        ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_FIRST_V1,
        ReviewedLibraryRule.KOTLIN_2_3_0_PAIR_SECOND_V1 -> emptyList()
    }
