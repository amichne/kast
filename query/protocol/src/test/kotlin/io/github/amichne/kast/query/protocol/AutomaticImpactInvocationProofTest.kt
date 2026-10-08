package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryInvestigationCompletion
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryWorkCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Pure admission tests use conserved detached compiler facts; they do not establish native authority. */
internal class AutomaticImpactInvocationProofTest : AutomaticSymbolQueryCase() {
    private val impact = ImpactWitnessPresentationFixture()
    private val rows = QueryRows.ValuePaths.fromInvestigation(impact.ledger).refined()
    private val projection = QueryOutcomeProjection(impact.symbols.references, QueryStateStore())

    @Test
    fun `established completion from a different original ledger is rejected`() {
        val ledger = impact.ledger
        val other =
            QueryImpactLedger.fromEvidence(
                    ledger.seeds,
                    ledger.domain,
                    ledger.semantics,
                    ledger.representationModels,
                    ledger.boundaryModels,
                    ledger.observations,
                    ledger.paths,
                    ledger.readRejections,
                    listOf(impact.producer),
                    ledger.requestedSites,
                    ledger.peerBoundaries,
                    ledger.readReceipts,
                )
                .refined()
        assertNotSame(ledger, other)
        val complete =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                QueryExecutionResult.Complete.create(
                    QueryResult(QueryRows.ValuePaths.fromInvestigation(other).refined(), emptyList()),
                    QueryCoverage.Complete(QueryCount.parse(1).refined()),
                ),
            )
        val forged = execution(rows, QueryInvestigationCompletion.Established.from(complete))
        val admission = SymbolInvocationPage.admit(observed(forged), impact.request)
        assertEquals(QueryInvocationStop.INVALID_STATE, (admission as Refinement.Rejected).failure.reason)
    }

    @Test
    fun `repeated original path rejects the page and preserves accepted facts`() {
        val facts = facts()
        val page = admit(rows)
        assertInstanceOf(Refinement.Refined::class.java, facts.append(page, Long.MAX_VALUE))
        val charged = facts.retainedBytes
        val rejected = facts.append(page, Long.MAX_VALUE) as Refinement.Rejected
        assertEquals(QueryInvocationStop.INVALID_STATE, rejected.failure.reason)
        assertEquals(charged, facts.retainedBytes)
        val finished = facts.finish(QueryInvocationTransition.Stopped(QueryInvocationStop.COMPLETED), terminal())
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, finished.execution)
        assertEquals(rows.values, (complete.result.rows as QueryRows.ValuePaths).values)
        assertSame(
            impact.ledger,
            ((complete.result.rows as QueryRows.ValuePaths).accounting as QueryValuePathAccounting.Investigated).ledger,
        )
    }

    @Test
    fun `missing original path cannot become complete despite completed stop`() {
        val facts = facts()
        assertInstanceOf(
            Refinement.Refined::class.java,
            facts.append(admit(rows.selectRows(emptyList()).refined()), Long.MAX_VALUE),
        )
        val finished = facts.finish(QueryInvocationTransition.Stopped(QueryInvocationStop.COMPLETED), terminal())
        val rejected = assertInstanceOf(QueryExecutionResult.Rejected::class.java, finished.execution)
        assertEquals(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION, rejected.reason)
    }

    @Test
    fun `retained capacity rejection publishes no admitted row shape or selection`() {
        val facts = facts()
        val page = admit(rows)
        val rejected = facts.append(page, 0L) as Refinement.Rejected
        assertEquals(QueryInvocationStop.RETAINED_BYTES_LIMIT, rejected.failure.reason)
        assertFalse(facts.hasObservedRowShape)
        assertEquals(0L, facts.retainedBytes)
        // The same page is still admissible after rejection; no selected identity escaped the rejected transaction.
        assertInstanceOf(Refinement.Refined::class.java, facts.append(page, Long.MAX_VALUE))
        val fresh = facts()
        assertInstanceOf(Refinement.Refined::class.java, fresh.append(page, Long.MAX_VALUE))
        assertEquals(fresh.retainedBytes, facts.retainedBytes)
        val finished = facts.finish(QueryInvocationTransition.Stopped(QueryInvocationStop.COMPLETED), terminal())
        assertInstanceOf(QueryExecutionResult.Complete::class.java, finished.execution)
    }

    private fun facts() = QueryInvocationFacts(impact.symbols.authority, policy(retainedBytes = 10_000_000))

    private fun terminal() = QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)

    private fun execution(
        selected: QueryRows.ValuePaths,
        completion: QueryInvestigationCompletion = QueryInvestigationCompletion.NotEstablished,
    ) =
        QueryExecutionResult.Qualified(
                QueryResult(selected, emptyList()),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(1).refined(),
                        setOf(QueryLimitation.RESULT_LIMIT_REACHED),
                    )
                    .refined(),
                terminal(),
                investigationCompletion = completion,
            )
            .observedWork(QueryWorkCount.parse(1).refined())

    private fun observed(execution: QueryExecutionResult) =
        QueryInvocationPage(
            projection.projectExecution(impact.request, impact.symbols.authority, execution),
            execution,
        )

    private fun admit(selected: QueryRows.ValuePaths) =
        SymbolInvocationPage.admit(observed(execution(selected)), impact.request).refined()
}
