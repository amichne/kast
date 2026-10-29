package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ImpactReviewedMappedFixture(
    sourceStart: Int = 50,
    sourceEnd: Int = 60,
    adjustBinding: (ImpactReviewedValueBindingDocument) -> ImpactReviewedValueBindingDocument = { it },
) {
    val base = ImpactReviewedValueFixture()
    private val signature =
        CompilerSignatureDocument.Function(
            base.text("kotlin.collections.mapValues"),
            CompilerReceiverDocument.Present(base.text("kotlin/collections/Map<outK,V>")),
            base.bounded(emptyList()),
            base.bounded(listOf(base.text("(kotlin/collections/Map.Entry<K,V>)->R"))),
            (CompilerTypeParameterCountDocument.parse(3) as Refinement.Refined).value,
        )
    val binding =
        base.binding
            .copy(
                rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_VALUES_V1,
                selected =
                    base.binding.selected.copy(
                        declaration =
                            base.binding.selected.declaration.copy(
                                file =
                                    base.text(
                                        "jar:///library/kotlin-stdlib-2.3.0.jar!/kotlin/collections/MapsKt.class"
                                    ),
                                compilerIdentity =
                                    (CompilerSymbolEvidenceDocument.fromSignature(signature) as Refinement.Refined)
                                        .value
                                        .identity,
                            ),
                        qualifiedIdentity = signature.qualifiedIdentity,
                        receiver =
                            ImpactDeclaredCallbackReceiverDocument.Present(
                                (signature.receiver as CompilerReceiverDocument.Present).compilerType
                            ),
                        valueParameters = signature.valueParameters,
                        typeParameterCount = (QueryDiscoveryCountDocument.parse(3) as Refinement.Refined).value,
                    ),
                artifacts = artifacts(),
            )
            .let(adjustBinding)
    val source =
        base.source.copy(
            range = base.range(sourceStart, sourceEnd),
            executionOwner = ImpactExecutionOwnerDocument.ReviewedCallback(binding),
        )
    val entry =
        ImpactReviewedMappedEntryOriginDocument(
            ImpactReviewedMapEntryFamilyDocument(binding, source.callStack),
            source.range,
            ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
        )
    val condition =
        ImpactValueConditionDocument.ReviewedMappedKeySurvives(
            ImpactValueConditionOriginDocument(
                entry.returnExecution,
                source.range,
                source.role,
                source.valuePath,
                source.callStack,
            ),
            binding.supplierRange,
            entry,
        )
    val transfer =
        ImpactReviewedValueTransferDocument(
            source,
            base.target.copy(
                range = binding.supplierRange,
                valuePath =
                    base.bounded(
                        listOf(
                            ImpactValuePathSegmentDocument.MapEntryComponent(entry, ImpactMapEntrySlotDocument.VALUE)
                        ) + source.valuePath.values
                    ),
                conditions = base.bounded(listOf(condition)),
            ),
            ImpactReviewedValueRelationshipDocument(binding, ImpactReviewedValueRelationDocument.MappedValue(entry)),
        )

    private fun artifacts() =
        ImpactReviewedMapArtifactsDocument(
            base.text("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
            base.text("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
            base.text("1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7"),
            base.text("520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4"),
            base.text("bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389"),
            ImpactReviewedMapSectionsDocument.MapValues(lines(465, 467), lines(414, 416)),
            base.bounded(listOf(mapCapacity())),
        )

    private fun mapCapacity() =
        ImpactReviewedLibraryHelperDocument(
            ImpactReviewedLibraryHelperKindDocument.MAP_CAPACITY,
            base.text("jvmMain/kotlin/collections/MapsJVM.kt"),
            base.text("27253b4fc002268413750e06ca5c026496d2c4face80ac0b952e2621d536ec54"),
            base.bounded(listOf(lines(147, 156))),
            base.bounded(
                listOf(
                    ImpactReviewedClassEvidenceDocument(
                        base.text("kotlin/collections/MapsKt__MapsJVMKt.class"),
                        base.text("7d26ea9cedb542a3fa31d8b976f442fe4767e988c52c04ec8d36e865478deda5"),
                    )
                )
            ),
        )

    private fun lines(first: Int, last: Int) =
        ImpactReviewedSourceLinesDocument(
            (ProtocolOffset.parse(first) as Refinement.Refined).value,
            (ProtocolOffset.parse(last) as Refinement.Refined).value,
        )
}
