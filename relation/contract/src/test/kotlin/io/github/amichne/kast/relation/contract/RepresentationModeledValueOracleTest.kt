package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Assumed issued metadata isolates provenance decisions. No case proves native model selection or runtime reachability.
 */
class RepresentationModeledValueOracleTest {
    @Test
    fun `automatic element selection weakens known representation without claiming identity`() {
        val case = ModeledValueProvenanceOracleCase()
        val application = case.application()
        val before = case.known.branches.single()
        val result = case.known.modeledValue(application).flowRefined()
        assertEquals(case.origin.branches.single().current, before.current)
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            result.branches.single().current,
        )
        assertSame(application.target, result.site)
        assertEquals(application.source.predicates, result.site.predicates)
        assertEquals(application.source.callStack, result.site.callStack)
        assertEquals(application.invocation.range, result.site.range)
        assertEquals(ValueRole.ExpressionResult, result.site.role)
        assertEquals(before, case.known.branches.single())
    }

    @Test
    fun `a prior unknown input reason survives automatic value selection`() {
        val case = ModeledValueProvenanceOracleCase()
        val before = case.unknownInput()
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.INPUT_STATE_NOT_ESTABLISHED),
            before.branches.single().current,
        )
        val result = before.modeledValue(case.application()).flowRefined()
        assertEquals(before.branches.single().current, result.branches.single().current)
        assertEquals(before.branches.single().history, result.branches.single().history.dropLast(1))
    }

    @Test
    fun `selected suffix and exact local condition survive the provenance projection`() {
        val case = ModeledValueProvenanceOracleCase()
        val path = case.model.suffix.prepend(ValuePathSegment.AnyElement).flowRefined()
        val source = case.model.receiver(path = path)
        val before = case.origin.unmodeled(source).flowRefined()
        val application = case.application(source)
        val result = before.modeledValue(application).flowRefined()
        assertEquals(case.model.suffix, result.site.valuePath)
        assertEquals(source.predicates, result.site.predicates)
        assertEquals(source.callStack, result.site.callStack)
        assertEquals(source.executionOwner, result.site.executionOwner)
        val history = result.branches.single().history.last() as ValueModelApplication.CollectionSelection
        assertSame(application, history)
        assertEquals(application.target.identity, history.site)
        assertEquals(application.condition, history.condition)
        assertEquals(0, history.condition.index.value)
        assertEquals(ValueAccessPath.Whole, history.condition.receiver.valuePath)
        assertEquals(ValueAccessPath.Whole, history.condition.result.valuePath)
    }

    @Test
    fun `automatic application stays distinct from compiler flow and supplied representation rules`() {
        val case = ModeledValueProvenanceOracleCase()
        val application = case.application()
        val result = case.known.modeledValue(application).flowRefined()
        val history = result.branches.single().history
        assertEquals(case.known.branches.single().history, history.dropLast(1))
        assertSame(application, history.last())
        assertEquals(
            listOf(RepresentationHistory.CompilerTransfer(case.receiverTransfer)),
            history.filterIsInstance<RepresentationHistory.CompilerTransfer>(),
        )
        assertEquals(
            listOf(case.origin.branches.single().history.single(), application),
            history.filterIsInstance<RepresentationHistory.Model>(),
        )
        assertEquals(
            emptyList<RepresentationModelApplication>(),
            history.filterIsInstance<RepresentationModelApplication>(),
        )
        assertEquals(
            ReviewedValueModel.FirstOrNull.reference,
            (history.last() as RepresentationHistory.Model).reference,
        )
    }

    @Test
    fun `another invocation at the same receiver range cannot borrow its provenance`() {
        val case = ModeledValueProvenanceOracleCase()
        val other = case.model.receiver(call = case.model.call(start = 45))
        assertEquals(case.receiver.range, other.range)
        assertEquals(
            Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH),
            case.known.modeledValue(case.application(other)),
        )
    }

    @Test
    fun `a stale application rejects with its basis mismatch`() {
        val case = ModeledValueProvenanceOracleCase()
        val stale = ModeledValueProvenanceOracleCase(generation = 2)
        assertEquals(
            Refinement.Rejected(RepresentationPropagationFailure.BASIS_MISMATCH),
            case.known.modeledValue(stale.application()),
        )
    }

    @Test
    fun `the same location without its exact predicates cannot borrow guarded provenance`() {
        val case = ModeledValueProvenanceOracleCase()
        val unguarded = case.receiver.withState(predicates = ValuePredicates.Empty).flowRefined()
        assertEquals(case.receiver.range, unguarded.range)
        assertEquals(
            Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH),
            case.known.modeledValue(case.application(unguarded)),
        )
    }
}
