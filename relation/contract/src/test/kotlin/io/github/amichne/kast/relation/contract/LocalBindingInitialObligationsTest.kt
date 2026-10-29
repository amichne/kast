package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBindingInitialObligationsTest {
    @Test
    fun `normal local read snapshots two distinct getter obligations and charges their evidence`() {
        val f = InitialObligationFixture()
        val expected = f.obligations()
        val supplied = (expected + expected.first()).toMutableList()
        val scan = f.scan(initial = supplied)
        supplied.clear()
        val observed = scan.read() as ValueFlowRead.Observed
        assertEquals(expected, observed.step.obligations)
        assertEquals(ValueFlowTerminal.Unresolved, observed.step.terminal)
        assertEquals(listOf(f.read(0)), observed.step.transfers.map { it.target })
        val baseline = InitialObligationFixture().scan(initial = emptyList()).read() as ValueFlowRead.Observed
        assertTrue(observed.step.retainedBytes > baseline.step.retainedBytes)
        assertEquals(observed.step.retainedBytes, observed.step.receipts.single().returnedBytes.value)
    }

    @Test
    fun `suspended and resumed local scans preserve both exact expression obligations`() {
        val f = InitialObligationFixture(results = 1)
        val expected = f.obligations()
        val suspended = f.scan(initial = expected, candidates = listOf(0, 1)).read() as ValueFlowRead.Suspended
        assertEquals(ValueFlowSuspensionCause.RESULT_LIMIT_REACHED, suspended.cause)
        assertEquals(expected, suspended.step.obligations)
        assertEquals(ValueFlowTerminal.ResourceSuspended, suspended.step.terminal)
        assertEquals(setOf(f.key(0)), suspended.remainder.consumed)
        assertEquals(setOf(f.read(0).identity), suspended.remainder.emitted)
        val resumed =
            f.scan(
                    initial = expected,
                    candidates = listOf(0, 1),
                    request = f.request.copy(remainder = suspended.remainder),
                )
                .read() as ValueFlowRead.Observed
        assertEquals(expected, resumed.step.obligations)
        assertEquals(listOf(f.read(1)), resumed.step.transfers.map { it.target })
        assertEquals(ValueFlowTerminal.Unresolved, resumed.step.terminal)
    }

    @Test
    fun `foreign source and epoch reject before enumeration or confirmation`() {
        val f = InitialObligationFixture()
        val foreignRange =
            ValueSite.fromCompiler(f.request.source.enclosing, flowRange(11, 20), ValueRole.LocalBinding).flowRefined()
        val foreignEpoch =
            ValueSite.fromCompiler(
                    flowEndpoint("owner", 0, 200, generation = 2),
                    flowRange(10, 20),
                    ValueRole.LocalBinding,
                )
                .flowRefined()
        for ((source, cause) in
            listOf(
                foreignRange to ValueFlowStepFailure.SOURCE_MISMATCH,
                foreignEpoch to ValueFlowStepFailure.BASIS_MISMATCH,
            )) {
            val rejected = f.scan(initial = listOf(f.obligation(source, 40))).read() as ValueFlowRead.ContractRejected
            assertEquals(cause, rejected.cause)
        }
        assertEquals(0, f.enumerations)
        assertEquals(0, f.confirmations)
        assertEquals(0, f.addresses)
    }

    @Test
    fun `insufficient grant rejects initial evidence before native callbacks`() {
        val f = InitialObligationFixture(bytes = 1)
        val rejected = f.scan(initial = f.obligations()).read() as ValueFlowRead.Rejected
        assertEquals(ValueFlowRejection.GRANT_TOO_SMALL, rejected.cause)
        assertEquals(0, f.enumerations)
        assertEquals(0, f.confirmations)
        assertEquals(0, f.addresses)
    }
}

private class InitialObligationFixture(results: Int = 10, bytes: Long = 2_000_000) {
    val request =
        ValueFlowRequest(
            source =
                ValueSite.fromCompiler(flowEndpoint("owner", 0, 200), flowRange(10, 20), ValueRole.LocalBinding)
                    .flowRefined(),
            budget =
                RelationBudget(
                    resources =
                        ResourceBudget(
                            ResultLimit.parse(results).flowRefined(),
                            WorkUnitLimit.parse(100).flowRefined(),
                            ElapsedTimeLimitMillis.parse(10_000).flowRefined(),
                        ),
                    returnedBytes = RelationByteLimit.parse(bytes).flowRefined(),
                ),
            boundary = RelationSearchBoundary.RETAINED_SUBJECT,
        )
    var enumerations = 0
    var confirmations = 0
    var addresses = 0

    fun obligations(): List<ValueFlowObligation> =
        listOf(obligation(request.source, 40), obligation(request.source, 50))

    fun obligation(source: ValueSite, start: Int): ValueFlowObligation =
        ValueFlowObligation.fromExpression(
                site = source,
                cause = ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY,
                kind = ValueFlowExpressionKind.fromCompiler("KtDestructuringDeclarationEntry").flowRefined(),
                range = flowRange(start, start + 1),
            )
            .flowRefined()

    fun key(index: Int): LocalReferenceKey =
        LocalReferenceKey(
            flowRange(30 + index, 31 + index),
            flowRange(30 + index, 31 + index),
            LocalReferenceKind.fromBoundary("fixture.Reference").flowRefined(),
        )

    fun read(index: Int): ValueSite =
        ValueSite.fromCompiler(request.source.enclosing, key(index).element, ValueRole.LocalRead).flowRefined()

    fun scan(
        initial: List<ValueFlowObligation>,
        candidates: List<Int> = listOf(0),
        request: ValueFlowRequest = this.request,
    ): LocalBindingReferenceScan<Int> =
        LocalBindingReferenceScan(
            request = request,
            domain =
                RelationRequest.start(
                    request.source.enclosing as RelationEndpoint.Resolved,
                    RelationMeaning.References,
                    request.budget,
                    request.boundary,
                ),
            enumerate = { visit ->
                enumerations++
                for (candidate in candidates) if (!visit(candidate)) break
            },
            key = {
                addresses++
                key(it)
            },
            confirm = {
                confirmations++
                LocalReferenceResolution.Transfer(read(it))
            },
            checkCanceled = {},
            elapsedMillis = { 0L },
            initialObligations = initial,
        )
}
