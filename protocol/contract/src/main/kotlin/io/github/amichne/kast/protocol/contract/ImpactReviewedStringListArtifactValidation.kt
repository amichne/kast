package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedStringListArtifactDocument.admitsStringListArtifact(): Boolean =
    binaryArtifactSha256.value == "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" &&
        sourceArtifactSha256.value == "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531" &&
        sourceSections.values.map {
            Triple(it.sourceEntry.value, it.sourceEntrySha256.value, it.lines.first.value to it.lines.last.value)
        } == expectedStringListSections() &&
        classes.values.map { it.entry.value to it.sha256.value } == expectedStringListClasses()

private fun expectedStringListSections(): List<Triple<String, String, Pair<Int, Int>>> =
    listOf(
        Triple(
            "commonMain/kotlin/collections/Collections.kt",
            "8f99448da740e96343a5c9a37aa584000a6c914773b089f590e614cc0b0fdd6b",
            LIST_OF_LINE to LIST_OF_LINE,
        ),
        Triple(
            "jvmMain/generated/_ArraysJvm.kt",
            "11c62590490748672aed289ff84aa4c74c2adbf961e701e95d69742b0518fef5",
            ARRAY_AS_LIST_FIRST to ARRAY_AS_LIST_LAST,
        ),
        Triple(
            "jvmMain/kotlin/collections/ArraysUtilJVM.java",
            "94b06874e3859a8f096d1f6aa2fe29bd54aef2ad2ec39e811f5c15f1d568641e",
            ARRAYS_UTIL_FIRST to ARRAYS_UTIL_LAST,
        ),
    )

private fun expectedStringListClasses(): List<Pair<String, String>> =
    listOf(
        "kotlin/collections/CollectionsKt.class" to "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770",
        "kotlin/collections/CollectionsKt__CollectionsKt.class" to
            "4f7c41e7e4fed9faf50a3f24415e32f502d247ae6970b701002cae7128356ee2",
        "kotlin/collections/ArraysKt.class" to "76a3f1b2f8341ceb3d595cb4a827171bc632b711c9037f48d04c6260dfbf3fed",
        "kotlin/collections/ArraysKt___ArraysJvmKt.class" to
            "c953c2eec8057b0621601d50e3d4b7b6187b8d37c5e6633588b1c3efe59e92a6",
        "kotlin/collections/ArraysUtilJVM.class" to "e77d73a588507edb74494536a6ed321e9a291a9aba24704106da1ca5c70dc6d4",
    )

private const val LIST_OF_LINE = 81
private const val ARRAY_AS_LIST_FIRST = 133
private const val ARRAY_AS_LIST_LAST = 135
private const val ARRAYS_UTIL_FIRST = 12
private const val ARRAYS_UTIL_LAST = 14
