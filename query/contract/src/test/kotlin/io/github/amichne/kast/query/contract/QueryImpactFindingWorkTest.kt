package io.github.amichne.kast.query.contract

import com.sun.management.ThreadMXBean
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class QueryImpactFindingWorkTest {
    @Test
    fun `large ledger drains preserve every original ordinal path and qualification`() {
        val ledger = ledger(1000)
        for (pageSize in listOf(1, 37, 100)) {
            var cursor = 0
            val ordinals = mutableListOf<Int>()
            while (cursor < ledger.paths.size) {
                val next = minOf(cursor + pageSize, ledger.paths.size)
                val view = findings(ledger, cursor, next)
                assertWindow(ledger, view, cursor, next)
                ordinals += view.entries.map { it.ordinal.value }
                cursor = view.nextOrdinal.value
            }
            assertEquals((0 until 1000).toList(), ordinals)
        }
        val empty = findings(ledger, 1000, 1000)
        assertWindow(ledger, empty, 1000, 1000)
        assertThrows(UnsupportedOperationException::class.java) {
            (findings(ledger, 499, 500).entries as MutableList).clear()
        }
    }

    @Test
    fun `finding windows retain finite rejection for invalid and oversized ranges`() {
        val ledger = ledger(1000)
        for ((start, end) in listOf(-1 to 0, 4 to 3, 1000 to 1001, 1001 to 1001)) {
            assertEquals(
                Refinement.Rejected(QueryImpactWitnessFailure.INVALID_RANGE),
                QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, start, end),
            )
        }
        assertEquals(
            Refinement.Rejected(QueryImpactWitnessFailure.PAGE_TOO_LARGE),
            QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, 0, 101),
        )
    }

    /** JVM allocation is an explicit measurement boundary, excluded from routine behavior tests. */
    @Test
    @Tag("performance")
    fun `fixed finding window allocation does not scale with excluded paths`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported) { "Thread allocation measurement is unavailable" }
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val small = ledger(16)
        val large = ledger(1000)
        val sink = PageSink()
        repeat(512) {
            sink.consume(findings(small, 7, 8))
            sink.consume(findings(large, 499, 500))
        }
        val smallPage = measure(bean, 64) { sink.consume(findings(small, 7, 8)) }
        val largePage = measure(bean, 64) { sink.consume(findings(large, 499, 500)) }
        val fullPage = measure(bean, 64) { sink.consume(findings(large, 450, 550)) }
        val emptyPage = measure(bean, 64) { sink.consume(findings(large, 1000, 1000)) }
        val oneRowDrain =
            measure(bean, 1) {
                for (cursor in 0 until 1000) sink.consume(findings(large, cursor, cursor + 1))
            }
        val hundredRowDrain =
            measure(bean, 1) {
                for (cursor in 0 until 1000 step 100) sink.consume(findings(large, cursor, cursor + 100))
            }
        println(
            FindingAllocationEvidence(
                runtime = System.getProperty("java.runtime.version"),
                smallPathCount = 16,
                largePathCount = 1000,
                samples = 3,
                iterationsPerPageSample = 64,
                smallOneRowPageBytes = smallPage,
                largeOneRowPageBytes = largePage,
                hundredRowPageBytes = fullPage,
                emptyPageBytes = emptyPage,
                oneRowDrainBytes = oneRowDrain,
                hundredRowDrainBytes = hundredRowDrain,
            )
        )
        assertWindow(large, findings(large, 499, 500), 499, 500)
        assertTrue(largePage <= smallPage * 2 + 1024, "Excluded paths increased allocation: $smallPage -> $largePage")
        assertTrue(emptyPage <= 1024, "Empty page materialized excluded findings: $emptyPage")
    }

    private fun measure(bean: ThreadMXBean, iterations: Int, action: () -> Unit): Long {
        val thread = Thread.currentThread().threadId()
        val samples =
            LongArray(3) {
                val before = bean.getThreadAllocatedBytes(thread)
                check(before >= 0) { "Thread allocation observation is unavailable" }
                repeat(iterations) { action() }
                val after = bean.getThreadAllocatedBytes(thread)
                check(after >= before) { "Thread allocation observation moved backwards" }
                (after - before) / iterations
            }
        return samples.sorted()[1]
    }

    private class PageSink {
        @Volatile private var latest: QueryImpactWitnessView? = null

        fun consume(view: QueryImpactWitnessView) {
            latest = view
        }
    }

    private data class FindingAllocationEvidence(
        val runtime: String,
        val smallPathCount: Int,
        val largePathCount: Int,
        val samples: Int,
        val iterationsPerPageSample: Int,
        val smallOneRowPageBytes: Long,
        val largeOneRowPageBytes: Long,
        val hundredRowPageBytes: Long,
        val emptyPageBytes: Long,
        val oneRowDrainBytes: Long,
        val hundredRowDrainBytes: Long,
    )

    private fun assertWindow(ledger: QueryImpactLedger, view: QueryImpactWitnessView, start: Int, end: Int) {
        assertSame(ledger, view.ledger)
        assertSame(ledger.closure, view.ledger.closure)
        assertEquals(ledger.paths.size, view.sectionCount.value)
        assertEquals(start, view.firstOrdinal.value)
        assertEquals(end, view.nextOrdinal.value)
        assertEquals((start until end).toList(), view.entries.map { it.ordinal.value })
        for (record in view.entries) {
            val finding = (record.evidence as QueryImpactWitnessEntry.Finding).finding
            assertEquals(record.ordinal.value, finding.pathOrdinal.value)
            assertSame(ledger.paths[record.ordinal.value], finding.path)
        }
    }

    private fun findings(ledger: QueryImpactLedger, start: Int, end: Int) =
        QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.FINDINGS, start, end).value()

    private fun ledger(count: Int): QueryImpactLedger {
        val fixture = QueryImpactLedgerTest.Fixture(ownerEnd = 2000)
        val sites =
            (0 until count).map { index ->
                ValueSite.fromCompiler(
                        fixture.owner,
                        ExactDeclarationTextRange.parse(100 + index, 101 + index).value(),
                        ValueRole.ExpressionResult,
                    )
                    .value()
            }
        val producers = sites.map { site ->
            QueryImpactProducer.admit(
                    site,
                    ValueInvocation.fromCompiler(fixture.owner, site.range, fixture.owner).value(),
                )
                .value()
        }
        val observations = sites.map { fixture.observe(it, emptyList()) }
        val paths = observations.map { observation ->
            QueryImpactPath.fromEvidence(
                    observation.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(observation).value(),
                )
                .value()
        }
        return QueryImpactLedger.fromEvidence(
                sites,
                fixture.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                observations,
                paths,
                originalProducers = producers,
            )
            .value()
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejected: $failure")
    }
