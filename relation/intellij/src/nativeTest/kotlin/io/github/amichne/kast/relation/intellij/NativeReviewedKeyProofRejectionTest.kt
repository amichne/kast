package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ReviewedEntryKeyEvidence
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole

class NativeReviewedKeyProofRejectionTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-key-proof-rejection-jdk"

    fun testSpreadAndUnknownLiteralInputsKeepConditionalEntryEvidence() {
        assertConditionalEntries("spreadKeyAssociation", "unknownLiteralKeyAssociation")
    }

    fun testSingleElementListOverloadDoesNotClaimVarargEvidence() {
        assertConditionalEntries("singleOverloadKeyAssociation")
    }

    fun testTransformedAndCapturedKeysKeepConditionalEntryEvidence() {
        assertConditionalEntries("transformedKeyAssociation", "capturedKeyAssociation")
    }

    fun testLocalAliasDoesNotClaimDirectFormalEvidence() {
        assertConditionalEntries("aliasKeyAssociation")
    }

    private fun assertConditionalEntries(vararg names: String) {
        val fixture = fixture(paths = listOf(CONTROLS, KEY_PROOF_CONTROLS))
        for (name in names) {
            val owner = PacketDeclaration(KEY_PROOF_CONTROLS, "", name)
            val steps = trace(fixture, owner, budget = composedBudget(fixture))
            val destination = fixture.markedExpression(owner, "destination")
            assertTrue(
                "The case must admit the real association for $name",
                steps
                    .flatMap { it.reviewedTransfers }
                    .any { transfer ->
                        transfer.relation is ReviewedValueRelation.AssociationComponent &&
                            transfer.target.range == destination.anchor
                    },
            )
            val result = steps.singleOrNull {
                it.source.range == destination.anchor && it.source.role == ValueRole.Return
            }
            assertNotNull("The selected payload must reach the exact map result for $name", result)
            val source = checkNotNull(result).source
            val entry = source.valuePath.segments.single() as ValuePathSegment.MapEntryComponent
            assertEquals(MapEntrySlot.VALUE, entry.slot)
            assertEquals(
                ReviewedEntryKeyEvidence.Conditional,
                (entry.entry as io.github.amichne.kast.relation.contract.ReviewedMapEntryCorrelation).keyEvidence,
            )
            assertTrue(
                "Unsupported key proof must keep the exact entry premise for $name",
                source.conditions.facts.any {
                    it is ValueCondition.ReviewedEntryKeyUnique && it.entry == entry.entry
                },
            )
        }
    }

    private companion object {
        const val KEY_PROOF_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedKeyProofControls.kt"
    }
}
