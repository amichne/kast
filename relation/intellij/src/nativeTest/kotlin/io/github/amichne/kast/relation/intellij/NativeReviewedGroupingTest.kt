package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import kotlinx.coroutines.runBlocking

class NativeReviewedGroupingTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-grouping-jdk"

    fun testGroupSelectorCannotBecomeSameTypedBucketElement() = runBlocking {
        val fixture = groupingFixture()
        val owner = owner("groupSelectorCannotBecomeBucketElement")
        val seed =
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = fixture.selection(owner, "scopePayload", declaration("scopePayload")),
                    model = fixture.model,
                )
        assertTrue("The selected key producer needs actual seed authority: $seed", seed is ValueProducerSeedRead.Seeded)
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        assertTrue(steps.isNotEmpty())
        assertFalse(
            "The key selector cannot become a grouped value even when both have the same type",
            steps.any { step ->
                step.source.range == destination.anchor &&
                    (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                        MapEntrySlot.VALUE
            },
        )
    }

    fun testSingleCallbackGroupPreservesSelectedElementAndField() {
        val fixture = groupingFixture()
        val owner = owner("singleCallbackGroupKeepsSelectedElement")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        val result = steps.singleOrNull { step ->
            step.source.range == destination.anchor && step.source.role == ValueRole.Return
        }
        assertNotNull("Missing original-element grouping result: ${steps.flatMap { it.obligations }}", result)
        val segments = checkNotNull(result).source.valuePath.segments
        assertEquals(MapEntrySlot.VALUE, (segments.first() as ValuePathSegment.MapEntryComponent).slot)
        assertEquals(listOf(ValuePathSegment.Element), segments.drop(1).dropLast(1))
        assertEquals("selected", (segments.last() as ValuePathSegment.Field).field.declaration.name.value)
        assertTrue(steps.flatMap { it.reviewedTransfers }.any { it.binding.target.name.value == "groupBy" })
    }

    private fun groupingFixture() = fixture(paths = listOf(CONTROLS, GROUPING_CONTROLS))

    private fun owner(name: String) = PacketDeclaration(GROUPING_CONTROLS, "", name)

    private companion object {
        const val GROUPING_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedGroupingControls.kt"
    }
}
