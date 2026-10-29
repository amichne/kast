package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import kotlinx.coroutines.runBlocking

class NativeReviewedScopeReceiversTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-scope-receivers-jdk"

    fun testPlainRunDiscardsTheIgnoredProducer() = assertExcluded(owner("plainRunDiscardsProducer"))

    fun testReceiverRunDiscardsTheIgnoredProducer() = assertExcluded(owner("receiverRunDiscardsProducer"))

    fun testWithDiscardsTheIgnoredProducer() = assertExcluded(owner("withDiscardsProducer"))

    fun testThrowingRunCannotReturnTheProducer() = assertExcluded(owner("throwingRunCannotReturnProducer"))

    fun testThrowingApplyCannotReturnTheReceiver() = assertExcluded(owner("throwingApplyCannotReturnReceiver"))

    fun testNonLocalApplyCannotReturnTheReceiver() = assertExcluded(owner("nonLocalApplyCannotReturnReceiver"))

    fun testSameTypedExtensionReceiverCannotSupplyDispatchField() =
        assertExcluded(owner("extensionReceiverCannotBecomeDispatchField", SHADOW_OWNER))

    fun testPlainRunKeepsActualReturnedProducer() = assertReturned(owner("plainRunSelectedResult"), "run")

    fun testReceiverRunKeepsActualReturnedProducer() = assertReturned(owner("receiverRunSelectedResult"), "run")

    fun testWithKeepsActualReturnedProducer() = assertReturned(owner("withSelectedResult"), "with")

    fun testSameTypedExtensionReceiverSuppliesItsOwnField() {
        val fixture = scopeFixture()
        val owner = owner("extensionReceiverKeepsItsOwnField", SHADOW_OWNER)
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        val result = steps.singleOrNull { step ->
            step.source.range == destination.anchor && step.source.role == ValueRole.Return
        }
        assertNotNull("The actual extension receiver field must reach the run result", result)
        assertTrue(checkNotNull(result).source.valuePath.segments.isEmpty())
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == "run" && transfer.source.role == ValueRole.Return
                }
        )
    }

    private fun assertReturned(owner: PacketDeclaration, targetName: String) {
        val fixture = scopeFixture()
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        val result = steps.singleOrNull { step ->
            step.source.range == destination.anchor && step.source.role == ValueRole.Return
        }
        assertNotNull("Missing actual returned producer for ${owner.name}: ${steps.flatMap { it.obligations }}", result)
        val segment = checkNotNull(result).source.valuePath.segments.single() as ValuePathSegment.Field
        assertEquals("selected", segment.field.declaration.name.value)
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == targetName && transfer.source.role == ValueRole.Return
                }
        )
    }

    private fun assertExcluded(owner: PacketDeclaration) = runBlocking {
        val fixture = scopeFixture()
        val seed =
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = fixture.selection(owner, "scopePayload", declaration("scopePayload")),
                    model = fixture.model,
                )
        assertTrue("The selected producer needs actual seed authority: $seed", seed is ValueProducerSeedRead.Seeded)
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        assertTrue(steps.isNotEmpty())
        assertFalse(
            "Ignored or abrupt callback cannot supply ${owner.name}",
            steps.any { step ->
                step.source.range == destination.anchor && step.source.role == ValueRole.Return
            },
        )
    }

    private fun scopeFixture() = fixture(paths = listOf(CONTROLS, RECEIVER_CONTROLS))

    private fun owner(name: String, owner: String = "") = PacketDeclaration(RECEIVER_CONTROLS, owner, name)

    private companion object {
        const val RECEIVER_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedScopeReceiverControls.kt"
        const val SHADOW_OWNER = "ReviewedScopeShadow"
    }
}
