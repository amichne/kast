package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ImpactReviewedFilterFixture {
    val base = ImpactReviewedValueFixture()
    private val signature =
        CompilerSignatureDocument.Function(
            base.text("kotlin.collections.filter"),
            CompilerReceiverDocument.Present(base.text("kotlin/collections/Iterable<T>")),
            base.bounded(emptyList()),
            base.bounded(listOf(base.text("(T)->kotlin/Boolean"))),
            (CompilerTypeParameterCountDocument.parse(1) as Refinement.Refined).value,
        )
    private val identity =
        (CompilerSymbolEvidenceDocument.fromSignature(signature) as Refinement.Refined).value.identity
    val binding =
        base.binding.copy(
            rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1,
            selected =
                base.binding.selected.copy(
                    declaration =
                        base.binding.selected.declaration.copy(
                            compilerIdentity = identity,
                            file =
                                base.text(
                                    "jar:///library/kotlin-stdlib-2.3.0.jar!/kotlin/collections/CollectionsKt.class"
                                ),
                        ),
                    qualifiedIdentity = signature.qualifiedIdentity,
                    valueParameters = signature.valueParameters,
                    typeParameterCount = (QueryDiscoveryCountDocument.parse(1) as Refinement.Refined).value,
                ),
            artifacts =
                (base.binding.artifacts as ImpactReviewedCollectionArtifactsDocument).copy(
                    binaryArtifactSha256 =
                        base.text("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
                    sourceArtifactSha256 =
                        base.text("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
                    sourceEntrySha256 = base.text("03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970"),
                    facadeSha256 = base.text("516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770"),
                    implementationSha256 =
                        base.text("fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39"),
                    sections = ImpactReviewedCollectionSectionsDocument.Filter(lines(776, 778), lines(872, 875)),
                    helpers = base.bounded(emptyList()),
                ),
        )
    val source =
        base.target.copy(
            range = base.range(30, 35),
            role =
                ImpactValueRoleDocument.Receiver(
                    ImpactInvocationReferenceDocument(binding.supplierRange, binding.selected.declaration),
                    ImpactReceiverKindDocument.EXTENSION,
                ),
        )
    private val origin =
        ImpactValueConditionOriginDocument(
            ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
            source.range,
            source.role,
            source.valuePath,
            source.callStack,
        )
    val transfer =
        ImpactReviewedValueTransferDocument(
            source,
            base.target.copy(
                range = binding.supplierRange,
                conditions =
                    base.bounded(
                        listOf(
                            ImpactValueConditionDocument.ReviewedCollectionNormalResult(
                                origin,
                                binding.supplierRange,
                                binding,
                            ),
                            ImpactValueConditionDocument.ReviewedPredicateAccepts(
                                origin,
                                binding.supplierRange,
                                binding,
                            ),
                        )
                    ),
            ),
            ImpactReviewedValueRelationshipDocument(
                binding,
                ImpactReviewedValueRelationDocument.FilteredElements(ImpactReviewedCollectionSelectionDocument.ELEMENT),
            ),
        )

    private fun lines(first: Int, last: Int) =
        ImpactReviewedSourceLinesDocument(base.range(first, last).start, base.range(first, last).end)
}
