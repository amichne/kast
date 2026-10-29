package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedArtifactsDocument.admitsRule(rule: ImpactReviewedValueRuleDocument): Boolean =
    when (this) {
        is ImpactReviewedEntryDeclarationArtifactDocument -> admitsEntryDeclaration(rule)
        is ImpactReviewedMapArtifactsDocument -> admitsMapValues(rule)
        is ImpactReviewedCollectionArtifactsDocument -> admitsCollection(rule)
        is ImpactReviewedScopeArtifactsDocument -> admitsScope(rule)
        is ImpactReviewedTupleArtifactsDocument -> admitsTuple(rule)
    }

private fun ImpactReviewedCollectionArtifactsDocument.admitsCollection(rule: ImpactReviewedValueRuleDocument): Boolean {
    val sectionsAdmitted =
        when (val section = sections) {
            is ImpactReviewedCollectionSectionsDocument.GroupBy,
            is ImpactReviewedCollectionSectionsDocument.GroupByTransform -> section.admitsGroupSections(rule)
            is ImpactReviewedCollectionSectionsDocument.Filter,
            is ImpactReviewedCollectionSectionsDocument.MapNotNull -> section.admitsSelectionSections(rule)
            is ImpactReviewedCollectionSectionsDocument.Map ->
                when (rule) {
                    ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 ->
                        section.mapLines.matches(1585, 1587) && section.mapToLines.matches(1660, 1664)
                    ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1 ->
                        section.mapLines.matches(1744, 1746) && section.mapToLines.matches(1819, 1823)
                    else -> false
                }
            is ImpactReviewedCollectionSectionsDocument.SortedBy ->
                rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 && section.admits()
            is ImpactReviewedCollectionSectionsDocument.Associate ->
                rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 &&
                    section.associateLines.matches(1204, 1207) &&
                    section.associateToLines.matches(1281, 1286)
        }
    if (!sectionsAdmitted || !helpers.admitsHelpers(rule)) return false
    return if (rule == ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1)
        matches(
            "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3",
            "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2",
            "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c",
            "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878",
            "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296",
        )
    else
        matches230(
            "03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970",
            "516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770",
            "fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39",
        )
}

private fun ImpactReviewedCollectionSectionsDocument.SortedBy.admits(): Boolean =
    sortedByLines.matches(SORTED_BY_FIRST_LINE, SORTED_BY_LAST_LINE) &&
        sortedWithLines.matches(SORTED_WITH_FIRST_LINE, SORTED_WITH_LAST_LINE) &&
        toListLines.matches(TO_LIST_FIRST_LINE, TO_LIST_LAST_LINE) &&
        toMutableListLines.matches(TO_MUTABLE_FIRST_LINE, TO_MUTABLE_LAST_LINE) &&
        collectionToMutableListLines.matches(COLLECTION_MUTABLE_FIRST_LINE, COLLECTION_MUTABLE_LAST_LINE)

private fun ImpactReviewedScopeArtifactsDocument.admitsScope(rule: ImpactReviewedValueRuleDocument): Boolean =
    when (rule) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_TOP_LEVEL_V1 ->
            functionLines.matches(RUN_TOP_FIRST_LINE, RUN_TOP_LAST_LINE)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_RUN_RECEIVER_V1 ->
            functionLines.matches(RUN_RECEIVER_FIRST_LINE, RUN_RECEIVER_LAST_LINE)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_WITH_V1 -> functionLines.matches(WITH_FIRST_LINE, WITH_LAST_LINE)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_APPLY_V1 ->
            functionLines.matches(APPLY_FIRST_LINE, APPLY_LAST_LINE)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1 -> functionLines.matches(LET_FIRST_LINE, LET_LAST_LINE)
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1 -> functionLines.matches(ALSO_FIRST_LINE, ALSO_LAST_LINE)
        else -> false
    } &&
        matches230(
            "86b7320d868d66492d277ebb5d5d8ad78da074d592dacf32f186a83d8d3196a0",
            "f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05",
            "bfdad3872af89a51041163c03afda592baea4e394a8b1665de5b7547f4f43ce0",
        )

private fun ImpactReviewedTupleArtifactsDocument.admitsTuple(rule: ImpactReviewedValueRuleDocument): Boolean {
    val sectionAdmitted =
        when (val section = sections) {
            is ImpactReviewedTupleSectionsDocument.To ->
                rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1 &&
                    section.toLines.matches(44, 44) &&
                    section.pairLines.matches(27, 36)
            is ImpactReviewedTupleSectionsDocument.Pair ->
                rule in
                    setOf(
                        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1,
                        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1,
                        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_SECOND_V1,
                    ) && section.pairLines.matches(27, 36)
        }
    val facade =
        if (rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1)
            "7d7ae7592527ece70344f2aed7d546c4c44085472154b88fd2e119fef0cd82b0"
        else "8fed0fa88eb2df5723812a95330dee6bc91fd66b13906acb7906f9a813da9736"
    return sectionAdmitted &&
        matches230(
            "1eaf3b3334dbd646a6b93dbe31db7adba72fdb92e8a5d3ea6b249aff5a7d9e9f",
            facade,
            facade,
        )
}

