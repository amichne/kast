package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedGroupTest {
    @Test
    fun `group bucket rejects key swaps selector shortcuts and wrong overloads`() {
        val f = ImpactReviewedGroupFixture()
        val key =
            f.transfer.target.copy(
                valuePath =
                    f.base.bounded(
                        listOf(
                            ImpactValuePathSegmentDocument.MapEntryComponent(f.entry, ImpactMapEntrySlotDocument.KEY)
                        )
                    )
            )
        assertFalse(f.transfer.copy(target = key).admitsEvidence())
        assertFalse(
            f.transfer
                .copy(
                    source =
                        f.source.copy(
                            role = ImpactValueRoleDocument.Return,
                            range = f.base.range(50, 60),
                            executionOwner = ImpactExecutionOwnerDocument.ReviewedCallback(f.binding),
                        )
                )
                .admitsEvidence()
        )
        assertFalse(
            f.transfer
                .copy(
                    binding =
                        f.transfer.binding.copy(
                            authority =
                                f.binding.copy(
                                    rule = ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1
                                )
                        )
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `bucket encoding separates static placement and unknown execution`() {
        val f = ImpactReviewedGroupFixture()
        val encoded = Json.encodeToString(ImpactReviewedValueTransferDocument.serializer(), f.transfer)
        val bucket = Json.encodeToJsonElement(ImpactReviewedGroupBucketDocument.serializer(), f.entry).jsonObject
        assertEquals(
            setOf("callbacks", "placement", "selection", "sourceRange", "sourceExecution", "callStack"),
            bucket.keys,
        )
        assertEquals(
            "ORIGINAL_ELEMENTS",
            bucket.getValue("callbacks").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                ImpactReviewedValueTransferDocument.serializer(),
                encoded.replace("\"actualExecution\":\"NOT_ESTABLISHED\"", "\"actualExecution\":\"EXECUTED\""),
            )
        }
    }

    @Test
    fun `group authority rejects changed source spans helpers and callback positions`() {
        val f = ImpactReviewedGroupFixture()
        assertTrue(f.binding.admits(f.binding.enclosing))
        val artifacts = f.binding.artifacts as ImpactReviewedCollectionArtifactsDocument
        val section = artifacts.sections as ImpactReviewedCollectionSectionsDocument.GroupBy
        val changedSpan =
            artifacts.copy(
                sections =
                    section.copy(
                        groupByLines =
                            ImpactReviewedSourceLinesDocument(
                                f.base.range(1511, 1514).start,
                                f.base.range(1511, 1514).end,
                            )
                    )
            )
        assertFalse(f.binding.copy(artifacts = changedSpan).admits(f.binding.enclosing))
        assertFalse(
            f.binding
                .copy(artifacts = artifacts.copy(helpers = f.base.bounded(emptyList())))
                .admits(f.binding.enclosing)
        )
        val position = f.base.range(1, 2).start
        assertFalse(
            f.binding.copy(selected = f.binding.selected.copy(formalIndex = position)).admits(f.binding.enclosing)
        )
    }

    @Test
    fun `original element occupies a qualified value bucket with its suffix`() {
        val f = ImpactReviewedGroupFixture()
        assertTrue(f.transfer.admitsEvidence())
        assertEquals(ImpactValuePathSegmentDocument.Element, f.transfer.target.valuePath.values[1])
        assertEquals(f.source.valuePath.values.drop(1), f.transfer.target.valuePath.values.drop(2))
    }
}
