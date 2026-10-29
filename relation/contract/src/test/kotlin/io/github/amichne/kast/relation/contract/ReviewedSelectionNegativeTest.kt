package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReviewedSelectionNegativeTest {
    @Test
    fun `predicate returns cannot supply filtered payload`() {
        val kept = Any()
        val removed = Any()
        val oracle = listOf(kept, removed).filter { it === kept }
        assertEquals(listOf(kept), oracle)
        assertFalse(removed in oracle)
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_FILTER)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(f.returned(), f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.filteredResult(f.returned(), f.binding),
        )
    }

    @Test
    fun `filter rejects field container inference and sorted correspondence`() {
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_FILTER)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.filteredResult(f.receiver(f.base.fieldPath), f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.sortedResult(f.receiver(), f.binding),
        )
    }

    @Test
    fun `constant false filter cannot lose its membership qualification`() {
        val original = listOf(Any(), Any())
        assertEquals(emptyList<Any>(), original.filter { false })
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_FILTER)
        val source = f.receiver()
        val result = ReviewedValueTransfer.filteredResult(source, f.binding).flowRefined()
        assertEquals(
            listOf(ValueCondition.ReviewedCollectionNormalResult::class, ReviewedPredicateAccepts::class),
            result.target.conditions.facts.map { it::class },
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.filteredResult(
                source.withState(conditions = result.target.conditions).flowRefined(),
                f.binding,
            ),
        )
    }

    @Test
    fun `ignored producer before null or constant cannot bypass callback body`() {
        val producer = Any()
        val constant = Any()
        val ignored = listOf(producer).mapNotNull { null }
        val constants = listOf(producer).mapNotNull { constant }
        assertEquals(emptyList<Any>(), ignored)
        assertFalse(producer in constants)
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_MAP_NOT_NULL)
        val input = ReviewedValueTransfer.callbackInput(f.receiver(), f.binding, f.formal()).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(input.target, f.binding),
        )
        val ignoredProducer =
            f.base.source(role = ValueRole.ExpressionResult, owner = ValueExecutionOwner.reviewedCallback(f.binding))
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(ignoredProducer, f.binding),
        )
    }

    @Test
    fun `nullable return premise rejects a foreign callback and receiver result shortcut`() {
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_MAP_NOT_NULL)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(f.base.source(), f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ReviewedCallbackResultNonNull.fromReviewed(f.receiver(), f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.filteredResult(f.receiver(), f.binding),
        )
    }
}
