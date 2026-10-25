package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryRetainedBasisRejectionTest {
    @Test
    fun `empty selection cannot reuse a proof under another epoch or ledger`() {
        val fixture = Case()
        val first = QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page()).value()
        val foreign = QueryImpactLedgerFixture(generation = 2)
        val otherLedger = fixture.native.ledger(fixture.native.paths).value()
        val otherRows = QueryRows.ValuePaths.fromInvestigation(otherLedger).value().selectRows(emptyList()).value()
        assertNotSame(fixture.ledger, otherLedger)

        assertBasisRejected(first.capture(foreign.lease, fixture.page(fixture.rows.selectRows(emptyList()).value())))
        assertBasisRejected(first.capture(fixture.native.lease, fixture.page(otherRows)))
        assertSame(
            fixture.ledger,
            ((first.capture(fixture.native.lease, fixture.page()).value().result as QueryRetainedResult.ValuePaths)
                    .rows
                    .accounting as QueryValuePathAccounting.Investigated)
                .ledger,
        )
    }

    @Test
    fun `foreign page failure cannot use an established ledger proof`() {
        val fixture = Case()
        val first = QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page()).value()
        val foreign = QueryImpactLedgerFixture(generation = 2)
        val failure =
            QueryItemFailure.PredicateUnproven(SymbolSelector.issue(foreign.lease, foreign.scope, foreign.evidence))
        val invalid = fixture.page(failures = listOf(failure))

        assertBasisRejected(first.capture(fixture.native.lease, invalid))
        assertInstanceOf(
            QueryRetainedResult.ValuePaths::class.java,
            first.capture(fixture.native.lease, fixture.page()).value().result,
        )
    }

    @Test
    fun `foreign selected row still rejects after an established ledger proof`() {
        val fixture = Case()
        val first = QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page()).value()
        val foreign = QueryImpactLedgerFixture(generation = 2)

        assertBasisRejected(first.capture(fixture.native.lease, fixture.page(QueryRows.ValuePaths.of(foreign.paths))))
    }

    @Test
    fun `foreign checkpoint rejects after an established ledger proof`() {
        val fixture = Case()
        val first = QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page()).value()
        val foreign = QueryImpactLedgerFixture(generation = 2)
        val checkpoint =
            object : QueryCheckpoint {
                override val lease = foreign.lease
                override val plan = fixture.plan()
                override val retainedBytes = 0L
            }

        assertBasisRejected(
            first.capture(
                fixture.native.lease,
                fixture.page().copy(continuation = QueryContinuationState.Resumable(checkpoint)),
            )
        )
        assertInstanceOf(
            QueryRetainedCapture.Captured::class.java,
            first.capture(fixture.native.lease, fixture.page()).value(),
        )
    }

    @Test
    fun `failed first capture issues no proof and leaves fresh validation necessary`() {
        val fixture = Case()
        val events = mutableListOf<QueryRetainedBasisEvent>()
        val observation = QueryRetainedBasisObservation(events::add)
        val foreign = QueryImpactLedgerFixture(generation = 2)
        val failure =
            QueryItemFailure.PredicateUnproven(SymbolSelector.issue(foreign.lease, foreign.scope, foreign.evidence))

        assertBasisRejected(
            QueryRetainedCapture.Initial.capture(
                fixture.native.lease,
                fixture.page(failures = listOf(failure)),
                observation,
            )
        )
        QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page(), observation).value()

        assertEquals(2, events.count { it == QueryRetainedBasisEvent.LEDGER_CHECK_STARTED })
        assertEquals(0, events.count { it == QueryRetainedBasisEvent.LEDGER_PROOF_REUSED })
    }

    @Test
    fun `execution rejection precedes an incompatible capture proof`() {
        val fixture = Case()
        val first = QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page()).value()
        val foreign = QueryImpactLedgerFixture(generation = 2)
        val rejected = QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)

        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.EXECUTION_REJECTED),
            first.capture(foreign.lease, rejected),
        )
    }

    @Test
    fun `witness rows keep basis rejection before presentation only rejection`() {
        val fixture = Case()
        val first = QueryRetainedCapture.Initial.capture(fixture.native.lease, fixture.page()).value()
        val otherLedger = fixture.native.ledger(fixture.native.paths).value()
        val view = QueryImpactWitnessView.create(otherLedger, QueryImpactWitnessSection.FINDINGS, 0, 1).value()
        val witness = fixture.page().copy(result = QueryResult(QueryRows.ImpactWitness.of(view), emptyList()))
        val foreign = QueryImpactLedgerFixture(generation = 2)

        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.PRESENTATION_ONLY_ROWS),
            first.capture(fixture.native.lease, witness),
        )
        assertBasisRejected(first.capture(foreign.lease, witness))
    }

    private fun assertBasisRejected(result: Refinement<QueryRetainedCapture.Captured, QueryRetainedResultFailure>) =
        assertEquals(Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH), result)

    private class Case {
        val native = QueryImpactLedgerFixture()
        val ledger = native.ledger(native.paths).value()
        val rows = QueryRows.ValuePaths.fromInvestigation(ledger).value()

        fun page(selected: QueryRows.ValuePaths = rows, failures: List<QueryItemFailure> = emptyList()) =
            QueryExecutionResult.Qualified(
                QueryResult(selected, failures),
                QueryCoverage.Qualified.create(
                        QueryCount.parse(selected.values.size).value(),
                        setOf(QueryLimitation.RELATION_INCOMPLETE),
                    )
                    .value(),
            )

        fun plan(): AdmittedQueryPlan =
            (QueryPlanCompiler.admit(
                    QueryPlanSyntax(
                        QuerySourceSyntax.Impact(
                            QueryImpactSource.admit(
                                    listOf(native.producerWitness),
                                    emptyList(),
                                    emptyList(),
                                    native.domain.boundary,
                                )
                                .value()
                        ),
                        emptyList(),
                        QueryOutputSyntax.ValuePaths,
                    )
                ) as QueryPlanAdmission.Admitted)
                .plan
    }
}

private fun <T, F> Refinement<T, F>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Basis fixture rejected: $failure")
    }
