package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ReviewedEntrySelection
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole

class NativeReviewedWholeMapTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-whole-map-jdk"

    fun testConstantCallbackCannotAcquireWholeMapValueContent() {
        val fixture = fixture(paths = listOf(CONTROLS, WHOLE_CONTROLS))
        val host = owner("wholeMapConstantValue")
        val steps =
            trace(fixture = fixture, owner = host, producer = owner("wholeMapSource"), budget = composedBudget(fixture))
        val input = fixture.markedExpression(host, "input")
        assertTrue(steps.any { it.source.range == input.anchor && it.source.valuePath == ValueAccessPath.Whole })
        assertTrue(
            "The rejected result needs an admitted VALUE input branch",
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    val relation = transfer.relation
                    relation is ReviewedValueRelation.EntryInput &&
                        relation.slot == MapEntrySlot.VALUE &&
                        relation.selection == ReviewedEntrySelection.CONTENT
                },
        )
        val destination = fixture.markedExpression(host, "destination")
        assertFalse(
            steps.any { step ->
                step.source.range == destination.anchor &&
                    step.source.role == ValueRole.Return &&
                    (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                        MapEntrySlot.VALUE
            }
        )
    }

    fun testWholeMapValueContentCrossesTheActualEntryRead() {
        val fixture = fixture(paths = listOf(CONTROLS, WHOLE_CONTROLS))
        val host = owner("wholeMapValueContent")
        val steps =
            trace(fixture = fixture, owner = host, producer = owner("wholeMapSource"), budget = composedBudget(fixture))
        val destination = fixture.markedExpression(host, "destination")
        val result = steps.singleOrNull { step ->
            step.source.range == destination.anchor &&
                step.source.role == ValueRole.Return &&
                (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                    MapEntrySlot.VALUE
        }
        assertNotNull("Whole-map value content must cross the actual callback body", result)
        val read = fixture.markedExpression(host, "entry-read")
        assertTrue(steps.any { it.source.range == read.anchor })
        assertEquals(1, checkNotNull(result).source.valuePath.depth)
        val mapped =
            steps
                .flatMap { it.reviewedTransfers }
                .map { it.relation }
                .filterIsInstance<ReviewedValueRelation.MappedValue>()
                .single()
        assertEquals(ReviewedEntrySelection.CONTENT, mapped.selection)
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.source.valuePath == ValueAccessPath.Whole &&
                        transfer.target.role is ValueRole.Formal &&
                        transfer.target.valuePath != ValueAccessPath.Whole
                }
        )
    }

    fun testWholeMapKeysRemainContainedContent() {
        val fixture = fixture(paths = listOf(CONTROLS, WHOLE_CONTROLS))
        val host = owner("wholeMapConstantValue")
        val steps =
            trace(fixture = fixture, owner = host, producer = owner("wholeMapSource"), budget = composedBudget(fixture))
        val destination = fixture.markedExpression(host, "destination")
        val result = steps.singleOrNull { step ->
            step.source.range == destination.anchor &&
                step.source.role == ValueRole.Return &&
                (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                    MapEntrySlot.KEY
        }
        assertNotNull("A constant value callback still preserves conditional key content", result)
        assertEquals(1, checkNotNull(result).source.valuePath.depth)
        assertTrue(result.source.conditions.facts.isNotEmpty())
        val preserved =
            steps
                .flatMap { it.reviewedTransfers }
                .map { it.relation }
                .filterIsInstance<ReviewedValueRelation.PreservedKey>()
                .single()
        assertEquals(ReviewedEntrySelection.CONTENT, preserved.selection)
    }

    private fun owner(name: String) = PacketDeclaration(WHOLE_CONTROLS, "", name)

    private companion object {
        const val WHOLE_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedWholeMapControls.kt"
    }
}
