package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.ValueAccessPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Draft outside the active source set. Every route comes from the actual detached observation under examination. */
class QueryImpactAutomaticModelConservationTest {
    @Test
    fun `automatic application and native sibling remain independent of supplied models`() {
        val f = QueryImpactAutomaticModelFixture()
        val ledger = f.ledger().modelRefined()
        assertTrue(ledger.representationModels.isEmpty())
        assertEquals(3, ledger.paths.size)
        assertTrue(ledger.paths.all { it.representation == QueryImpactRepresentation.NotModeled })
        val application = (ledger.paths[0].steps[1] as QueryImpactStep.ModeledValue).application
        assertEquals(f.application, application)
        assertEquals(f.invocation, application.invocation)
        assertEquals(f.artifact, application.binding.artifact)
        assertEquals(ValueAccessPath.Whole, f.application.condition.receiver.valuePath)
        assertEquals(ValueAccessPath.Whole, f.application.condition.result.valuePath)
        assertEquals(0, f.application.condition.index.value)
        assertEquals(setOf(QueryImpactRequiredObligation.NATIVE_FLOW),
            (ledger.closure as QueryImpactClosure.Unresolved).required)
    }

    @Test
    fun `a missing application route cannot borrow its native sibling`() {
        val f = QueryImpactAutomaticModelFixture()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            f.ledger(paths = listOf(f.nativePath, f.obligationPath)))
    }

    @Test
    fun `an application route cannot borrow a missing native sibling`() {
        val f = QueryImpactAutomaticModelFixture()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            f.ledger(paths = listOf(f.appPath, f.obligationPath)))
    }

    @Test
    fun `an automatic application cannot erase an external obligation`() {
        val f = QueryImpactAutomaticModelFixture()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            f.ledger(paths = listOf(f.appPath, f.nativePath)))
    }

    @Test
    fun `an application from another observed module cannot borrow the same call`() {
        val f = QueryImpactAutomaticModelFixture()
        val other = f.application(f.artifact.copy(moduleName = LibraryModelModuleName.parse("another-module").modelRefined()))
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_MODELED_VALUE_STEP),
            f.ledger(receiverObservation = f.observedReceiver(applications = listOf(other))))
    }

    @Test
    fun `an application-bearing observation does not establish a supported domain end`() {
        val f = QueryImpactAutomaticModelFixture()
        val observed = f.observedReceiver(edges = emptyList(), obligations = emptyList())
        assertEquals(Refinement.Rejected(QueryImpactPathFailure.TERMINAL_UNPROVEN),
            QueryImpactTerminal.SupportedDomainEnd.admit(observed))
    }
}
