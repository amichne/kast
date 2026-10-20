package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Candidate-free traversal must preserve the original semantic deadline and cancellation. */
class LocalBindingTraversalAdmissionTest {
    @Test
    fun `candidate-free enumeration that reaches the deadline remains suspended`() {
        var elapsed = 0L
        val read = scan(enumerate = { _ -> elapsed = DEADLINE }, elapsedMillis = { elapsed })
        val suspended = assertInstanceOf(ValueFlowRead.Suspended::class.java, read)
        assertEquals(ValueFlowSuspensionCause.TIME_LIMIT_REACHED, suspended.cause)
        assertEquals(ValueFlowTerminal.ResourceSuspended, suspended.step.terminal)
        assertEquals(emptyList<ValueTransfer>(), suspended.step.transfers)
        assertEquals(emptyList<ValueFlowObligation>(), suspended.step.obligations)
        assertEquals(2L, suspended.step.examinedWorkUnits.value)
    }

    @Test
    fun `candidate-free enumeration propagates cancellation before exhaustion`() {
        var canceled = false
        val cancellation = CancellationException("candidate-free cancellation")
        val thrown =
            assertThrows(CancellationException::class.java) {
                scan(
                    enumerate = { _ -> canceled = true },
                    checkCanceled = { if (canceled) throw cancellation },
                )
            }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `candidate-free current enumeration exhausts without candidate work`() {
        val read = scan(enumerate = { _ -> })
        val observed = assertInstanceOf(ValueFlowRead.Observed::class.java, read)
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, observed.step.terminal)
        assertEquals(emptyList<ValueTransfer>(), observed.step.transfers)
        assertEquals(emptyList<ValueFlowObligation>(), observed.step.obligations)
        assertEquals(2L, observed.step.examinedWorkUnits.value)
    }

    @Test
    fun `traversal admission stops before a later candidate-free node`() {
        var elapsed = 0L
        val visited = mutableListOf<Int>()
        val read =
            scan(
                enumerate = { traversal ->
                    for (node in listOf(0, 1, 2)) {
                        if (node == 1) elapsed = DEADLINE
                        if (!traversal.admitTraversal()) break
                        visited += node
                    }
                },
                elapsedMillis = { elapsed },
            )
        val suspended = assertInstanceOf(ValueFlowRead.Suspended::class.java, read)
        assertEquals(ValueFlowSuspensionCause.TIME_LIMIT_REACHED, suspended.cause)
        assertEquals(listOf(0), visited)
        assertEquals(2L, suspended.step.examinedWorkUnits.value)
    }

    private fun scan(
        enumerate: (LocalReferenceTraversal<Int>) -> Unit,
        checkCanceled: () -> Unit = {},
        elapsedMillis: () -> Long = { 0L },
    ): ValueFlowRead {
        val budget =
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(RESULT_LIMIT).flowRefined(),
                    WorkUnitLimit.parse(WORK_LIMIT).flowRefined(),
                    ElapsedTimeLimitMillis.parse(DEADLINE).flowRefined(),
                ),
                RelationByteLimit.parse(BYTE_LIMIT).flowRefined(),
            )
        val source =
            ValueSite.fromCompiler(
                    enclosing = flowEndpoint("scan", 0, OWNER_END),
                    range = flowRange(BINDING_START, BINDING_END),
                    role = ValueRole.LocalBinding,
                )
                .flowRefined()
        val request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT)
        val domain = RelationRequest.start(source.enclosing, RelationMeaning.References, budget, request.boundary)
        return LocalBindingReferenceScan<Int>(
                request = request,
                domain = domain,
                enumerate = enumerate,
                key = { error("Candidate-free enumeration emitted a reference") },
                confirm = { error("Candidate-free enumeration confirmed a reference") },
                checkCanceled = checkCanceled,
                elapsedMillis = elapsedMillis,
            )
            .read()
    }

    private companion object {
        const val DEADLINE = 10L
        const val RESULT_LIMIT = 32
        const val WORK_LIMIT = 32L
        const val BYTE_LIMIT = 1_000_000L
        const val OWNER_END = 100
        const val BINDING_START = 20
        const val BINDING_END = 30
    }
}
