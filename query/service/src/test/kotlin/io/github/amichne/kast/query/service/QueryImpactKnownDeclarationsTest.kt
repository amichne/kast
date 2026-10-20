package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowWorkReceipt
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactKnownDeclarationsTest {
    @Test
    fun `known declaration input survives the emitted request observed receipt and final ledger`() = runTest {
        val c = KnownDeclarationQueryCase()
        val native = KnownDeclarationQueryScript(listOf(c.f.producer.site)) { request -> c.observed(request) }
        val result = c.f.service(native.port).run(c.f.request(work = 1 + 1 + 1, plan = c.plan))
        native.assertConsumed()
        assertSame(c.known, c.source.knownDeclarations)
        assertCarrier(c.known, native.requests.single().knownDeclarations)
        val step = (native.reads.single() as ValueFlowRead.Observed).step
        assertCarrier(c.known, step.knownDeclarations)
        assertCarrier(c.known, step.receipts.single().knownDeclarations)
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
        val ledger = knownLedger(complete.result.rows)
        assertCarrier(c.known, ledger.knownDeclarations)
        assertCarrier(c.known, ledger.observations.single().knownDeclarations)
        assertCarrier(c.known, ledger.readReceipts.getValue(c.f.producer.site).single().knownDeclarations)
    }

    @Test
    fun `a suspended local scan preserves known inputs through both actual grants and accumulated receipts`() =
        runTest {
            val c = KnownDeclarationQueryCase()
            val native =
                KnownDeclarationQueryScript(listOf(c.f.producer.site, c.binding, c.binding, c.local)) { request ->
                    if (request.source == c.binding) c.scan(request)
                    else {
                        val edges =
                            if (request.source == c.f.producer.site)
                                listOf(c.f.edge(request.source, c.binding, ValueTransferKind.LOCAL_BINDING))
                            else emptyList()
                        c.observed(request, edges)
                    }
                }
            val service = c.f.service(native.port)
            // Two visits, one producer read, and one child route leave the four-unit native scan.
            val first =
                assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(c.f.request(8, plan = c.plan)))
            assertCarrier(c.known, native.requests.first().knownDeclarations)
            assertEquals(listOf(0, 1), c.confirmations)
            native.requests.forEach { assertCarrier(c.known, it.knownDeclarations) }
            val partial = assertInstanceOf(ValueFlowRead.Suspended::class.java, native.reads.last())
            assertCarrier(c.known, partial.step.knownDeclarations)
            assertCarrier(c.known, partial.step.receipts.single().knownDeclarations)
            val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
            val saved = checkNotNull(checkpoint.impact)
            assertEquals(2, saved.remainders.getValue(c.binding).consumed.size)
            assertCarrier(c.known, saved.receipts.getValue(c.binding).single().knownDeclarations)
            val last = service.run(c.f.request(plan = c.plan, checkpoint = checkpoint))
            native.assertConsumed()
            assertEquals(listOf(0, 1, 2), c.confirmations)
            native.requests.forEach { assertCarrier(c.known, it.knownDeclarations) }
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            val ledger = knownLedger(complete.result.rows)
            assertCarrier(c.known, ledger.knownDeclarations)
            val receipts = ledger.readReceipts.getValue(c.binding)
            assertEquals(listOf(4L, 3L), receipts.map { it.examinedWorkUnits.value })
            receipts.forEach { assertCarrier(c.known, it.knownDeclarations) }
            assertEquals(c.local, (complete.result.rows as QueryRows.ValuePaths).values.single().destination)
        }

    @Test
    fun `rejected native work keeps its known input and exact receipt without an invented observation`() = runTest {
        val c = KnownDeclarationQueryCase()
        val work = RelationWorkCount.parse(1).autoRefined()
        val native =
            KnownDeclarationQueryScript(listOf(c.f.producer.site)) {
                ValueFlowRead.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE, work)
            }
        val result = c.f.service(native.port).run(c.f.request(work = 1 + 1 + 1, plan = c.plan))
        native.assertConsumed()
        assertCarrier(c.known, native.requests.single().knownDeclarations)
        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        val ledger = knownLedger(qualified.result.rows)
        assertCarrier(c.known, ledger.knownDeclarations)
        assertTrue(ledger.observations.isEmpty())
        val rejection = ledger.readRejections.single() as QueryImpactReadRejection.Native
        assertEquals(ValueFlowRejection.OWNER_UNAVAILABLE, rejection.cause)
        assertEquals(work, rejection.examinedWorkUnits)
        val receipt = ledger.readReceipts.getValue(c.f.producer.site).single()
        assertCarrier(c.known, receipt.knownDeclarations)
        assertEquals(native.requests.single().budget, receipt.domain.budget)
        assertEquals(work, receipt.examinedWorkUnits)
        assertEquals(0, receipt.returnedResults.value)
        assertEquals(0L, receipt.returnedBytes.value)
    }

    @Test
    fun `cached resume keeps the known input without another native request`() = runTest {
        val c = KnownDeclarationQueryCase()
        val native = KnownDeclarationQueryScript(listOf(c.f.producer.site)) { request -> c.observed(request) }
        val service = c.f.service(native.port)
        val first =
            assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(c.f.request(2, plan = c.plan)))
        assertCarrier(c.known, native.requests.single().knownDeclarations)
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val observed = (checkpoint.impact!!.reads.getValue(c.f.producer.site) as ValueFlowRead.Observed).step
        assertCarrier(c.known, observed.knownDeclarations)
        val last = service.run(c.f.request(work = 1 + 1, plan = c.plan, checkpoint = checkpoint))
        native.assertConsumed()
        assertEquals(1, native.requests.size)
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
        val ledger = knownLedger(complete.result.rows)
        assertCarrier(c.known, ledger.knownDeclarations)
        assertSame(observed, ledger.observations.single())
        assertEquals(listOf(1L), ledger.readReceipts.getValue(c.f.producer.site).map { it.examinedWorkUnits.value })
    }

    @Test
    fun `same count and declaration identity cannot replace the full observed receipt proof`() {
        val c = KnownDeclarationQueryCase()
        val counterfeit = c.alternate()
        assertCounterfeit(c.known, counterfeit)
        val request = request(c, c.known)
        val read = c.observed(request, known = counterfeit)
        assertCarrier(counterfeit, read.step.receipts.single().knownDeclarations)
        val actual = validateImpactRead(read, request, c.source)
        assertEquals(
            ValueFlowRead.ContractRejected(
                ValueFlowStepFailure.DOMAIN_MISMATCH,
                RelationWorkCount.parse(1).autoRefined(),
            ),
            actual,
        )
    }

    @Test
    fun `a counterfeit same-count request carrier cannot borrow the correct source and native receipt`() {
        val c = KnownDeclarationQueryCase()
        val counterfeit = c.alternate()
        assertCounterfeit(c.known, counterfeit)
        val request = request(c, counterfeit)
        val read = c.observed(request, known = c.known)
        assertCarrier(c.known, read.step.receipts.single().knownDeclarations)
        val actual = validateImpactRead(read, request, c.source)
        assertEquals(
            ValueFlowRead.ContractRejected(
                ValueFlowStepFailure.DOMAIN_MISMATCH,
                RelationWorkCount.parse(1).autoRefined(),
            ),
            actual,
        )
    }

    @Test
    fun `the empty input remains empty through ordinary request receipt and final ledger`() = runTest {
        val c = KnownDeclarationQueryCase(empty = true)
        val native = KnownDeclarationQueryScript(listOf(c.f.producer.site)) { request -> c.observed(request) }
        val result = c.f.service(native.port).run(c.f.request(work = 1 + 1 + 1, plan = c.plan))
        native.assertConsumed()
        assertSame(ValueKnownDeclarations.Empty, native.requests.single().knownDeclarations)
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
        val ledger = knownLedger(complete.result.rows)
        assertSame(ValueKnownDeclarations.Empty, ledger.knownDeclarations)
        assertSame(ValueKnownDeclarations.Empty, ledger.observations.single().knownDeclarations)
        assertSame(
            ValueKnownDeclarations.Empty,
            ledger.readReceipts.getValue(c.f.producer.site).single().knownDeclarations,
        )
    }

    @Test
    fun `a rejected receipt snapshots the complete request carrier rather than the count`() {
        val c = KnownDeclarationQueryCase()
        val alternate = c.alternate()
        val request = request(c, alternate)
        val receipt = ValueFlowWorkReceipt.rejected(request, RelationWorkCount.parse(1).autoRefined())
        assertCounterfeit(c.known, alternate)
        assertCarrier(alternate, receipt.knownDeclarations)
        assertFalse(c.known.hasSameProofAs(receipt.knownDeclarations))
        assertEquals(request.budget, receipt.domain.budget)
    }

    private fun request(c: KnownDeclarationQueryCase, known: ValueKnownDeclarations): ValueFlowRequest {
        val query = c.f.request().budget
        val budget = RelationBudget(query.resources, RelationByteLimit.parse(query.returnedBytes.value).autoRefined())
        return ValueFlowRequest(
            source = c.f.producer.site,
            budget = budget,
            boundary = c.source.domain,
            knownDeclarations = known,
        )
    }

    private fun assertCounterfeit(expected: ValueKnownDeclarations, other: ValueKnownDeclarations) {
        assertEquals(expected.declarations.size, other.declarations.size)
        assertEquals(
            expected.declarations.single().endpoint.valueIdentity,
            other.declarations.single().endpoint.valueIdentity,
        )
        assertFalse(expected.hasSameProofAs(other))
    }

    private fun assertCarrier(expected: ValueKnownDeclarations, actual: ValueKnownDeclarations) {
        assertEquals(expected.declarations.size, actual.declarations.size)
        assertTrue(expected.hasSameProofAs(actual), "The complete current declaration proof must survive")
    }
}
