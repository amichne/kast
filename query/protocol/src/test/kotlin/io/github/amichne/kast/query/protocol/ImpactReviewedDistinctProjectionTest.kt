package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryKeyEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedDistinctProjectionTest {
    @Test
    fun `distinct entry rejects incomplete inventory duplicate strings wrong formal artifact and receiver`() {
        val document = ImpactReviewedDistinctFixture().distinct.impactDocument().componentValue()
        val relation = document.binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
        val proof = (relation.entry.keyEvidence as ImpactReviewedEntryKeyEvidenceDocument.Distinct).proof
        for (wrong in wrongProofs(proof)) assertFalse(document.withProof(wrong).admitsEvidence())
        assertTrue(document.admitsEvidence())
    }

    @Test
    fun `distinct proof survives component paths and encodes exact empty and whitespace constants`() {
        val f = ImpactReviewedDistinctFixture()
        val document = f.distinct.impactDocument().componentValue()
        val conditional = f.conditional.impactDocument().componentValue()
        assertTrue(document.admitsEvidence())
        assertTrue(conditional.admitsEvidence())
        assertEquals(document.source.conditions, document.target.conditions)
        assertEquals(1, conditional.target.conditions.values.size)
        val relation = document.binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
        val entry = (document.target.valuePath.values.first() as ImpactValuePathSegmentDocument.MapEntryComponent).entry
        assertEquals(relation.entry, entry)
        assertNotEquals(conditional.binding.relation, document.binding.relation)
        val encoded = Json.parseToJsonElement(Json.encodeToString(entry.keyEvidence)).jsonObject
        assertEquals("DISTINCT", encoded.getValue("type").jsonPrimitive.content)
        val proof = encoded.getValue("proof").jsonObject
        assertEquals(setOf("input", "key", "associationInvocation", "receiverRange"), proof.keys)
        val input = proof.getValue("input").jsonObject
        assertEquals("2", input.getValue("actualArgumentCount").jsonPrimitive.content)
        assertEquals(2, input.getValue("actualArgumentRanges").jsonArray.size)
        assertEquals(
            listOf("", " value "),
            input.getValue("arguments").jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.content },
        )
    }

    private fun wrongProofs(
        proof: ImpactReviewedDistinctStringKeysDocument
    ): List<ImpactReviewedDistinctStringKeysDocument> {
        val input = proof.input
        val arguments = input.arguments.values
        return listOf(
            proof.copy(input = input.copy(actualArgumentCount = QueryDiscoveryCountDocument.parse(1).componentValue())),
            proof.copy(
                input = input.copy(arguments = BoundedProtocolList.create(arguments.dropLast(1)).componentValue())
            ),
            proof.copy(
                input =
                    input.copy(
                        arguments =
                            BoundedProtocolList.create(
                                    listOf(arguments.first(), arguments.last().copy(value = arguments.first().value))
                                )
                                .componentValue()
                    )
            ),
            proof.copy(
                input = input.copy(formal = input.formal.copy(position = ProtocolOffset.parse(1).componentValue()))
            ),
            proof.copy(
                input =
                    input.copy(
                        artifact =
                            input.artifact.copy(binaryArtifactSha256 = "f".repeat(64).impactText().componentValue())
                    )
            ),
            proof.copy(
                key = proof.key.copy(formal = proof.key.formal.copy(index = ProtocolOffset.parse(1).componentValue()))
            ),
            proof.copy(key = proof.key.copy(returnRange = proof.key.keyRange)),
            proof.copy(receiverRange = proof.key.keyRange),
        )
    }
}

private fun ImpactReviewedValueTransferDocument.withProof(
    proof: ImpactReviewedDistinctStringKeysDocument
): ImpactReviewedValueTransferDocument {
    val relation = binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
    val entry = relation.entry.copy(keyEvidence = ImpactReviewedEntryKeyEvidenceDocument.Distinct(proof))
    val targetPath =
        target.valuePath.map { segment ->
            if (segment is ImpactValuePathSegmentDocument.MapEntryComponent && segment.entry == relation.entry)
                segment.copy(entry = entry)
            else segment
        }
    return copy(
        binding = binding.copy(relation = relation.copy(entry = entry)),
        target = target.copy(valuePath = targetPath),
    )
}
