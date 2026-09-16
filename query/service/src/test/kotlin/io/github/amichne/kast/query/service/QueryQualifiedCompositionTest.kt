package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
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

class QueryQualifiedCompositionTest {
    @Test
    fun `terminal qualified edges continue through non leaf and leaf expansions`() = runTest {
        for (leaf in listOf(false, true)) {
            QueryServiceTest().apply {
                val selected = selector(selection())
                val reads = mutableListOf<Pair<String, Long>>()
                val service = recordingService(selected, reads, leaf = leaf)
                val result = service.run(request(twoHopPlan(selected), workLimit = 100L))
                val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
                assertEquals(listOf("PaymentService" to 0L, "B" to 0L), reads)
                assertEquals(listOf(QueryLimitation.RELATION_INCOMPLETE), qualified.coverage.limitations)
                assertEquals(
                    io.github.amichne.kast.query.contract.QueryContinuationState.Terminal(
                        io.github.amichne.kast.query.contract.QueryTerminalReason.UPSTREAM_INCOMPLETE
                    ),
                    qualified.continuation,
                )
                val symbols =
                    (qualified.result.items as io.github.amichne.kast.query.contract.QueryResultSet.Symbols).values
                assertEquals(if (leaf) emptyList() else listOf("C"), symbols.map { it.description.name.value }.sorted())
                symbols.forEach { symbol ->
                    assertEquals(listOf("PaymentService", "B"), symbol.connections.map { it.source.name.value })
                    assertEquals(
                        listOf("B", symbol.description.name.value),
                        symbol.connections.map { it.target.name.value },
                    )
                }
            }
        }
    }

    @Test
    fun `terminal omissions survive distinct multi output pages and child cursors`() = runTest {
        QueryServiceTest().apply {
            val selected = selector(selection())
            suspend fun drain(
                capacity: Int,
                terminal: Boolean,
            ): Pair<List<io.github.amichne.kast.query.contract.QuerySymbol>, List<Pair<String, Long>>> {
                val reads = mutableListOf<Pair<String, Long>>()
                val service = recordingService(selected, reads, terminal = terminal, repeated = true)
                val first = request(twoHopPlan(selected), workLimit = 100L, resultLimit = capacity)
                var next = first
                val symbols = mutableListOf<io.github.amichne.kast.query.contract.QuerySymbol>()
                repeat(10) {
                    val page = service.run(next)
                    val result =
                        when (page) {
                            is QueryExecutionResult.Complete -> page.result
                            is QueryExecutionResult.Qualified -> {
                                assertEquals(terminal, QueryLimitation.RELATION_INCOMPLETE in page.coverage.limitations)
                                page.result
                            }
                            is QueryExecutionResult.Rejected -> error(page.toString())
                        }
                    symbols += (result.items as io.github.amichne.kast.query.contract.QueryResultSet.Symbols).values
                    val continuation = (page as? QueryExecutionResult.Qualified)?.continuation
                    if (continuation is io.github.amichne.kast.query.contract.QueryContinuationState.Resumable) {
                        next =
                            QueryExecutionRequest.create(first.plan, first.lease, first.budget, continuation.checkpoint)
                                .refined()
                    } else {
                        assertEquals(terminal, page is QueryExecutionResult.Qualified)
                        return symbols to reads
                    }
                }
                error("Query did not drain")
            }
            for (terminal in listOf(true, false)) {
                val (wide, _) = drain(20, terminal)
                val (paged, reads) = drain(1, terminal)
                assertEquals(listOf("C", "D"), paged.map { it.description.name.value }.sorted())
                assertEquals(
                    wide.map { it.selector.fingerprint to it.connections.map(RelationFact::canonicalProjection) },
                    paged.map { it.selector.fingerprint to it.connections.map(RelationFact::canonicalProjection) },
                )
                assertEquals(listOf("PaymentService" to 0L, "B" to 0L, "B" to 1L, "B" to 2L), reads)
            }
        }
    }

    private fun QueryServiceTest.twoHopPlan(selected: io.github.amichne.kast.symbol.contract.SymbolSelector) =
        exactReferencePlan(
            listOf(selected),
            listOf(
                QueryStepSyntax.Related(RelationMeaning.Callees),
                QueryStepSyntax.Related(RelationMeaning.Callees),
                QueryStepSyntax.Distinct,
            ),
        )

    private fun QueryServiceTest.recordingService(
        selected: io.github.amichne.kast.symbol.contract.SymbolSelector,
        reads: MutableList<Pair<String, Long>>,
        leaf: Boolean = false,
        terminal: Boolean = true,
        repeated: Boolean = false,
    ) =
        QueryService(
            discoveryEmpty(false),
            exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { error("No discovery expected") },
            ),
            SourceReadOperations { error("No source expected") },
            RelationOperations { read ->
                val position = read.providerCursor.nextPosition.value
                reads += read.subject.name.value to position
                val names =
                    when (read.subject.name.value) {
                        "PaymentService" -> listOf("B")
                        "B" -> if (leaf) emptyList() else if (repeated) listOf("C", "C", "D") else listOf("C")
                        else -> error("Unexpected subject")
                    }
                val facts =
                    names
                        .mapIndexed { index, name ->
                            val evidence =
                                io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromBoundary(
                                        selected.file,
                                        30 + name.single().code,
                                        31 + name.single().code,
                                        name,
                                        "sample.$name",
                                        io.github.amichne.kast.symbol.contract.CompilerSymbolKind.CLASSLIKE,
                                        io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.classLike(
                                                "sample.$name"
                                            )
                                            .refined(),
                                    )
                                    .refined()
                            val target =
                                io.github.amichne.kast.relation.contract.RelationEndpoint.resolve(
                                        read.subject.lease,
                                        read.subject.scope,
                                        evidence,
                                    )
                                    .refined()
                            RelationFact.create(
                                    read,
                                    read.subject,
                                    target,
                                    RelationOccurrence.fromBoundary(selected.file, 200 + index, 201 + index).refined(),
                                    RelationProvenance.K2_AUTHORED_SOURCE,
                                )
                                .refined()
                        }
                        .sorted()
                val page = facts.drop(position.toInt()).take(read.budget.resources.resultLimit.value)
                val batch =
                    RelationBatch.create(
                            read,
                            page,
                            RelationByteCount.parse(
                                    page.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
                                )
                                .refined(),
                            RelationWorkCount.parse(page.size.toLong()).refined(),
                            RelationResultCount.parse(page.size).refined(),
                        )
                        .refined()
                val compilation =
                    when {
                        read.subject.name.value == "PaymentService" && terminal ->
                            RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM))
                                .refined()
                        position + page.size < facts.size ->
                            RelationCompilation.qualifiedResumable(
                                    batch,
                                    setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                                    page.fold(read.providerCursor) { cursor, fact ->
                                        cursor.advance(
                                            RelationProviderItemDescriptor.parse(fact.canonicalProjection()).refined()
                                        )
                                    },
                                )
                                .refined()
                        else -> RelationCompilation.complete(batch)
                    }
                when (compilation) {
                    is RelationCompilation.Complete -> RelationReadResult.Complete(batch, compilation.coverage)
                    is RelationCompilation.Qualified -> RelationReadResult.Qualified(batch, compilation.coverage)
                    is RelationCompilation.Rejected -> error(compilation.toString())
                }
            },
            clock = QueryNanoClock { 0L },
        )

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Expected refinement")
        }
}
