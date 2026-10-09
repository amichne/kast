package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointPartialSymbols
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTraceGroupedRecoveryTest {
    @Test
    fun `bounded trace retains proven grouped facts before final flush`() = runTest {
        val fixture = QueryServiceTest()
        val seed = fixture.selector(fixture.selection())
        val reads = mutableListOf<RelationMeaning>()
        val service = service(fixture, reads)
        val plan = fixture.exactReferencePlan(listOf(seed), listOf(QueryStepSyntax.Trace()))
        val execution =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(fixture.request(plan, 2L, resultLimit = 100)),
            )
        assertEquals(listOf(RelationMeaning.References), reads)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in execution.coverage.limitations)
        val checkpoint = assertInstanceOf(QueryContinuationState.Resumable::class.java, execution.continuation)
        val grouped = (checkpoint.checkpoint as PipelineCheckpoint).identityRows.values.flatMap { it.values }
        assertEquals(listOf(100), grouped.single().connections.map { it.occurrence.range.startInclusive })
        val proof =
            (checkpoint.checkpoint as QueryCheckpointPartialSymbols).partialSymbols(
                ResultLimit.parse(100).refined(),
                QueryByteLimit.parse(100_000).refined(),
            )
        assertEquals(listOf(100), proof.values.single().connections.map { it.occurrence.range.startInclusive })
        assertTrue(
            execution.result.symbolRows().isEmpty(),
            "Partial proof must not be re-emitted as a progressive page",
        )
        assertEquals(0, execution.coverage.knownMinimum.value)
        val resumed = fixture.request(plan, 100L, resultLimit = 100)
        val complete =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(
                    QueryExecutionRequest.create(plan, resumed.lease, resumed.budget, checkpoint.checkpoint).refined()
                ),
            )
        assertEquals(1, complete.coverage.resultCount.value)
        assertEquals(
            listOf(100),
            complete.result.symbolRows().single().connections.map { it.occurrence.range.startInclusive },
        )
    }

    private fun service(fixture: QueryServiceTest, reads: MutableList<RelationMeaning>): QueryService {
        val relations = RelationOperations { read ->
            reads += read.meaning
            val facts =
                if (read.meaning == RelationMeaning.References)
                    listOf(
                        RelationFact.create(
                                read,
                                read.subject,
                                read.subject,
                                RelationOccurrence.fromBoundary(read.subject.file, 100, 101).refined(),
                                RelationProvenance.K2_AUTHORED_SOURCE,
                            )
                            .refined()
                    )
                else emptyList()
            val batch =
                RelationBatch.create(
                        read,
                        facts,
                        RelationByteCount.parse(
                                facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
                            )
                            .refined(),
                        RelationWorkCount.parse(facts.size.toLong()).refined(),
                        RelationResultCount.parse(facts.size).refined(),
                    )
                    .refined()
            val complete = RelationCompilation.complete(batch)
            RelationReadResult.Complete(complete.batch, complete.coverage)
        }
        return QueryService(
            fixture.discoveryEmpty(false),
            fixture.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("Exact trace does not discover declarations") },
            ),
            SourceReadOperations { read ->
                traceMemberSourceRead((read.anchor as SourceReadAnchor.Symbol).selector, emptyList())
            },
            relations,
            unexpectedQueryTraversal(),
            queryTestTraversalCeiling(),
        )
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
