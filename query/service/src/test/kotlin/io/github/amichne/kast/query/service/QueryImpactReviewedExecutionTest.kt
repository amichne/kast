package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactClosure
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValuePathSegment
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactReviewedExecutionTest {
    @Test
    fun `stale reviewed owner rejects the read without a completed route`() = runTest {
        val fixture = ReviewedImpactExecutionFixture()
        val stale = ReviewedImpactExecutionFixture(generation = 2)
        var reads = 0
        val port = ValueFlowCompilerPort { request ->
            reads++
            assertEquals(fixture.producer.site, request.source)
            val observation =
                ValueFlowStep.fromCompiler(
                    source = stale.producer.site,
                    transfers = listOf(stale.compiler.first()),
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain =
                        RelationRequest.start(
                            stale.producer.site.enclosing,
                            RelationMeaning.References,
                            request.budget,
                            request.boundary,
                        ),
                    examinedWorkUnits =
                        when (val work = RelationWorkCount.parse(1)) {
                            is Refinement.Refined -> work.value
                            is Refinement.Rejected -> error("Fixture work count rejected")
                        },
                )
            when (observation) {
                is Refinement.Refined -> ValueFlowRead.Observed(observation.value)
                is Refinement.Rejected -> error("Fixture observation rejected: ${observation.failure}")
            }
        }
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                fixture.base.service(port).run(fixture.request),
            )
        assertEquals(1, reads)
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows)
        val terminal =
            assertInstanceOf(QueryImpactTerminal.Unresolved.ReadRejected::class.java, rows.values.single().terminal)
        val rejection = assertInstanceOf(QueryImpactReadRejection.Contract::class.java, terminal.rejection)
        assertEquals(ValueFlowStepFailure.BASIS_MISMATCH, rejection.cause)
        assertEquals(fixture.producer.site, rejection.source)
        assertTrue(rows.values.single().steps.isEmpty())
    }

    @Test
    fun `empty model execution completes through reviewed element payload under original grants`() = runTest {
        val fixture = ReviewedImpactExecutionFixture()
        val native = fixture.script()
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                fixture.base.service(native.port).run(fixture.request),
            )
        native.assertConsumed()
        val rows = assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows)
        assertEquals(QueryValuePathAccountingStatus.Conserved, rows.accountingStatus)
        val path = rows.values.single()
        assertEquals(fixture.output, path.destination)
        assertEquals(
            listOf(ValuePathSegment.Element, ValuePathSegment.Field(fixture.field)),
            path.destination.valuePath.segments,
        )
        assertSame(fixture.field, (path.destination.valuePath.segments.last() as ValuePathSegment.Field).field)
        assertEquals(fixture.compiler, path.steps.filterIsInstance<QueryImpactStep.Compiler>().map { it.transfer })
        val reviewed = path.steps.filterIsInstance<QueryImpactStep.ReviewedLibrary>().single().transfer
        assertEquals(fixture.reviewed, reviewed)
        assertEquals(reviewed.source.valuePath.segments, reviewed.target.valuePath.segments.drop(1))
        val ledger = (rows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertEquals(QueryImpactClosure.Discharged, ledger.closure)
        assertEquals(QueryImpactFlowSemantics.KOTLIN_FORWARD_V1, ledger.semantics)
        assertEquals(fixture.domain, ledger.domain)
        assertTrue(ledger.representationModels.isEmpty())
        assertTrue(ledger.boundaryModels.isEmpty())
        assertEquals(6, native.examined.size)
        assertTrue(native.grants.all { it.resources == ResourceBudget.RunnerControlled })
        assertEquals(setOf(100), native.grants.map { it.outputResults.value }.toSet())
        assertEquals(setOf(1_048_576L), native.grants.map { it.returnedBytes.value }.toSet())
        val modelCount =
            when (val count = QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.MODELS)) {
                is Refinement.Refined -> count.value
                is Refinement.Rejected -> error("Model count rejected: ${count.failure}")
            }
        assertEquals(0, modelCount.value)
        assertEquals(1, ledger.observations.sumOf { it.reviewedTransfers.size })
        assertEquals(4, ledger.observations.sumOf { it.transfers.size })
    }
}
