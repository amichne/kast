package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Source-derived synthetic declarations; exact native signatures remain a separate capture obligation. */
internal enum class ReviewedEagerAdmissionCase(
    val qualified: String,
    val receiver: String?,
    val parameters: List<String>,
    val typeParameters: Int,
    val callbackPositions: List<Int>,
    val sourceSpan: Pair<Int, Int>,
) {
    ITERABLE_FILTER(
        "kotlin.collections.filter",
        "kotlin/collections/Iterable<T>",
        listOf("(T)->kotlin/Boolean"),
        1,
        listOf(0),
        776 to 778,
    ),
    ITERABLE_MAP_NOT_NULL(
        "kotlin.collections.mapNotNull",
        "kotlin/collections/Iterable<T>",
        listOf("(T)->R?"),
        2,
        listOf(0),
        1641 to 1643,
    ),
    ITERABLE_GROUP_BY(
        "kotlin.collections.groupBy",
        "kotlin/collections/Iterable<T>",
        listOf("(T)->K"),
        2,
        listOf(0),
        1511 to 1513,
    ),
    ITERABLE_GROUP_BY_TRANSFORM(
        "kotlin.collections.groupBy",
        "kotlin/collections/Iterable<T>",
        listOf("(T)->K", "(T)->V"),
        3,
        listOf(0, 1),
        1524 to 1526,
    ),
    RUN_TOP_LEVEL("kotlin.run", null, listOf("()->R"), 1, listOf(0), 41 to 46),
    RUN_RECEIVER("kotlin.run", "T", listOf("T.()->R"), 2, listOf(0), 55 to 60),
    WITH("kotlin.with", null, listOf("T", "T.()->R"), 2, listOf(1), 69 to 74),
    APPLY("kotlin.apply", "T", listOf("T.()->kotlin/Unit"), 1, listOf(0), 83 to 89);

    private val collection: Boolean
        get() = qualified.startsWith("kotlin.collections.")

    val classEntry: String
        get() = if (collection) "kotlin/collections/CollectionsKt.class" else "kotlin/StandardKt.class"

    val sourceEntry: String
        get() = if (collection) "commonMain/generated/_Collections.kt" else "commonMain/kotlin/util/Standard.kt"

    val sourceHash: String
        get() =
            if (collection) "03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970"
            else "86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0"

    val facadeHash: String
        get() =
            if (collection) "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770"
            else "f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05"

    val implementationHash: String
        get() =
            if (collection) "fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39"
            else "bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0"

    fun target(
        fixture: ReviewedValueFixture,
        receiver: String? = this.receiver,
        typeParameters: Int = this.typeParameters,
        file: String = classEntry,
        contexts: List<String> = emptyList(),
    ): CompilerGroundedSymbolEvidence =
        fixture.target(
            qualified = qualified,
            receiver = receiver,
            parameters = parameters,
            typeParameters = typeParameters,
            contextReceivers = contexts,
            file =
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///library.jar!/$file").flowRefined()
                ),
        )
}
