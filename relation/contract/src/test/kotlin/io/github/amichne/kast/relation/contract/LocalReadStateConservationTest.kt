package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalReadStateConservationTest {
    @Test
    fun `a resumed reference scan preserves its exact field and predicates`() {
        val owner = flowEndpoint("owner", 0, 200)
        val path = flowPath(ValuePathSegment.Field(flowField("Request.destination", 300, 320)))
        val binding = ValueSite.fromCompiler(owner, flowRange(10, 20), ValueRole.LocalBinding).flowRefined()
        val predicates =
            ValuePredicates.fromCompiler(
                    listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(binding)))
                )
                .flowRefined()
        val source = binding.withState(valuePath = path, predicates = predicates).flowRefined()
        val read =
            ValueSite.fromCompiler(
                    owner,
                    flowRange(30, 40),
                    ValueRole.LocalRead,
                    valuePath = path,
                    predicates = predicates,
                )
                .flowRefined()
        assertTrue(remainder(source, read) is Refinement.Refined)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            remainder(source, read.withState(valuePath = ValueAccessPath.Whole).flowRefined()),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            remainder(source, read.withState(predicates = ValuePredicates.Empty).flowRefined()),
        )
    }

    private fun remainder(source: ValueSite, read: ValueSite) =
        LocalBindingReadRemainder.admit(
            source,
            RelationSearchBoundary.WORKSPACE_EXPANSION,
            emptySet(),
            setOf(read.identity),
        )
}