private fun ImpactReviewedJvmArtifactHashes.matches230(entry: String, facade: String, implementation: String): Boolean =
    matches(
        "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1",
        "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531",
        entry,
        facade,
        implementation,
    )

private fun ImpactReviewedJvmArtifactHashes.matches(
    binary: String,
    source: String,
    entry: String,
    facade: String,
    implementation: String,
): Boolean =
    binaryArtifactSha256.value == binary &&
        sourceArtifactSha256.value == source &&
        sourceEntrySha256.value == entry &&
        facadeSha256.value == facade &&
        implementationSha256.value == implementation

internal fun ImpactReviewedSourceLinesDocument.matches(first: Int, last: Int): Boolean =
    this.first.value == first && this.last.value == last

private const val SORTED_BY_FIRST_LINE = 1067
private const val SORTED_BY_LAST_LINE = 1069
private const val SORTED_WITH_FIRST_LINE = 1097
private const val SORTED_WITH_LAST_LINE = 1104
private const val TO_LIST_FIRST_LINE = 1342
private const val TO_LIST_LAST_LINE = 1351
private const val TO_MUTABLE_FIRST_LINE = 1356
private const val TO_MUTABLE_LAST_LINE = 1360
private const val COLLECTION_MUTABLE_FIRST_LINE = 1365
private const val COLLECTION_MUTABLE_LAST_LINE = 1367
private const val LET_FIRST_LINE = 114
private const val LET_LAST_LINE = 119
private const val ALSO_FIRST_LINE = 99
private const val ALSO_LAST_LINE = 105

private fun ImpactReviewedMapArtifactsDocument.admitsMapValues(rule: ImpactReviewedValueRuleDocument): Boolean =
    admitsMapSections(rule) &&
        helpers.admitsHelpers(rule) &&
        matches230(
            "1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7",
            "520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4",
            "bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389",
        )

private fun ImpactReviewedMapArtifactsDocument.admitsMapSections(rule: ImpactReviewedValueRuleDocument): Boolean =
    when (val lines = sections) {
        is ImpactReviewedMapSectionsDocument.MapValues ->
            rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_VALUES_V1 &&
                lines.mapValuesLines.matches(MAP_VALUES_FIRST_LINE, MAP_VALUES_LAST_LINE) &&
                lines.mapValuesToLines.matches(MAP_VALUES_TO_FIRST_LINE, MAP_VALUES_TO_LAST_LINE)
        is ImpactReviewedMapSectionsDocument.EntryComponent ->
            when (rule) {
                ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1 ->
                    lines.componentLines.matches(ENTRY_COMPONENT_ONE_LINE, ENTRY_COMPONENT_ONE_LINE)
                ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 ->
                    lines.componentLines.matches(ENTRY_COMPONENT_TWO_LINE, ENTRY_COMPONENT_TWO_LINE)
                else -> false
            }
    }

private fun ImpactReviewedEntryDeclarationArtifactDocument.admitsEntryDeclaration(
    rule: ImpactReviewedValueRuleDocument
): Boolean =
    (rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1 ||
        rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_VALUE_V1) &&
        binaryArtifactSha256.value == "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1" &&
        sourceArtifactSha256.value == "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531" &&
        sourceEntrySha256.value == "a972160cdcfa4c563abeaf0d10992080470713315a2028de4c421a761a0b3850" &&
        declarationLines.matches(ENTRY_DECLARATION_FIRST_LINE, ENTRY_DECLARATION_LAST_LINE) &&
        metadata.entry.value == "kotlin/collections/collections.kotlin_builtins" &&
        metadata.sha256.value == "e91b5fc8241f903f0cd2c6e3d038c10a9c74a8f22659dfa9268e21afcca70bf1"

private const val MAP_VALUES_FIRST_LINE = 465
private const val MAP_VALUES_LAST_LINE = 467
private const val MAP_VALUES_TO_FIRST_LINE = 414
private const val MAP_VALUES_TO_LAST_LINE = 416
private const val ENTRY_COMPONENT_ONE_LINE = 312
private const val ENTRY_COMPONENT_TWO_LINE = 325
private const val ENTRY_DECLARATION_FIRST_LINE = 611
private const val ENTRY_DECLARATION_LAST_LINE = 627

private const val RUN_TOP_FIRST_LINE = 41
private const val RUN_TOP_LAST_LINE = 46
private const val RUN_RECEIVER_FIRST_LINE = 55
private const val RUN_RECEIVER_LAST_LINE = 60
private const val WITH_FIRST_LINE = 69
private const val WITH_LAST_LINE = 74
private const val APPLY_FIRST_LINE = 83
private const val APPLY_LAST_LINE = 89
