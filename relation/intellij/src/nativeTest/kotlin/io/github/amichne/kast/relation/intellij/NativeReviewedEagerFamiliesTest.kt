package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ReviewedValueSemantics
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.runBlocking

class NativeReviewedEagerFamiliesTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-eager-families-jdk"

    fun testPredicateResultCannotBecomeSameTypedElement() {
        assertExcluded("predicateReturnCannotBecomeElement", owner("reviewedPredicate"))
    }

    fun testNullReturnDiscardsMapNotNullProducer() {
        assertExcluded("nullResultDiscardsProducer")
    }

    fun testConstantReturnDiscardsMapNotNullProducer() {
        assertExcluded("constantNonNullDiscardsProducer")
    }

    fun testApplyCallbackResultCannotReplaceReceiver() {
        assertExcluded("applyCallbackCannotReplaceReceiver")
    }

    fun testTransformOverloadCannotShortcutOriginalInput() {
        val fixture = eagerFixture()
        val owner = owner("transformedGroupCannotAcquireOriginal")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertTrue(
            "The selected original must first enter the admitted map output",
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == "map" &&
                        transfer.target.valuePath.segments.firstOrNull() == ValuePathSegment.Element
                },
        )
        val destination = fixture.markedExpression(owner, "destination")
        assertFalse(
            "Original input cannot replace the actual transform result",
            steps.any {
                it.source.range == destination.anchor
            },
        )
    }

    fun testFilterKeepsSelectedElementAndNestedField() {
        val fixture = eagerFixture()
        val owner = owner("filteredSelectedContent")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertElementField(eagerResult(fixture, owner, steps))
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any {
                    it.binding.target.name.value == "filter" && it.source.role is ValueRole.Receiver
                }
        )
    }

    fun testMapNotNullKeepsActualReturnedElementAndField() {
        val fixture = eagerFixture()
        val owner = owner("nonNullSelectedContent")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertElementField(eagerResult(fixture, owner, steps))
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any {
                    it.binding.target.name.value == "mapNotNull" && it.source.role == ValueRole.Return
                }
        )
    }

    fun testApplyKeepsTheExactWholeReceiver() {
        val fixture = eagerFixture()
        val owner = owner("applyKeepsSelectedReceiver")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertTrue(eagerResult(fixture, owner, steps).valuePath.segments.isEmpty())
        val edge =
            steps
                .flatMap { it.reviewedTransfers }
                .single { transfer ->
                    transfer.binding.target.name.value == "apply" && transfer.target.role == ValueRole.ExpressionResult
                }
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == "apply" && transfer.target.role is ValueRole.Formal
                }
        )
        assertTrue(edge.source.role is ValueRole.Receiver)
        assertEquals(
            ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT,
            (edge.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
        )
        assertTrue(edge.source.valuePath.segments.isEmpty())
        assertTrue(edge.target.valuePath.segments.isEmpty())
    }

    fun testGroupTransformReturnKeepsBucketElementAndField() {
        val fixture = eagerFixture()
        val owner = owner("transformedGroupKeepsSelectedResult")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val result = eagerResult(fixture, owner, steps)
        val entry = result.valuePath.segments.first() as ValuePathSegment.MapEntryComponent
        assertEquals(MapEntrySlot.VALUE, entry.slot)
        assertEquals(listOf(ValuePathSegment.Element), result.valuePath.segments.drop(1).dropLast(1))
        assertSelectedField(result.valuePath.segments.last())
        assertTrue(
            "Only the actual transform callback return supplies grouped values",
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    val binding = transfer.binding as? ReviewedCallbackValueBinding
                    binding != null &&
                        binding.target.name.value == "groupBy" &&
                        binding.position.value == 1 &&
                        transfer.source.role == ValueRole.Return
                },
        )
    }

    private fun assertExcluded(name: String, producer: PacketDeclaration = declaration("scopePayload")) = runBlocking {
        val fixture = eagerFixture()
        val owner = owner(name)
        val seed =
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = fixture.selection(owner, producer.name, producer),
                    model = fixture.model,
                )
        assertTrue(
            "The controlled producer must have native seed authority: $seed",
            seed is ValueProducerSeedRead.Seeded,
        )
        val steps = trace(fixture = fixture, owner = owner, budget = composedBudget(fixture), producer = producer)
        assertTrue(steps.isNotEmpty())
        val destination = fixture.markedExpression(owner, "destination")
        assertFalse(
            "The discarded callback value cannot reach the output",
            steps.any {
                it.source.range == destination.anchor
            },
        )
    }

    private fun eagerFixture() = fixture(paths = listOf(CONTROLS, EAGER_CONTROLS))

    private fun owner(name: String) = PacketDeclaration(EAGER_CONTROLS, "", name)

    private companion object {
        const val EAGER_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedEagerFamilyControls.kt"
    }
}

private fun eagerResult(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
): ValueSite {
    val destination = fixture.markedExpression(owner, "destination")
    val result = steps.singleOrNull { it.source.range == destination.anchor && it.source.role == ValueRole.Return }
    assertNotNull(
        "Missing exact ${owner.name} return: ${steps.joinToString("\n", transform = ::eagerRouteEvidence)}",
        result,
    )
    return checkNotNull(result).source
}

private fun assertElementField(site: ValueSite) {
    assertEquals(2, site.valuePath.depth)
    assertEquals(ValuePathSegment.Element, site.valuePath.segments.first())
    assertSelectedField(site.valuePath.segments.last())
}

private fun assertSelectedField(segment: ValuePathSegment) {
    assertEquals("selected", (segment as ValuePathSegment.Field).field.declaration.name.value)
}

private fun eagerRouteEvidence(step: ValueFlowStep): String =
    listOf(
            "range=${step.source.range}",
            "role=${step.source.role}",
            "path=${step.source.valuePath.segments}",
            "reviewed=${step.reviewedTransfers.map { it.relation.javaClass.simpleName to it.target.range }}",
            "compiler=${step.transfers.map { it.kind to it.target.range }}",
            "obligations=${step.obligations.map { it.cause to it.site.range }}",
        )
        .joinToString(" ")
