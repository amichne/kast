package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole

class NativeReviewedMapPrefixTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-map-prefix-jdk"

    fun testFirstComponentCannotSupplySecondComponentValue() {
        val fixture = fixture(paths = listOf(CONTROLS, PREFIX_CONTROLS))
        val owner = PacketDeclaration(PREFIX_CONTROLS, "", "selectedKeyWithBothComponents")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val input = fixture.markedExpression(owner, "input")
        val destination = fixture.markedExpression(owner, "destination")
        assertTrue(
            steps.any { step ->
                step.source.range == input.anchor &&
                    (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                        MapEntrySlot.KEY
            }
        )
        assertFalse(
            "The selected key cannot become the second component's returned value",
            steps.any { step ->
                step.source.range == destination.anchor &&
                    step.source.role == ValueRole.Return &&
                    (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                        MapEntrySlot.VALUE
            },
        )
    }

    fun testSecondComponentKeepsNormalPrefixAndSelectedValue() {
        val fixture = fixture(paths = listOf(CONTROLS, PREFIX_CONTROLS))
        val owner = PacketDeclaration(PREFIX_CONTROLS, "", "selectedValueWithBothComponents")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        val result = steps.singleOrNull { step ->
            step.source.range == destination.anchor &&
                step.source.role == ValueRole.Return &&
                (step.source.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                    MapEntrySlot.VALUE
        }
        assertNotNull(
            "Missing second-component route: " + steps.joinToString("\n", transform = ::routeEvidence),
            result,
        )
        assertTrue(
            "The first invoked component needs its own normal-prefix premise",
            checkNotNull(result).source.conditions.facts.any { it is ValueCondition.ReviewedEntryPrefixNormal },
        )
        val unresolved =
            steps
                .flatMap { it.obligations }
                .filter { it.cause == ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY }
                .mapNotNull { it.evidence as? ValueFlowObligationEvidence.Expression }
                .map { it.range.startInclusive to it.range.endExclusive }
                .toSet()
        // Independent offsets in the frozen fixture identify both invoked component declarations.
        assertTrue(
            "Both unproved getters must remain obligations: $unresolved",
            unresolved.containsAll(setOf(KEY_START to KEY_END, VALUE_START to VALUE_END)),
        )
    }

    private companion object {
        const val PREFIX_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedMapPrefixControls.kt"
        const val KEY_START = 565
        const val KEY_END = 568
        const val VALUE_START = 570
        const val VALUE_END = 575
    }
}

private fun routeEvidence(step: ValueFlowStep): String =
    listOf(
            "range=${step.source.range}",
            "role=${step.source.role}",
            "owner=${step.source.executionOwner.context.javaClass.simpleName}",
            "path=${step.source.valuePath.segments}",
            "reviewed=${step.reviewedTransfers.map { it.relation.javaClass.simpleName to it.target.range }}",
            "obligations=${step.obligations}",
        )
        .joinToString(" ")
