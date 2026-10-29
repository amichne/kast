package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedMapInputAdmissionTest {
    @Test
    fun `eager map rejects mixed versions and independently changed archives`() {
        val fixture = ReviewedValueFixture()
        val pairs =
            listOf(
                BINARY_230 to ReviewedValueFixture.SOURCE_HASH,
                ReviewedValueFixture.BINARY_HASH to SOURCE_230,
                "a".repeat(64) to SOURCE_230,
                BINARY_230 to "b".repeat(64),
            )
        for ((binary, source) in pairs) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
                ReviewedLibraryArtifact.admit(fixture.selected, fixture.digest(binary), fixture.digest(source)),
            )
        }
    }

    @Test
    fun `receiver and callback formal cannot become map output without body flow`() {
        val fixture = ReviewedValueFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.callbackResult(receiver(fixture), fixture.binding),
        )
        val owner = ValueExecutionOwner.reviewedCallback(fixture.binding)
        val formal =
            ValueFormalParameter.fromCompiler(
                    callable = fixture.enclosing,
                    position = fixture.position(0),
                    name = SymbolDiscoveryCandidateName.parse("element").flowRefined(),
                    range = flowRange(436, 457),
                    executionOwner = owner,
                )
                .flowRefined()
        val input = fixture.source(role = ValueRole.Formal(ValueFormalPosition.Parameter(formal)), owner = owner)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(input, fixture.binding),
        )
    }

    @Test
    fun `a matching map name cannot borrow another supplier or callback body`() {
        val fixture = ReviewedValueFixture()
        val other = flowEndpoint("other", 356, 459) as RelationEndpoint.Resolved
        val supplier = RelationCallableBody.Named.fromCompiler(other.evidence).flowRefined()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            fixture.binding(supplier = supplier),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            fixture.binding(body = fixture.body(410, 420)),
        )
    }

    @Test
    fun `exact two three eager map has its own reviewed source rule`() {
        val fixture = ReviewedValueFixture()
        val admitted =
            ReviewedLibraryArtifact.admit(
                fixture.selected,
                fixture.digest(BINARY_230),
                fixture.digest(SOURCE_230),
            )
        assertTrue(admitted is Refinement.Refined, "Exact Kotlin 2.3.0 eager map admission is missing: $admitted")
        val artifact = (admitted as Refinement.Refined).value
        assertEquals("KOTLIN_2_3_0_ITERABLE_MAP_V1", artifact.rule.name)
        assertEquals("commonMain/generated/_Collections.kt", artifact.sourceEntry)
        assertEquals("03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970", artifact.sourceEntrySha256)
        assertEquals("516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770", artifact.facadeSha256)
        assertEquals("fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39", artifact.implementationSha256)
        assertEquals(listOf(1585 to 1587, 1660 to 1664), artifact.sourceSections.map { it.firstLine to it.lastLine })
        assertOutputCorrespondence(fixture, artifact)
    }

    @Test
    fun `existing eager map keeps its exact reviewed source rule`() {
        val fixture = ReviewedValueFixture()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    fixture.selected,
                    fixture.digest(ReviewedValueFixture.BINARY_HASH),
                    fixture.digest(ReviewedValueFixture.SOURCE_HASH),
                )
                .flowRefined()
        assertEquals("KOTLIN_2_4_21_ITERABLE_MAP_V1", artifact.rule.name)
        assertEquals(listOf(1744 to 1746, 1819 to 1823), artifact.sourceSections.map { it.firstLine to it.lastLine })
        assertOutputCorrespondence(fixture, artifact)
    }

    private fun assertOutputCorrespondence(fixture: ReviewedValueFixture, artifact: ReviewedLibraryArtifact) {
        val binding =
            ReviewedCallbackValueBinding.admit(
                    enclosing = fixture.enclosing,
                    body = fixture.body,
                    occurrence = fixture.occurrence,
                    supplier = fixture.supplier,
                    selected = fixture.selected,
                    artifact = artifact,
                )
                .flowRefined()
        val source = fixture.source(owner = ValueExecutionOwner.reviewedCallback(binding))
        val transfer = ReviewedValueTransfer.callbackResult(source, binding).flowRefined()
        assertEquals(
            listOf(ValuePathSegment.Element, ValuePathSegment.Field(fixture.field)),
            transfer.target.valuePath.segments,
        )
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, transfer.binding.invocationEvidence)
        assertEquals(ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT, transfer.semantics)
    }

    private fun receiver(fixture: ReviewedValueFixture): ValueSite {
        val target =
            RelationEndpoint.resolve(
                    fixture.enclosing.lease,
                    fixture.enclosing.scope,
                    fixture.selected.target,
                )
                .flowRefined()
        val call = ValueInvocation.fromCompiler(fixture.enclosing, fixture.occurrence.range, target).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = fixture.enclosing,
                range = flowRange(424, 433),
                role = ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                valuePath = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(fixture.field)),
            )
            .flowRefined()
    }

    private companion object {
        const val BINARY_230 = "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"
        const val SOURCE_230 = "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
    }
}
