package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedBasisEvent
import io.github.amichne.kast.query.contract.QueryRetainedBasisObservation
import io.github.amichne.kast.query.contract.QueryRetainedCapture
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class QueryInvocationBasisWorkTest {
    @Test
    fun `equal live references cannot replace the captured authority owner`() {
        val symbols = RelationPagingFixture.live()
        val firstAuthority = symbols.authority as LiveSemanticReadAuthority
        val otherAuthority = LiveReadAuthorityFixture.create(firstAuthority.workspaceRoot)
        val impact = ImpactWitnessPresentationFixture(symbols = symbols)
        val rows = QueryRows.ValuePaths.fromInvestigation(impact.ledger).value().selectRows(emptyList()).value()
        val execution =
            QueryExecutionResult.Qualified(
                QueryResult(rows, emptyList()),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(0).value(),
                        setOf(QueryLimitation.ROW_SELECTION_INCOMPLETE),
                    )
                    .value(),
            )
        val first = QueryRetainedCapture.Initial.capture(firstAuthority, execution).value()

        assertEquals(firstAuthority.reference, otherAuthority.reference)
        assertEquals(firstAuthority.identity, otherAuthority.identity)
        assertNotEquals(firstAuthority, otherAuthority)
        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH),
            first.capture(otherAuthority, execution),
        )
        assertSame(firstAuthority, first.capture(firstAuthority, execution).value().result.lease)
    }

    @Test
    fun `accepted pages share one full ledger basis scan`(reporter: TestReporter) {
        val case = Case()
        val facts = case.facts()

        facts.append(case.page(case.rows.selectRows(emptyList()).value()), Long.MAX_VALUE).value()
        facts.append(case.page(case.rows), Long.MAX_VALUE).value()

        val scans = case.events.count { it == QueryRetainedBasisEvent.LEDGER_CHECK_STARTED }
        reporter.publishEntry("ledger-basis-scans", scans.toString())
        assertEquals(1, scans)
        assertEquals(1, case.events.count { it == QueryRetainedBasisEvent.LEDGER_PROOF_REUSED })
        assertSame(case.impact.ledger, case.finishedLedger(facts))
    }

    @Test
    fun `capacity rejected first ledger cannot prevent a different valid first ledger`() {
        val case = Case()
        val facts = case.facts()
        val first = case.page(case.rows.selectRows(emptyList()).value())
        val rejected = assertInstanceOf(Refinement.Rejected::class.java, facts.append(first, 1L))
        assertEquals(
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            assertInstanceOf(QueryInvocationTransition.Stopped::class.java, rejected.failure).reason,
        )
        assertFalse(facts.hasObservedRowShape)
        assertEquals(QueryInvocationFactsRetentionEstimate.Empty, facts.retentionEstimate)
        val replacement = case.otherLedger()

        facts.append(case.page(QueryRows.ValuePaths.fromInvestigation(replacement).value()), Long.MAX_VALUE).value()

        assertSame(replacement, case.finishedLedger(facts))
        assertEquals(2, case.events.count { it == QueryRetainedBasisEvent.LEDGER_CHECK_STARTED })
        assertEquals(0, case.events.count { it == QueryRetainedBasisEvent.LEDGER_PROOF_REJECTED })
    }

    @Test
    fun `accepted ledger still rejects a different physical ledger before capture`() {
        val case = Case()
        val facts = case.facts()
        facts.append(case.page(case.rows.selectRows(emptyList()).value()), Long.MAX_VALUE).value()
        val before = facts.retentionEstimate
        val other = QueryRows.ValuePaths.fromInvestigation(case.otherLedger()).value()

        val rejected = assertInstanceOf(Refinement.Rejected::class.java, facts.append(case.page(other), Long.MAX_VALUE))

        assertEquals(
            QueryInvocationStop.INVALID_STATE,
            assertInstanceOf(QueryInvocationTransition.Stopped::class.java, rejected.failure).reason,
        )
        assertEquals(before, facts.retentionEstimate)
        assertEquals(1, case.events.count { it == QueryRetainedBasisEvent.LEDGER_CHECK_STARTED })
        facts.append(case.page(case.rows), Long.MAX_VALUE).value()
        assertSame(case.impact.ledger, case.finishedLedger(facts))
    }

    private class Case {
        val impact = ImpactWitnessPresentationFixture()
        val rows = QueryRows.ValuePaths.fromInvestigation(impact.ledger).value()
        val events = mutableListOf<QueryRetainedBasisEvent>()
        private val projection = QueryOutcomeProjection(impact.symbols.references, QueryStateStore())

        fun facts() =
            QueryInvocationFacts(
                impact.symbols.authority,
                QueryInvocationPolicy(
                    ResultLimit.parse(10).value(),
                    QueryByteLimit.parse(100_000).value(),
                    QueryByteLimit.parse(Long.MAX_VALUE).value(),
                    CanonicalQueryCliDocuments::previewBytes,
                ),
                QueryRetainedBasisObservation(events::add),
            )

        fun page(selected: QueryRows.ValuePaths): SymbolInvocationPage {
            val execution =
                QueryExecutionResult.Qualified(
                        QueryResult(selected, emptyList()),
                        QueryCoverage.Qualified.create(
                                QueryCount.parse(1).value(),
                                setOf(QueryLimitation.RESULT_LIMIT_REACHED),
                            )
                            .value(),
                    )
                    .observedWork(QueryWorkCount.parse(1).value())
            val projected = projection.projectExecution(impact.request, impact.symbols.authority, execution)
            return SymbolInvocationPage.admit(QueryInvocationPage(projected, execution), impact.request).value()
        }

        fun otherLedger(): QueryImpactLedger {
            val ledger = impact.ledger
            return QueryImpactLedger.fromEvidence(
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
                .value()
        }

        fun finishedLedger(facts: QueryInvocationFacts): QueryImpactLedger {
            val finished =
                facts.finish(
                    QueryInvocationTransition.Stopped(QueryInvocationStop.COMPLETED),
                    QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
                )
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, finished.execution)
            return ((complete.result.rows as QueryRows.ValuePaths).accounting as QueryValuePathAccounting.Investigated)
                .ledger
        }
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invocation basis fixture rejected: $failure")
    }
