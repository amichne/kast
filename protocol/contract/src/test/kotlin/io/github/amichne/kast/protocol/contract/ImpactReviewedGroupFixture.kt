package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ImpactReviewedGroupFixture {
    private val filtering = ImpactReviewedFilterFixture()
    val base = filtering.base
    private val signature =
        CompilerSignatureDocument.Function(
            base.text("kotlin.collections.groupBy"),
            CompilerReceiverDocument.Present(base.text("kotlin/collections/Iterable<T>")),
            base.bounded(emptyList()),
            base.bounded(listOf(base.text("(T)->K"))),
            (CompilerTypeParameterCountDocument.parse(2) as Refinement.Refined).value,
        )
    private val identity =
        (CompilerSymbolEvidenceDocument.fromSignature(signature) as Refinement.Refined).value.identity
    val binding =
        filtering.binding.copy(
            rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1,
            selected =
                filtering.binding.selected.copy(
                    declaration = filtering.binding.selected.declaration.copy(compilerIdentity = identity),
                    qualifiedIdentity = signature.qualifiedIdentity,
                    valueParameters = signature.valueParameters,
                    typeParameterCount = (QueryDiscoveryCountDocument.parse(2) as Refinement.Refined).value,
                ),
            artifacts =
                (filtering.binding.artifacts as ImpactReviewedCollectionArtifactsDocument).copy(
                    sections = ImpactReviewedCollectionSectionsDocument.GroupBy(lines(1511, 1513), lines(1537, 1544)),
                    helpers = base.bounded(listOf(groupLookup())),
                ),
        )
    val source =
        filtering.source.copy(
            role =
                ImpactValueRoleDocument.Receiver(
                    ImpactInvocationReferenceDocument(binding.supplierRange, binding.selected.declaration),
                    ImpactReceiverKindDocument.EXTENSION,
                )
        )
    val entry =
        ImpactReviewedGroupBucketDocument(
            ImpactReviewedGroupCallbacksDocument.OriginalElements(binding),
            ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS,
            ImpactReviewedEntrySelectionDocument.COMPONENT,
            source.range,
            ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
            source.callStack,
        )
    val transfer =
        ImpactReviewedValueTransferDocument(
            source,
            base.target.copy(
                valuePath =
                    base.bounded(
                        listOf(
                            ImpactValuePathSegmentDocument.MapEntryComponent(entry, ImpactMapEntrySlotDocument.VALUE),
                            ImpactValuePathSegmentDocument.Element,
                        ) + source.valuePath.values.drop(1)
                    ),
                conditions =
                    base.bounded(
                        listOf(
                            ImpactValueConditionDocument.ReviewedGroupMembership(
                                ImpactValueConditionOriginDocument(
                                    entry.sourceExecution,
                                    source.range,
                                    source.role,
                                    source.valuePath,
                                    source.callStack,
                                ),
                                binding.supplierRange,
                                entry,
                            )
                        )
                    ),
            ),
            ImpactReviewedValueRelationshipDocument(binding, ImpactReviewedValueRelationDocument.GroupPlacement(entry)),
        )

    private fun groupLookup() =
        ImpactReviewedLibraryHelperDocument(
            ImpactReviewedLibraryHelperKindDocument.GROUP_LOOKUP,
            base.text("commonMain/kotlin/collections/Maps.kt"),
            base.text("1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7"),
            base.bounded(listOf(lines(382, 391))),
            base.bounded(
                listOf(
                    ImpactReviewedClassEvidenceDocument(
                        base.text("kotlin/collections/MapsKt.class"),
                        base.text("520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4"),
                    ),
                    ImpactReviewedClassEvidenceDocument(
                        base.text("kotlin/collections/MapsKt__MapsKt.class"),
                        base.text("bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389"),
                    ),
                )
            ),
        )

    private fun lines(first: Int, last: Int) =
        ImpactReviewedSourceLinesDocument(base.range(first, last).start, base.range(first, last).end)
}
