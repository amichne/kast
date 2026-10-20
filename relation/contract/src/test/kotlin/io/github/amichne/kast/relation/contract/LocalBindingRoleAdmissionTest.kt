package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Fresh implicit-role admission runs even when its body contains no references. */
class LocalBindingRoleAdmissionTest {
    @Test
    fun `rejected role records its exact cause without enumerating candidates`() {
        var enumerations = 0
        val read =
            scan(
                enumerate = { enumerations++ },
                admitRole = { Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE) },
            )
        val observed = assertInstanceOf(ValueFlowRead.Observed::class.java, read)
        assertEquals(0, enumerations)
        assertEquals(ValueFlowTerminal.Unresolved, observed.step.terminal)
        assertEquals(listOf(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE), observed.step.obligations.map { it.cause })
        assertEquals(emptyList<ValueTransfer>(), observed.step.transfers)
        assertEquals(2L, observed.step.examinedWorkUnits.value)
    }

    @Test
    fun `role proof follows cancellation and deadline within its existing work unit`() {
        val events = mutableListOf<String>()
        var work = 0L
        val read =
            scan(
                enumerate = { events += "enumerate" },
                admitRole = {
                    assertEquals(listOf("cancellation", "deadline"), events)
                    assertEquals(2L, work)
                    events += "role"
                    Refinement.Refined(Unit)
                },
                checkCanceled = { events += "cancellation" },
                elapsedMillis = {
                    events += "deadline"
                    0L
                },
                onWork = { work = it.value },
            )
        val observed = assertInstanceOf(ValueFlowRead.Observed::class.java, read)
        assertEquals(listOf("role", "enumerate"), events.filter { it == "role" || it == "enumerate" })
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, observed.step.terminal)
        assertEquals(2L, observed.step.examinedWorkUnits.value)
    }

    private fun scan(
        enumerate: (LocalReferenceTraversal<Int>) -> Unit,
        admitRole: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        checkCanceled: () -> Unit = {},
        elapsedMillis: () -> Long = { 0L },
        onWork: (RelationWorkCount) -> Unit = {},
    ): ValueFlowRead {
        val budget =
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(32).flowRefined(),
                    WorkUnitLimit.parse(32L).flowRefined(),
                    ElapsedTimeLimitMillis.parse(10L).flowRefined(),
                ),
                RelationByteLimit.parse(1_000_000L).flowRefined(),
            )
        val source =
            ValueSite.fromCompiler(flowEndpoint("scan", 0, 100), flowRange(20, 30), ValueRole.LocalBinding)
                .flowRefined()
        val request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT)
        return LocalBindingReferenceScan<Int>(
                request = request,
                domain = RelationRequest.start(source.enclosing, RelationMeaning.References, budget, request.boundary),
                enumerate = enumerate,
                key = { error("Candidate-free role oracle emitted a reference") },
                confirm = { error("Candidate-free role oracle confirmed a reference") },
                checkCanceled = checkCanceled,
                elapsedMillis = elapsedMillis,
                onWork = onWork,
                admitRole = admitRole,
            )
            .read()
    }
}
