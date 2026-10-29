package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.EmptyImpactConditions
import io.github.amichne.kast.protocol.contract.ImpactMapEntrySlotDocument
import io.github.amichne.kast.protocol.contract.ImpactPairSlotDocument
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import io.github.amichne.kast.query.contract.QueryImpactClassification
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRelationship
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedComponentProjectionTest {
    @Test
    fun `tuple construction rejects a same typed slot swap and has no callback-shaped authority`() {
        val f = ImpactReviewedComponentFixture()
        val document = f.construct().impactDocument().componentValue()
        val relation = document.binding.relation as ImpactReviewedValueRelationDocument.TupleConstruction
        val binding = document.binding.authority as ImpactReviewedCallValueBindingDocument
        val artifact = binding.artifacts as ImpactReviewedTupleArtifactsDocument
        assertEquals(
            "7d7ae7592527ece70344f2aed7d546c4c44085472154b88fd2e119fef0cd82b0",
            artifact.implementationSha256.value,
        )
        val wrongClass =
            artifact.copy(
                implementationSha256 =
                    "8fed0fa88eb2df5723812a95330dee6bc91fd66b13906acb7906f9a813da9736".impactText().componentValue()
            )
        assertFalse(
            document
                .copy(binding = document.binding.copy(authority = binding.copy(artifacts = wrongClass)))
                .admitsEvidence()
        )
        assertFalse(
            document
                .copy(binding = document.binding.copy(relation = relation.copy(slot = ImpactPairSlotDocument.SECOND)))
                .admitsEvidence()
        )
        assertTrue(document.admitsEvidence())
        val encoded = Json.parseToJsonElement(Json.encodeToString(document)).jsonObject
        val authority = encoded.getValue("binding").jsonObject.getValue("authority").jsonObject
        assertEquals(
            setOf("type", "rule", "basis", "enclosing", "invocation", "selected", "artifacts", "entryReadOrder"),
            authority.keys,
        )
        val readOrder = authority.getValue("entryReadOrder").jsonObject
        assertEquals(setOf("type"), readOrder.keys)
        assertEquals("DIRECT", readOrder.getValue("type").jsonPrimitive.content)
        assertEquals("CALL", authority.getValue("type").jsonPrimitive.content)
        assertEquals("FUNCTION", authority.getValue("selected").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(document.source.valuePath.values, document.target.valuePath.values.drop(1))
    }

    @Test
    fun `association rejects key value swaps another tuple supplier and missing uniqueness premise`() {
        val f = ImpactReviewedComponentFixture()
        val construction = f.construct()
        val document = f.associated(construction).impactDocument().componentValue()
        val relation = document.binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
        assertFalse(
            document
                .copy(
                    binding = document.binding.copy(relation = relation.copy(slot = ImpactMapEntrySlotDocument.VALUE))
                )
                .admitsEvidence()
        )
        val other =
            f.associated(f.construct(start = 55)).impactDocument().componentValue().binding.relation
                as ImpactReviewedValueRelationDocument.AssociationComponent
        assertFalse(
            document
                .copy(binding = document.binding.copy(relation = relation.copy(entry = other.entry)))
                .admitsEvidence()
        )
        assertFalse(document.copy(target = document.target.copy(conditions = EmptyImpactConditions)).admitsEvidence())
        assertTrue(document.admitsEvidence())
        val path = document.target.valuePath.values
        assertEquals(
            ImpactMapEntrySlotDocument.KEY,
            (path.first() as ImpactValuePathSegmentDocument.MapEntryComponent).slot,
        )
        assertEquals(document.source.valuePath.values.drop(1), path.drop(1))
        val premise = document.target.conditions.values.single() as ImpactValueConditionDocument.ReviewedEntryKeyUnique
        assertEquals(relation.entry, premise.entry)
    }

    @Test
    fun `both slots keep shared entry correlation without runtime iteration claims`() {
        val f = ImpactReviewedComponentFixture()
        val key = f.associated(f.construct()).impactDocument().componentValue()
        val value = f.associated(f.construct(first = false)).impactDocument().componentValue()
        assertTrue(key.admitsEvidence())
        assertTrue(value.admitsEvidence())
        val keyRelation = key.binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
        val valueRelation = value.binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
        assertEquals(keyRelation.entry, valueRelation.entry)
        assertNotEquals(keyRelation, valueRelation)
        val entry = Json.parseToJsonElement(Json.encodeToString(keyRelation.entry)).jsonObject
        assertEquals(
            setOf("binding", "tupleOrigin", "returnRange", "returnExecution", "callStack", "keyEvidence"),
            entry.keys,
        )
        assertEquals(
            "NOT_ESTABLISHED",
            entry.getValue("binding").jsonObject.getValue("actualExecution").jsonPrimitive.content,
        )
    }

    @Test
    fun `sorted content cannot claim collection identity while an element keeps exact correspondence`() {
        val f = ImpactReviewedComponentFixture()
        for (whole in listOf(true, false)) {
            val transfer = f.sorted(whole)
            val path =
                QueryImpactPath.fromEvidence(
                        transfer.source,
                        listOf(QueryImpactStep.ReviewedLibrary(transfer)),
                        QueryImpactRepresentation.NotModeled,
                        QueryImpactTerminal.Unresolved.Flow(
                            ValueFlowObligation(transfer.target, ValueFlowUnsupportedCause.UNMODELED_CALL)
                        ),
                    )
                    .componentValue()
            val domainRelationship = if (whole) QueryImpactRelationship.DERIVED else QueryImpactRelationship.EXACT
            val publicRelationship = if (whole) ImpactRelationshipDocument.DERIVED else ImpactRelationshipDocument.EXACT
            assertEquals(QueryImpactClassification.Established(domainRelationship), path.classification)
            assertEquals(
                ImpactPathClassificationDocument.Established(publicRelationship),
                path.classificationDocument().componentValue(),
            )
            assertTrue(path.terminal is QueryImpactTerminal.Unresolved)
        }
    }

    @Test
    fun `sorting preserves nested element path and needs a normal result premise`() {
        val f = ImpactReviewedComponentFixture()
        for (whole in listOf(false, true)) {
            val document = f.sorted(whole).impactDocument().componentValue()
            assertFalse(
                document.copy(target = document.target.copy(conditions = EmptyImpactConditions)).admitsEvidence()
            )
            assertTrue(document.admitsEvidence())
            assertEquals(document.source.valuePath, document.target.valuePath)
            assertTrue(
                document.target.conditions.values.single()
                    is ImpactValueConditionDocument.ReviewedCollectionNormalResult
            )
        }
    }
}
