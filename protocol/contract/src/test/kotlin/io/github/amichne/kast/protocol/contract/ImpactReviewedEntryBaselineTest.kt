package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedEntryBaselineTest {
    @Test
    fun `builtins entry getter cannot borrow pair getter implementation authority`() {
        val fixture = ImpactReviewedValueFixture()
        val artifact = pairArtifact(fixture)
        assertTrue(artifact.admitsRule(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1))
        val target = entryKey(fixture)
        val binding =
            ImpactReviewedCallValueBindingDocument(
                ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1,
                fixture.binding.basis,
                fixture.binding.enclosing,
                ImpactInvocationReferenceDocument(fixture.range(30, 35), target.declaration),
                target,
                artifact,
            )
        assertFalse(binding.admits(binding.enclosing))
    }

    @Test
    fun `mapped result provenance cannot authorize an input key slot`() {
        val fixture = ImpactReviewedMappedFixture()
        assertTrue(fixture.transfer.admitsEvidence())
        val key = ImpactValuePathSegmentDocument.MapEntryComponent(fixture.entry, ImpactMapEntrySlotDocument.KEY)
        assertFalse(key.isOnBasis(fixture.binding.basis))
    }

    @Test
    fun `exact reviewed entry rule has a closed wire identity`() {
        val encoded = Json.encodeToString(EntryRuleOracle.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1)
        assertEquals(
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_KEY_V1,
            Json.decodeFromString<ImpactReviewedValueRuleDocument>(encoded),
        )
    }

    private fun entryKey(fixture: ImpactReviewedValueFixture): ImpactReviewedOrdinaryTargetDocument.Property {
        val signature =
            CompilerSignatureDocument.Property(
                fixture.text("kotlin.collections.Map.Entry.key"),
                CompilerReceiverDocument.Absent,
                fixture.bounded(emptyList()),
                fixture.text("K"),
            )
        val identity = (CompilerSymbolEvidenceDocument.fromSignature(signature) as Refinement.Refined).value.identity
        return ImpactReviewedOrdinaryTargetDocument.Property(
            fixture.binding.selected.declaration.copy(
                file =
                    fixture.text(
                        "jar:///library/kotlin-stdlib-2.3.0.jar!/kotlin/collections/collections.kotlin_builtins"
                    ),
                compilerIdentity = identity,
            ),
            signature.qualifiedIdentity,
            ImpactDeclaredCallbackReceiverDocument.Absent,
            signature.contextReceivers,
            signature.returnType,
        )
    }

    private fun pairArtifact(fixture: ImpactReviewedValueFixture): ImpactReviewedTupleArtifactsDocument =
        ImpactReviewedTupleArtifactsDocument(
            fixture.text("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
            fixture.text("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
            fixture.text("1eaf3b3334dbd646a6b93dbe31db7adba72fdb92e8a5d3ea6b249aff5a7d9e9f"),
            fixture.text("8fed0fa88eb2df5723812a95330dee6bc91fd66b13906acb7906f9a813da9736"),
            fixture.text("8fed0fa88eb2df5723812a95330dee6bc91fd66b13906acb7906f9a813da9736"),
            ImpactReviewedTupleSectionsDocument.Pair(
                ImpactReviewedSourceLinesDocument(fixture.range(27, 36).start, fixture.range(27, 36).end)
            ),
        )
}

@Serializable
private enum class EntryRuleOracle {
    KOTLIN_2_3_0_MAP_ENTRY_KEY_V1
}
