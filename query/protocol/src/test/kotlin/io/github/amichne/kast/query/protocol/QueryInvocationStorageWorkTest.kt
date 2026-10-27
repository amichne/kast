package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class QueryInvocationStorageWorkTest {
    @Test
    fun `one append measures standalone checkpoint storage once`(reporter: TestReporter) {
        val case = Case()
        val facts = case.facts()

        assertInstanceOf(Refinement.Refined::class.java, facts.append(case.page, Long.MAX_VALUE))

        reporter.publishEntry("standalone-checkpoint-measurements", case.checkpoint.measurements.toString())
        reporter.publishEntry("semantic-bytes", facts.retentionEstimate.semantic.value.toString())
        reporter.publishEntry("preview-bytes", facts.retentionEstimate.preview.value.toString())
        reporter.publishEntry("row-reference-bytes", facts.retentionEstimate.rowReferenceCells.value.toString())
        reporter.publishEntry("total-bytes", facts.retainedBytes.toString())
        assertCharge(facts)
        assertEquals(1, case.checkpoint.measurements)
        assertEquals(1, case.checkpoint.sharedMeasurements)
    }

    @Test
    fun `capacity rejection rolls back shared graph accounting before an exact capacity retry`() {
        val case = Case()
        val accepted = case.facts()
        assertInstanceOf(Refinement.Refined::class.java, accepted.append(case.page, Long.MAX_VALUE))
        assertCharge(accepted)
        val required = 357_496L
        val rejected = case.facts()
        // The original grant remains a rejection after the required evidence adds 216 bytes.
        val originalGrant = assertInstanceOf(Refinement.Rejected::class.java, rejected.append(case.page, 357_280L))
        assertEquals(
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            assertInstanceOf(QueryInvocationTransition.Stopped::class.java, originalGrant.failure).reason,
        )
        assertEquals(QueryInvocationFactsRetentionEstimate.Empty, rejected.retentionEstimate)
        assertFalse(rejected.hasObservedRowShape)

        val failure = assertInstanceOf(Refinement.Rejected::class.java, rejected.append(case.page, required - 1))
        assertEquals(
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            assertInstanceOf(QueryInvocationTransition.Stopped::class.java, failure.failure).reason,
        )
        assertEquals(QueryInvocationFactsRetentionEstimate.Empty, rejected.retentionEstimate)
        assertFalse(rejected.hasObservedRowShape)
        assertInstanceOf(Refinement.Refined::class.java, rejected.append(case.page, required))
        assertEquals(accepted.retentionEstimate, rejected.retentionEstimate)
        assertCharge(rejected)
    }

    @Test
    fun `saturated standalone checkpoint charge remains saturated without shared replacement`() {
        val case = Case(Long.MAX_VALUE)
        val facts = case.facts()

        val failure = assertInstanceOf(Refinement.Rejected::class.java, facts.append(case.page, Long.MAX_VALUE - 1))
        assertEquals(
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            assertInstanceOf(QueryInvocationTransition.Stopped::class.java, failure.failure).reason,
        )
        assertEquals(QueryInvocationFactsRetentionEstimate.Empty, facts.retentionEstimate)
        assertEquals(0, case.checkpoint.sharedMeasurements)
        assertInstanceOf(Refinement.Refined::class.java, facts.append(case.page, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, facts.retentionEstimate.semantic.value)
        assertEquals(Long.MAX_VALUE, facts.retainedBytes)
        assertEquals(0, case.checkpoint.sharedMeasurements)
    }

    @Test
    fun `unconditional conditions share payload but charge each incoming reference`() {
        val graph = QueryImpactRetainedGraph()
        // Fresh graph: conditions and empty-list objects, each with a 512-byte base and an 8-byte reference, times 8.
        assertEquals(8_320L, graph.conditions(ValueConditions.Unconditional))
        // The immutable payload is already retained; only the next 8-byte reference remains, times 8.
        assertEquals(64L, graph.conditions(ValueConditions.Unconditional))
    }

    private fun assertCharge(facts: QueryInvocationFacts) {
        // Two sites and their two identities add four references to one shared conditions object.
        // Its empty-list payload is already shared: (512 + 4 * 8 + 8) * 8 = 4,416 over the prior 349,880.
        // Two observations now also keep references to the shared empty construction list: 2 * 8 * 8 = 128.
        assertEquals(354_424L, facts.retentionEstimate.semantic.value)
        // Producer, transfer source/target and terminal site each encode the required 16-byte ,"conditions":[] field.
        // The required EXACT classification adds 63 bytes; the empty construction field adds 25 bytes.
        assertEquals(2_816L, facts.retentionEstimate.preview.value)
        assertEquals(256L, facts.retentionEstimate.rowReferenceCells.value)
        assertEquals(357_496L, facts.retainedBytes)
    }

    private class Case(overhead: Long = 1_024L) {
        private val fixture = ImpactWitnessPresentationFixture()
        private val source =
            QueryImpactSource.admit(listOf(fixture.producer), emptyList(), emptyList(), fixture.ledger.domain).value()
        private val plan =
            (QueryPlanCompiler.admit(
                    QueryPlanSyntax(QuerySourceSyntax.Impact(source), emptyList(), QueryOutputSyntax.ValuePaths)
                ) as QueryPlanAdmission.Admitted)
                .plan
        val checkpoint = LedgerCheckpoint(plan, fixture.symbols.authority, fixture.ledger, overhead)
        val page: SymbolInvocationPage

        init {
            val execution =
                QueryExecutionResult.Qualified(
                        QueryResult(QueryRows.ValuePaths.fromInvestigation(fixture.ledger).value(), emptyList()),
                        QueryCoverage.Qualified.create(
                                QueryCount.parse(1).value(),
                                setOf(QueryLimitation.RESULT_LIMIT_REACHED),
                            )
                            .value(),
                        QueryContinuationState.Resumable(checkpoint),
                    )
                    .observedWork(QueryWorkCount.parse(1).value())
            val projected =
                QueryOutcomeProjection(fixture.symbols.references, QueryStateStore())
                    .projectExecution(fixture.request, fixture.symbols.authority, execution)
            page = SymbolInvocationPage.admit(QueryInvocationPage(projected, execution), fixture.request).value()
            checkpoint.measurements = 0
            checkpoint.sharedMeasurements = 0
        }

        fun facts(): QueryInvocationFacts =
            QueryInvocationFacts(
                fixture.symbols.authority,
                QueryInvocationPolicy(
                    ResultLimit.parse(10).value(),
                    QueryByteLimit.parse(100_000).value(),
                    QueryByteLimit.parse(Long.MAX_VALUE).value(),
                    CanonicalQueryCliDocuments::previewBytes,
                ),
            )
    }

    private class LedgerCheckpoint(
        override val plan: AdmittedQueryPlan,
        override val lease: SemanticReadAuthority,
        private val ledger: QueryImpactLedger,
        private val overhead: Long,
    ) : QueryCheckpoint {
        var measurements = 0
        var sharedMeasurements = 0

        override val retainedBytes: Long
            get() {
                measurements++
                return overhead.saturatedAdd(QueryImpactRetainedGraph().ledger(ledger))
            }

        override fun retainedBytes(graph: QueryImpactRetainedGraph): Long {
            sharedMeasurements++
            return overhead.saturatedAdd(graph.ledger(ledger))
        }
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
    }
