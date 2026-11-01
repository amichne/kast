package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBindingEnumerationSuspensionTest {
    @Test
    fun `native enumeration expiry before a candidate preserves a resumable empty prefix`() {
        val request = request()
        val interrupted =
            scan(request, enumerate = {}, enumerationSuspension = { ValueFlowSuspensionCause.TIME_LIMIT_REACHED })
                as ValueFlowRead.Suspended
        assertEquals(ValueFlowSuspensionCause.TIME_LIMIT_REACHED, interrupted.cause)
        assertEquals(ValueFlowTerminal.ResourceSuspended, interrupted.step.terminal)
        assertTrue(interrupted.remainder.consumed.isEmpty())
        assertTrue(interrupted.remainder.emitted.isEmpty())
        assertTrue(interrupted.step.obligations.isEmpty())
        val resumed =
            scan(request.copy(remainder = interrupted.remainder), enumerate = { visit -> visit(0) })
                as ValueFlowRead.Observed
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, resumed.step.terminal)
        assertEquals(listOf(read(request, 0)), resumed.step.transfers.map { it.target })
    }

    @Test
    fun `native enumeration stop does not replace an earlier result limit`() {
        val request = request(results = 1)
        val interrupted =
            scan(
                request,
                enumerate = { visit ->
                    assertTrue(visit(0))
                    assertEquals(false, visit(1))
                },
                enumerationSuspension = { ValueFlowSuspensionCause.TIME_LIMIT_REACHED },
            )
                as ValueFlowRead.Suspended
        assertEquals(ValueFlowSuspensionCause.RESULT_LIMIT_REACHED, interrupted.cause)
        assertEquals(setOf(key(0)), interrupted.remainder.consumed)
        assertEquals(setOf(read(request, 0).identity), interrupted.remainder.emitted)
    }

    @Test
    fun `exhausted empty native enumeration remains exhausted`() {
        val completed = scan(request(), enumerate = {}) as ValueFlowRead.Observed
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, completed.step.terminal)
        assertTrue(completed.step.transfers.isEmpty())
        assertTrue(completed.step.obligations.isEmpty())
    }

    private fun scan(
        request: ValueFlowRequest,
        enumerate: ((Int) -> Boolean) -> Unit,
        enumerationSuspension: () -> ValueFlowSuspensionCause? = { null },
    ): ValueFlowRead =
        LocalBindingReferenceScan(
                request = request,
                domain =
                    RelationRequest.start(
                        request.source.enclosing as RelationEndpoint.Resolved,
                        RelationMeaning.References,
                        request.budget,
                        request.boundary,
                    ),
                enumerate = enumerate,
                key = ::key,
                confirm = { LocalReferenceResolution.Transfer(read(request, it)) },
                checkCanceled = {},
                elapsedMillis = { 0L },
                enumerationSuspension = enumerationSuspension,
            )
            .read()

    private fun request(results: Int = 10): ValueFlowRequest {
        val source =
            ValueSite.fromCompiler(flowEndpoint("owner", 0, 200), flowRange(10, 20), ValueRole.LocalBinding)
                .flowRefined()
        return ValueFlowRequest(
            source,
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(results).flowRefined(),
                    WorkUnitLimit.parse(100).flowRefined(),
                    ElapsedTimeLimitMillis.parse(10_000).flowRefined(),
                ),
                RelationByteLimit.parse(2_000_000).flowRefined(),
            ),
            RelationSearchBoundary.RETAINED_SUBJECT,
        )
    }

    private fun key(index: Int): LocalReferenceKey {
        val range = flowRange(30 + index, 31 + index)
        return LocalReferenceKey(range, range, LocalReferenceKind.fromBoundary("fixture.Reference").flowRefined())
    }

    private fun read(request: ValueFlowRequest, index: Int): ValueSite =
        ValueSite.fromCompiler(request.source.enclosing, key(index).element, ValueRole.LocalRead).flowRefined()
}
