package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.query.contract.QueryInvestigationCompletion
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryPlanAdmission
import io.github.amichne.kast.query.contract.QueryPlanCompiler
import io.github.amichne.kast.query.contract.QueryPlanSyntax
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        val page = resumable(rows, checkpoint())
        val rejected = facts.append(page, 1L) as Refinement.Rejected
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

    @Test
    fun `unequal checkpoints with equal hashes remain admissible but an older equal value does not`() {
        val first = checkpoint()
        val second = first.copy(state = 2)
        assertNotEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        val facts = facts()
        val empty = rows.selectRows(emptyList()).refined()
        assertInstanceOf(Refinement.Refined::class.java, facts.append(resumable(empty, first), Long.MAX_VALUE))
        assertInstanceOf(Refinement.Refined::class.java, facts.append(resumable(empty, second), Long.MAX_VALUE))
        val reconstructed = first.copy()
        assertNotSame(first, reconstructed)
        assertStop(QueryInvocationStop.NON_ADVANCING, facts, resumable(empty, reconstructed))
    }

    @Test
    fun `invalid selection does not publish its checkpoint or replace the accepted ledger`() {
        val facts = facts()
        val first = checkpoint()
        val next = first.copy(state = 2)
        assertInstanceOf(Refinement.Refined::class.java, facts.append(resumable(rows, first), Long.MAX_VALUE))
        val retained = facts.retentionEstimate
        assertStop(QueryInvocationStop.INVALID_STATE, facts, resumable(rows, next), 1L)
        assertEquals(retained, facts.retentionEstimate)
        assertInstanceOf(
            Refinement.Refined::class.java,
            facts.append(resumable(rows.selectRows(emptyList()).refined(), next), Long.MAX_VALUE),
        )
    }

    @Test
    fun `foreign capture does not publish its checkpoint or basis proof`() {
        val facts = facts()
        val checkpoint = checkpoint()
        val foreign = ImpactWitnessPresentationFixture(symbols = RelationPagingFixture.live())
        val foreignRows =
            QueryRows.ValuePaths.fromInvestigation(foreign.ledger).refined().selectRows(emptyList()).refined()
        assertStop(QueryInvocationStop.INVALID_STATE, facts, resumable(foreignRows, checkpoint), 1L)
        assertEquals(QueryInvocationFactsRetentionEstimate.Empty, facts.retentionEstimate)
        assertFalse(facts.hasObservedRowShape)
        val accepted = resumable(rows, checkpoint)
        assertInstanceOf(Refinement.Refined::class.java, facts.append(accepted, Long.MAX_VALUE))
        val fresh = facts()
        assertInstanceOf(Refinement.Refined::class.java, fresh.append(accepted, Long.MAX_VALUE))
        assertEquals(fresh.retentionEstimate, facts.retentionEstimate)
    }

    @Test
    fun `row shape precedes duplicate and duplicate precedes selection capture and capacity`() {
        val facts = facts()
        val checkpoint = checkpoint()
        assertInstanceOf(Refinement.Refined::class.java, facts.append(resumable(rows, checkpoint), Long.MAX_VALUE))
        val retained = facts.retentionEstimate
        assertStop(
            QueryInvocationStop.INVALID_STATE,
            facts,
            resumable(QueryRows.Symbols.of(emptyList()), checkpoint),
            1L,
        )
        assertStop(QueryInvocationStop.NON_ADVANCING, facts, resumable(rows, checkpoint.copy()), 1L)
        val foreign = ImpactWitnessPresentationFixture(symbols = RelationPagingFixture.live())
        val foreignRows =
            QueryRows.ValuePaths.fromInvestigation(foreign.ledger).refined().selectRows(emptyList()).refined()
        val captureFacts = facts()
        assertInstanceOf(
            Refinement.Refined::class.java,
            captureFacts.append(resumable(QueryRows.ValuePaths.of(emptyList()), checkpoint), Long.MAX_VALUE),
        )
        assertStop(QueryInvocationStop.NON_ADVANCING, captureFacts, resumable(foreignRows, checkpoint.copy()), 1L)
        assertStop(
            QueryInvocationStop.INVALID_STATE,
            captureFacts,
            resumable(foreignRows, checkpoint.copy(state = 2)),
            1L,
        )
        assertEquals(retained, facts.retentionEstimate)
    }

    private fun assertStop(
        expected: QueryInvocationStop,
        facts: QueryInvocationFacts,
        page: SymbolInvocationPage,
        capacity: Long = Long.MAX_VALUE,
    ) {
        val rejected = facts.append(page, capacity) as Refinement.Rejected
        assertEquals(expected, rejected.failure.reason)
    }

    // Deliberate opaque contract collision, not a replacement for service checkpoint equality or hashing.
    private data class CollisionCheckpoint(
        override val plan: AdmittedQueryPlan,
        override val lease: SemanticReadAuthority,
        val state: Int,
    ) : QueryCheckpoint {
        override val retainedBytes = 100L

        override fun hashCode(): Int = 7
    }

    private fun checkpoint(): CollisionCheckpoint {
        val source =
            QueryImpactSource.admit(listOf(impact.producer), emptyList(), emptyList(), impact.ledger.domain).refined()
        val plan =
            (QueryPlanCompiler.admit(
                    QueryPlanSyntax(QuerySourceSyntax.Impact(source), emptyList(), QueryOutputSyntax.ValuePaths)
                ) as QueryPlanAdmission.Admitted)
                .plan
        return CollisionCheckpoint(plan, impact.symbols.authority, 1)
    }

    private fun resumable(selected: QueryRows, checkpoint: QueryCheckpoint): SymbolInvocationPage {
        val requested = if (selected is QueryRows.Symbols) impact.request.copy(output = output) else impact.request
        val execution = execution(selected, continuation = QueryContinuationState.Resumable(checkpoint))
        return SymbolInvocationPage.admit(
                QueryInvocationPage(
                    projection.projectExecution(requested, impact.symbols.authority, execution),
                    execution,
                ),
                requested,
            )
            .refined()
    }

    private fun facts() = QueryInvocationFacts(impact.symbols.authority, policy(retainedBytes = 10_000_000))

    private fun terminal() = QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)

    private fun execution(
        selected: QueryRows,
        completion: QueryInvestigationCompletion = QueryInvestigationCompletion.NotEstablished,
        continuation: QueryContinuationState = terminal(),
    ) =
        QueryExecutionResult.Qualified(
                QueryResult(selected, emptyList()),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(1).refined(),
                        setOf(QueryLimitation.RESULT_LIMIT_REACHED),
                    )
                    .refined(),
                continuation,
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
