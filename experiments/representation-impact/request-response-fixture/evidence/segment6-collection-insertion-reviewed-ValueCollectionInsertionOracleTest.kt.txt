package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueCollectionInsertionOracleTest {
    @Test
    fun `single element review pins the fixed overload rule and exact reviewed artifact`() {
        val model = ReviewedValueModel.SingleElementListOf
        assertEquals("collections.listOf.insert-zero", model.reference.rule.value)
        assertEquals(SingleListModelOracleCase.signature(), model.callable)
        assertEquals(ReviewedModelOracleCase.BINARY_SHA, model.artifact.binaryDigest.value)
        assertEquals(ReviewedModelOracleCase.SOURCE_SHA, model.artifact.sourceDigest.value)
        assertEquals(ReviewedModelOracleCase.VERSION, model.artifact.version.value)
        assertEquals("source-sha256:" + ReviewedModelOracleCase.SOURCE_SHA, model.reference.model.provenance.value)
    }

    @Test
    fun `whole actual argument zero supplies only the exact result element zero`() {
        val case = SingleListModelOracleCase()
        assertInsertion(case, case.argument())
    }

    @Test
    fun `insertion keeps the incoming resolved field suffix after element zero`() {
        val case = SingleListModelOracleCase()
        assertInsertion(case, case.argument(path = case.suffix))
    }

    @Test
    fun `seven incoming segments permit the eighth insertion segment`() {
        val case = SingleListModelOracleCase()
        val path = ValueAccessPath.fromSegments(List(7) { ValuePathSegment.AnyElement }).flowRefined()
        val inserted = assertInsertion(case, case.argument(path = path))
        assertEquals(8, inserted.target.valuePath.depth)
    }

    @Test
    fun `eight incoming segments preserve an exact depth refusal without truncation`() {
        val case = SingleListModelOracleCase()
        val path = ValueAccessPath.fromSegments(List(8) { ValuePathSegment.AnyElement }).flowRefined()
        val source = case.argument(path = path)
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.ACCESS_PATH_DEPTH_EXCEEDED),
            ValueModelApplication.CollectionInsertion.insertSingle(source, case.binding()),
        )
        assertEquals(8, source.valuePath.depth)
        assertSame(path, source.valuePath)
    }

    @Test
    fun `an expression result cannot impersonate the fixed actual argument`() {
        val case = SingleListModelOracleCase()
        val source =
            case.invocation.resultSite().withState(callStack = case.stack, predicates = case.predicates).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.CollectionInsertion.insertSingle(source, case.binding()),
        )
    }

    @Test
    fun `argument one for the exact fixed overload rejects before model application`() {
        val case = SingleListModelOracleCase()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVALID_ARGUMENT_POSITION),
            ValueSite.fromCompiler(
                enclosing = case.host,
                range = flowRange(46, 50),
                role = ValueRole.Argument(case.invocation, ValueArgumentPosition.parse(1).flowRefined()),
                executionOwner = case.execution,
                callStack = case.stack,
                predicates = case.predicates,
            ),
        )
    }

    @Test
    fun `insertion preserves an existing exact nonnull predicate without adding another fact`() {
        val case = SingleListModelOracleCase()
        val base = case.argument()
        val fact = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(base))
        val predicates = base.predicates.plus(listOf(fact)).flowRefined()
        val source = base.withState(predicates = predicates).flowRefined()
        val inserted = assertInsertion(case, source)
        assertEquals(listOf(fact), inserted.target.predicates.entries.filterIsInstance<ValuePredicate.NonNull>())
        assertEquals(source.predicates.entries, inserted.target.predicates.entries)
    }

    private fun assertInsertion(
        case: SingleListModelOracleCase,
        source: ValueSite,
    ): ValueModelApplication.CollectionInsertion {
        val binding = case.binding()
        val inserted = ValueModelApplication.CollectionInsertion.insertSingle(source, binding).flowRefined()
        val element = ValuePathSegment.Element(ValueElementIndex.parse(0).flowRefined())
        val expected =
            ValueSite.fromCompiler(
                    enclosing = case.host,
                    range = case.invocation.range,
                    role = ValueRole.ExpressionResult,
                    executionOwner = case.execution,
                    valuePath = ValueAccessPath.fromSegments(listOf(element) + source.valuePath.segments).flowRefined(),
                    callStack = source.callStack,
                    predicates = source.predicates,
                )
                .flowRefined()
        assertSame(source, inserted.source)
        assertSame(binding, inserted.binding)
        assertSame(case.invocation, inserted.invocation)
        assertEquals(0, inserted.index.value)
        assertEquals(expected, inserted.target)
        assertEquals(ReviewedValueModel.SingleElementListOf.reference, inserted.reference)
        return inserted
    }
}
