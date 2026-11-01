package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite

class NativeReviewedMapEntryInputTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-map-entry-input-jdk"

    fun testExplicitValueReadCannotExchangeSameTypedKey() {
        assertForbiddenValue("selectedKeyWithExplicitValue", MapEntrySlot.KEY)
    }

    fun testExplicitKeyReadCannotAcquireSameTypedValue() {
        assertForbiddenValue("selectedValueWithExplicitKey", MapEntrySlot.VALUE)
    }

    fun testDestructuredValueCannotExchangeSameTypedKey() {
        assertForbiddenValue("selectedKeyWithDestructuredValue", MapEntrySlot.KEY)
    }

    fun testIgnoredEntryCannotSupplyMappedValue() {
        assertForbiddenValue("selectedValueWithIgnoredEntry", MapEntrySlot.VALUE)
    }

    fun testExplicitValueReadPreservesSelectedInputValue() {
        assertActualEntryRead("selectedValueWithExplicitValue", MapEntrySlot.VALUE)
    }

    fun testDestructuredValueReadPreservesSelectedInputValue() {
        assertActualEntryRead("selectedValueWithDestructuredValue", MapEntrySlot.VALUE)
    }

    fun testExplicitKeyReadCanSupplyMappedValueThroughTheBody() {
        assertActualEntryRead("selectedKeyWithExplicitKey", MapEntrySlot.KEY)
    }

    fun testMapValuesPreservesTheSelectedInputKeySeparately() {
        val route = route("selectedKeyWithExplicitValue", MapEntrySlot.KEY)
        val result = output(route, MapEntrySlot.KEY)
        assertNotNull("The exact original key must reach the transformed map key", result)
        assertEquals(1, checkNotNull(result).valuePath.depth)
        assertTrue(route.input.conditions.facts.all { it in result.conditions.facts })
        assertTrue(
            "Preserved key correspondence must start at the actual receiver",
            route.steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == "mapValues" &&
                        transfer.source.role is ValueRole.Receiver &&
                        (transfer.target.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)
                            ?.slot == MapEntrySlot.KEY
                },
        )
    }

    private fun assertForbiddenValue(name: String, inputSlot: MapEntrySlot) {
        val route = route(name, inputSlot)
        assertNull(
            "The selected $inputSlot cannot acquire the forbidden mapped VALUE",
            output(route, MapEntrySlot.VALUE),
        )
    }

    private fun assertActualEntryRead(name: String, inputSlot: MapEntrySlot) {
        val route = route(name, inputSlot)
        val read = route.fixture.markedExpression(route.owner, "entry-read")
        assertTrue(
            "The actual entry property or destructured reference must occur on the selected route",
            route.steps.any { step -> step.source.range == read.anchor },
        )
        val result = output(route, MapEntrySlot.VALUE)
        assertNotNull("Missing selected mapped VALUE after the actual body read", result)
        assertEquals(1, checkNotNull(result).valuePath.depth)
        assertTrue(
            route.steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == "mapValues" && transfer.source.role == ValueRole.Return
                }
        )
    }

    private fun route(name: String, inputSlot: MapEntrySlot): MapEntryInputRoute {
        val fixture = fixture(paths = listOf(CONTROLS, INPUT_CONTROLS))
        val owner = PacketDeclaration(INPUT_CONTROLS, "", name)
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val input = fixture.markedExpression(owner, "input")
        val original =
            steps
                .flatMap { it.reviewedTransfers }
                .single { transfer ->
                    transfer.binding.target.name.value == "associate" && transfer.target.range == input.anchor
                }
                .target
        assertEquals(inputSlot, (original.valuePath.segments.single() as ValuePathSegment.MapEntryComponent).slot)
        return MapEntryInputRoute(fixture = fixture, owner = owner, steps = steps, input = original)
    }

    private fun output(route: MapEntryInputRoute, slot: MapEntrySlot): ValueSite? {
        val destination = route.fixture.markedExpression(route.owner, "destination")
        return route.steps
            .singleOrNull { step ->
                step.source.range == destination.anchor &&
                    step.source.role == ValueRole.Return &&
                    (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot == slot
            }
            ?.source
    }

    private companion object {
        const val INPUT_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedMapInputControls.kt"
    }
}

private data class MapEntryInputRoute(
    val fixture: NativePacketValueFixture,
    val owner: PacketDeclaration,
    val steps: List<ValueFlowStep>,
    val input: ValueSite,
)
