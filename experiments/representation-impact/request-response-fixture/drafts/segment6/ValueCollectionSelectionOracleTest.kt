package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Assumed issued metadata exercises the reviewed decision. These cases establish no native model application. */
class ValueCollectionSelectionOracleTest {
    @Test
    fun `whole list selects a conditional whole result with exact callback context`() {
        val case = ReviewedModelOracleCase()
        assertSelection(case, case.receiver(), ValueAccessPath.Whole)
    }

    @Test
    fun `element zero selects its exact field suffix`() {
        val case = ReviewedModelOracleCase()
        val path = case.suffix.prepend(element(0)).flowRefined()
        assertSelection(case, case.receiver(path = path), case.suffix)
    }

    @Test
    fun `abstract element selects zero condition without a claim about every element`() {
        val case = ReviewedModelOracleCase()
        val path = case.suffix.prepend(ValuePathSegment.AnyElement).flowRefined()
        val selected = assertSelection(case, case.receiver(path = path), case.suffix)
        assertEquals(0, selected.condition.index.value)
    }

    @Test
    fun `element one does not reach the first result`() {
        val case = ReviewedModelOracleCase()
        val source = case.receiver(path = case.suffix.prepend(element(1)).flowRefined())
        assertRejected(case, source, ValueModelApplicationFailure.PATH_UNSELECTED)
    }

    @Test
    fun `a different leading field cannot masquerade as an element`() {
        val case = ReviewedModelOracleCase()
        assertRejected(case, case.receiver(path = case.suffix), ValueModelApplicationFailure.PATH_UNSELECTED)
    }

    @Test
    fun `dispatch slot cannot borrow the extension selection`() {
        val case = ReviewedModelOracleCase()
        assertRejected(
            case,
            case.receiver(kind = ValueReceiverKind.DISPATCH),
            ValueModelApplicationFailure.RECEIVER_KIND_MISMATCH,
        )
    }

    @Test
    fun `the result role cannot masquerade as the list receiver`() {
        val case = ReviewedModelOracleCase()
        val source =
            ValueSite.fromCompiler(
                    enclosing = case.host,
                    range = case.invocation.range,
                    role = ValueRole.ExpressionResult,
                    executionOwner = case.execution,
                    callStack = case.stack,
                    predicates = case.predicates,
                )
                .flowRefined()
        assertRejected(case, source, ValueModelApplicationFailure.SOURCE_MISMATCH)
    }

    @Test
    fun `another occurrence of the same callable cannot borrow the invocation binding`() {
        val case = ReviewedModelOracleCase()
        val source = case.receiver(call = case.call(start = 45))
        assertRejected(case, source, ValueModelApplicationFailure.SOURCE_MISMATCH)
    }

    @Test
    fun `a stale receiver cannot borrow a fresh binding`() {
        val case = ReviewedModelOracleCase()
        val stale = ReviewedModelOracleCase(generation = 2)
        assertRejected(case, stale.receiver(), ValueModelApplicationFailure.BASIS_MISMATCH)
    }

    @Test
    fun `selection keeps an existing exact nonnull subject without inventing a result fact`() {
        val case = ReviewedModelOracleCase()
        val source = case.receiver()
        val fact = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(source))
        val guarded = source.withState(predicates = source.predicates.plus(listOf(fact)).flowRefined()).flowRefined()
        val selected = assertSelection(case, guarded, ValueAccessPath.Whole)
        assertEquals(listOf(fact), selected.target.predicates.entries.filterIsInstance<ValuePredicate.NonNull>())
        assertEquals(source, fact.subject.occurrence.withState(predicates = source.predicates).flowRefined())
    }

    private fun assertSelection(
        case: ReviewedModelOracleCase,
        source: ValueSite,
        suffix: ValueAccessPath,
    ): ValueModelApplication.CollectionSelection {
        val binding = case.binding()
        val read = ValueModelApplication.CollectionSelection.selectFirst(source, binding)
        val status =
            when (read) {
                is Refinement.Refined -> "REFINED"
                is Refinement.Rejected -> read.failure.name
            }
        assertTrue(read is Refinement.Refined, "The reviewed zero selection must apply: $status")
        val selected = (read as Refinement.Refined).value
        val expected =
            ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = case.invocation.range,
                    role = ValueRole.ExpressionResult,
                    executionOwner = source.executionOwner,
                    valuePath = suffix,
                    callStack = source.callStack,
                    predicates = source.predicates,
                )
                .flowRefined()
        assertSame(source, selected.source)
        assertSame(binding, selected.binding)
        assertSame(case.invocation, selected.invocation)
        assertEquals(ReviewedValueModel.FirstOrNull.reference, selected.reference)
        assertEquals(expected, selected.target)
        assertEquals(source.withState(valuePath = ValueAccessPath.Whole).flowRefined(), selected.condition.receiver)
        assertEquals(expected.withState(valuePath = ValueAccessPath.Whole).flowRefined(), selected.condition.result)
        assertEquals(0, selected.condition.index.value)
        assertEquals(source.predicates, selected.target.predicates)
        return selected
    }

    private fun assertRejected(
        case: ReviewedModelOracleCase,
        source: ValueSite,
        failure: ValueModelApplicationFailure,
    ) {
        val originalPath = source.valuePath
        val originalPredicates = source.predicates
        assertEquals(
            Refinement.Rejected(failure),
            ValueModelApplication.CollectionSelection.selectFirst(source, case.binding()),
        )
        assertEquals(originalPath, source.valuePath)
        assertEquals(originalPredicates, source.predicates)
    }

    private fun element(index: Int) = ValuePathSegment.Element(ValueElementIndex.parse(index).flowRefined())
}
