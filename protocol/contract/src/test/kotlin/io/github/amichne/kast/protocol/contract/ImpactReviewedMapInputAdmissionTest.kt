package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedMapInputAdmissionTest {
    @Test
    fun `applied input semantics cannot replace canonical callback owner authority`() {
        val f = InputAdmissionFixture()
        val forged =
            f.binding.copy(
                semantics = ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER
            )
        val owner = ImpactExecutionOwnerDocument.ReviewedCallback(forged)
        assertFalse(forged.admits(forged.enclosing))
        assertFalse(f.transfer.copy(target = f.target(owner = owner)).admitsEvidence())
        assertTrue(f.transfer.admitsEvidence())
    }

    @Test
    fun `map input rejects another formal index invocation slot and stale epoch`() {
        val f = InputAdmissionFixture()
        val sourceRole = f.transfer.source.role as ImpactValueRoleDocument.Receiver
        val foreignBasis =
            (f.binding.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = (ImpactEvidenceRevisionDocument.parse(8) as Refinement.Refined).value
            )
        val cases =
            listOf(
                f.transfer.copy(target = f.target(index = 1)),
                f.transfer.copy(
                    source = f.transfer.source.copy(role = sourceRole.copy(kind = ImpactReceiverKindDocument.DISPATCH))
                ),
                f.transfer.copy(
                    source =
                        f.transfer.source.copy(
                            role =
                                sourceRole.copy(invocation = sourceRole.invocation.copy(range = f.base.range(31, 90)))
                        )
                ),
                f.transfer.copy(source = f.transfer.source.copy(range = f.base.range(41, 45))),
                f.transfer.copy(
                    source = f.transfer.source.copy(enclosing = f.binding.enclosing.copy(basis = foreignBasis))
                ),
                f.transfer.copy(binding = f.transfer.binding.copy(supplierRange = f.base.range(31, 90))),
            )
        for (candidate in cases) assertFalse(candidate.admitsEvidence())
        assertTrue(f.transfer.admitsEvidence())
    }

    @Test
    fun `Kotlin 2_3 map needs its own exact artifact hashes and reviewed spans`() {
        val f = InputAdmissionFixture(kotlin230 = true)
        assertTrue(f.binding.admits(f.binding.enclosing))
        assertTrue(f.transfer.admitsEvidence())
        val artifacts = f.binding.artifacts as ImpactReviewedMapArtifactsDocument
        val wrong =
            listOf(
                artifacts.copy(binaryArtifactSha256 = f.base.text("f".repeat(64))),
                artifacts.copy(sourceArtifactSha256 = f.base.text("f".repeat(64))),
                artifacts.copy(sourceEntrySha256 = f.base.text("f".repeat(64))),
                artifacts.copy(facadeSha256 = f.base.text("f".repeat(64))),
                artifacts.copy(implementationSha256 = f.base.text("f".repeat(64))),
                artifacts.copy(mapLines = ImpactReviewedSourceLinesDocument(offset(1744), offset(1746))),
                artifacts.copy(mapToLines = ImpactReviewedSourceLinesDocument(offset(1819), offset(1823))),
            )
        for (artifact in wrong) assertFalse(
            f.transfer.copy(binding = f.transfer.binding.copy(artifacts = artifact)).admitsEvidence()
        )
        assertFalse(
            f.transfer
                .copy(
                    binding =
                        f.transfer.binding.copy(rule = ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1)
                )
                .admitsEvidence()
        )
        assertFalse(
            f.transfer.copy(binding = f.transfer.binding.copy(artifacts = f.base.binding.artifacts)).admitsEvidence()
        )
    }
}

private class InputAdmissionFixture(kotlin230: Boolean = false) {
    val base = ImpactReviewedValueFixture()
    val binding =
        if (kotlin230)
            base.binding.copy(
                rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1,
                selected =
                    base.binding.selected.copy(
                        declaration =
                            base.binding.selected.declaration.copy(
                                file =
                                    base.text(
                                        "jar:///library/kotlin-stdlib-2.3.0.jar!/kotlin/collections/CollectionsKt.class"
                                    )
                            )
                    ),
                artifacts =
                    ImpactReviewedMapArtifactsDocument(
                        base.text("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
                        base.text("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
                        base.text("03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970"),
                        base.text("516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770"),
                        base.text("fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39"),
                        ImpactReviewedSourceLinesDocument(offset(1585), offset(1587)),
                        ImpactReviewedSourceLinesDocument(offset(1660), offset(1664)),
                    ),
            )
        else base.binding
    private val source =
        base.source.copy(
            range = base.range(30, 35),
            role =
                ImpactValueRoleDocument.Receiver(
                    ImpactInvocationReferenceDocument(binding.supplierRange, binding.selected.declaration),
                    ImpactReceiverKindDocument.EXTENSION,
                ),
            executionOwner = ImpactExecutionOwnerDocument.Declaration,
            valuePath = EmptyImpactValuePath,
        )
    val transfer =
        ImpactReviewedValueTransferDocument(
            source,
            target(),
            ImpactReviewedValueRelationshipDocument.fromBinding(
                binding,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
            ),
        )

    fun target(
        index: Int = 0,
        owner: ImpactExecutionOwnerDocument = ImpactExecutionOwnerDocument.ReviewedCallback(binding),
    ): ImpactValueSiteReferenceDocument {
        val parameter =
            ImpactFormalParameterDocument(
                binding.enclosing,
                offset(index),
                base.text("item"),
                base.range(40, 45),
                owner,
            )
        return base.source.copy(
            range = parameter.range,
            role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)),
            executionOwner = owner,
            valuePath = EmptyImpactValuePath,
        )
    }
}

private fun offset(value: Int): ProtocolOffset = (ProtocolOffset.parse(value) as Refinement.Refined).value
