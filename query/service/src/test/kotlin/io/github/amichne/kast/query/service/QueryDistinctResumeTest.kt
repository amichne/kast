package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryDistinctResumeTest {
    @Test
    fun `relation cursor and first distinct evidence survive a work page without prefix replay`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            val positions = mutableListOf<Long>()
            var descriptions = 0
            val service =
                QueryService(
                    discoveryEmpty(false),
                    exactOperations(
                        describe = {
                            descriptions++
                            SymbolDescriptionResult.Described(SymbolDescription.from(it))
                        },
                        resolve = { error("No discovery expected") },
                    ),
                    SourceReadOperations { error("No source expected") },
                    relationPages(selected, positions),
                    unexpectedQueryTraversal(),
                    queryTestTraversalCeiling(),
                )
            val first =
                request(
                    exactReferencePlan(
                        listOf(selected),
                        listOf(QueryStepSyntax.Related(RelationMeaning.Callees), QueryStepSyntax.Distinct),
                    ),
                    workLimit = 2L,
                    resultLimit = 1,
                )
            val page = service.run(first) as QueryExecutionResult.Qualified
            assertEquals(0, page.symbolCount())
            assertEquals(listOf(0L), positions)
            assertEquals(1, descriptions)
            val checkpoint =
                (page.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
            val resumed = request(first.plan, workLimit = 8L, resultLimit = 1)
            val last =
                service.run(QueryExecutionRequest.create(first.plan, first.lease, resumed.budget, checkpoint).refined())
            val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, last)
            assertEquals(1, complete.symbolCount())
            assertEquals(1, complete.result.symbolRows().single().connections.size)
            assertEquals(8, complete.result.symbolRows().single().connections.single().occurrence.range.startInclusive)
            assertEquals(listOf(0L, 1L), positions)
            assertEquals(1, descriptions)
        }
    }

    private fun relationPages(
        selected: io.github.amichne.kast.symbol.contract.SymbolSelector,
        positions: MutableList<Long>,
    ): RelationOperations = RelationOperations { read ->
        val position = read.providerCursor.nextPosition.value
        positions += position
        val fact =
            RelationFact.create(
                    read,
                    read.subject,
                    read.subject,
                    RelationOccurrence.fromBoundary(selected.file, 8 + position.toInt(), 9 + position.toInt())
                        .refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val batch =
            RelationBatch.create(
                    read,
                    listOf(fact),
                    RelationByteCount.parse(fact.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(1).refined(),
                )
                .refined()
        if (position == 0L) {
            val coverage =
                RelationIncompleteCoverage.resumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        read.providerCursor.advance(
                            RelationProviderItemDescriptor.parse("sample.catalog.first").refined()
                        ),
                    )
                    .refined()
            RelationReadResult.Qualified(batch, coverage)
        } else {
            val complete = RelationCompilation.complete(batch)
            RelationReadResult.Complete(batch, complete.coverage)
        }
    }

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Expected refinement")
        }
}
