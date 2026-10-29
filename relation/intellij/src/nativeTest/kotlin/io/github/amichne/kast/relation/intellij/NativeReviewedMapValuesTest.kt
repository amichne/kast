package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class NativeReviewedMapValuesTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-map-values-jdk"

    fun testDiscardedProducerCannotBecomeMappedValue() {
        assertExcluded("mapValueDiscardedProducer")
    }

    fun testSiblingMapCallbackCannotReceiveSelectedProducer() {
        assertExcluded("mapValueSiblingOwnerCannotReceiveProducer", "sibling-map")
    }

    fun testReturnedProducerKeepsMapValueAndNestedField() {
        val fixture = mapFixture()
        val owner = owner("mapValueReturnedProducer")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertMapValueResult(fixture, owner, steps, destination = expressionBodyRange(owner))
    }

    private fun expressionBodyRange(owner: PacketDeclaration): ExactDeclarationTextRange =
        ReadAction.compute<ExactDeclarationTextRange, RuntimeException> {
            val file =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/ReviewedMapValueControls.kt")
                )
            val function =
                (psiManager.findFile(file) as KtFile).declarations.filterIsInstance<KtNamedFunction>().single {
                    it.name == owner.name
                }
            range(checkNotNull(function.bodyExpression))
        }

    fun testNestedMapValueResultKeepsItsActualLetParent() {
        val fixture = mapFixture()
        val owner = owner("mapValueNestedReturn")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val mapped = fixture.markedExpression(owner, "mapped")
        val result = steps.singleOrNull {
            it.source.range == mapped.anchor && it.source.role == ValueRole.ExpressionResult
        }
        assertNotNull("The selected producer must reach the actual nested map result", result)
        val parent = checkNotNull(result).source.executionOwner.context
        assertTrue(
            "The nested result must keep its declared let parent",
            parent is ValueExecutionContext.DeclaredCallback,
        )
        assertEquals(
            fixture.markedExpression(owner, "parent-body").anchor,
            (parent as ValueExecutionContext.DeclaredCallback).binding.body.range,
        )
        assertMapValueResult(fixture, owner, steps, destination = expressionBodyRange(owner))
    }

    private fun assertExcluded(name: String, forbiddenMarker: String = "destination") = runBlocking {
        val fixture = mapFixture()
        val owner = owner(name)
        val seed =
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = fixture.selection(owner, "scopePayload", declaration("scopePayload")),
                    model = fixture.model,
                )
        assertTrue(
            "Native seed must admit the exact producer or reject its callback owner: $seed",
            seed is ValueProducerSeedRead.Seeded,
        )
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val forbidden = fixture.markedExpression(owner, forbiddenMarker)
        val destination = fixture.markedExpression(owner, "destination")
        assertTrue("The selected producer must traverse its actual body", steps.isNotEmpty())
        assertFalse(
            "Discarded producer reached the forbidden sibling or result",
            steps.any { it.source.range == forbidden.anchor },
        )
        assertFalse(steps.any { it.source.range == destination.anchor })
    }

    private fun mapFixture() = fixture(paths = listOf(CONTROLS, MAP_VALUE_CONTROLS))

    private fun owner(name: String) = PacketDeclaration(MAP_VALUE_CONTROLS, "", name)

    private companion object {
        const val MAP_VALUE_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedMapValueControls.kt"
    }
}

private fun assertMapValueResult(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
    destination: ExactDeclarationTextRange = fixture.markedExpression(owner, "destination").anchor,
) {
    val result = steps.singleOrNull { it.source.range == destination && it.source.role == ValueRole.Return }
    assertNotNull("Missing exact mapValues return: ${steps.flatMap { it.obligations }}", result)
    assertSelectedMapValue(checkNotNull(result).source)
    assertTrue(
        "The actual mapValues callback return must supply the map value",
        steps
            .flatMap { it.reviewedTransfers }
            .any { transfer ->
                transfer.binding.target.name.value == "mapValues" &&
                    transfer.source.role == ValueRole.Return &&
                    (transfer.target.valuePath.segments.firstOrNull() as? ValuePathSegment.MapEntryComponent)?.slot ==
                        MapEntrySlot.VALUE
            },
    )
}

private fun assertSelectedMapValue(site: ValueSite) {
    val segments = site.valuePath.segments
    assertEquals(2, segments.size)
    assertEquals(MapEntrySlot.VALUE, (segments.first() as ValuePathSegment.MapEntryComponent).slot)
    assertEquals(
        "selected",
        (segments.last() as ValuePathSegment.Field).field.declaration.name.value,
    )
}
