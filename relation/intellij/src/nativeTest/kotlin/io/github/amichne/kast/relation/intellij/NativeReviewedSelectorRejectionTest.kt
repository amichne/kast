package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole

class NativeReviewedSelectorRejectionTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-selector-rejection-jdk"

    fun testAdmittedSortSelectorReturnCannotBecomeOutputElement() {
        val fixture = fixture(paths = listOf(SELECTOR_CONTROLS))
        val owner = PacketDeclaration(SELECTOR_CONTROLS, "", "selectorResultCannotBecomeSortedElement")
        val producer = PacketDeclaration(SELECTOR_CONTROLS, "", "reviewedSelectorKey")
        val steps = trace(fixture, owner, producer)
        val selected = fixture.markedExpression(owner, "selector-result")
        val returned = steps.singleOrNull { it.source.range == selected.anchor && it.source.role == ValueRole.Return }
        assertNotNull("The selected producer must reach its actual selector return", returned)
        val step = checkNotNull(returned)
        val context = step.source.executionOwner.context
        assertTrue(
            "The compiler must admit the reviewed callback owner",
            context is ValueExecutionContext.ReviewedCallback,
        )
        assertEquals(
            ReviewedLibraryOperation.ITERABLE_SORTED_BY,
            (context as ValueExecutionContext.ReviewedCallback).binding.selected.operation,
        )
        assertTrue(
            "The actual selector return must preserve the precise rejection",
            step.obligations.any {
                it.site.identity == step.source.identity && it.cause == ValueFlowUnsupportedCause.UNSUPPORTED_RETURN
            },
        )
        assertTrue(step.reviewedTransfers.isEmpty())
        val destination = fixture.markedExpression(owner, "destination")
        assertFalse(steps.any { it.source.range == destination.anchor })
    }

    private companion object {
        const val SELECTOR_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedSelectorControls.kt"
    }
}
