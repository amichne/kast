package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TraversalQualifiedCompositionTest {
    private val fixture = TraversalTestFixture()
    private val a = fixture.selector("a", 10)
    private val b = fixture.selector("b", 20)
    private val c = fixture.selector("c", 30)
    private val d = fixture.selector("d", 40)

    @Test
    fun `terminal qualified non leaf and leaf reads retain witnessed depth separately`() {
        listOf(false, true).forEach(::assertQualifiedDepth)
    }

    private fun assertQualifiedDepth(leaf: Boolean) {
        val requests = mutableListOf<io.github.amichne.kast.relation.contract.RelationRequest>()
        val relations = RelationOperations { request ->
            requests += request
            when (request.subject.name.value) {
                "a" ->
                    fixture.terminalRelationResult(
                        request,
                        listOf(fixture.endpoint(request.subject, b)),
                        RelationLimitation.UNSUPPORTED_ITEM,
                    )
                "b" ->
                    fixture.completeRelationResult(
                        request,
                        if (leaf) emptyList() else listOf(fixture.endpoint(request.subject, c)),
                    )
                else -> error("Depth two must not read C")
            }
        }
        val result =
            assertInstanceOf(
                TraversalResult.Qualified::class.java,
                runSuspend {
                    traversalOperations(relations, TraversalNanoClock { 0L }).run(fixture.plan(a, depth = 2))
                },
            )
        assertEquals(
            listOf("a" to 0L, "b" to 0L),
            requests.map { it.subject.name.value to it.providerCursor.nextPosition.value },
        )
        assertEquals(
            if (leaf) listOf("b") else listOf("b", "c"),
            result.page.records.map { it.fact.target.name.value },
        )
        assertEquals(if (leaf) 1 else 2, result.page.progress.maximumDepthReached)
        assertEquals(2L, result.page.progress.totalReads)
        assertEquals(setOf(RelationLimitation.UNSUPPORTED_ITEM), result.qualification.relationLimitations)
        assertEquals(
            if (leaf) setOf(TraversalLimitation.ONE_HOP_INCOMPLETE)
            else setOf(TraversalLimitation.ONE_HOP_INCOMPLETE, TraversalLimitation.DEPTH_LIMIT_REACHED),
            result.qualification.limitations,
        )
        assertInstanceOf(TraversalQualification.TerminalIncomplete::class.java, result.qualification)
    }

    @Test
    fun `terminal omission and recoverable child pages survive repeated targets without replay`() {
        for (terminal in listOf(true, false)) {
            val (wide, _) = drain(20, terminal)
            val (paged, reads) = drain(1, terminal)
            assertEquals(wide, paged)
            assertEquals(4, paged.size)
            assertEquals(4, paged.distinct().size)
            assertEquals(listOf("a" to 0L, "b" to 0L, "b" to 1L, "b" to 2L, "d" to 0L, "c" to 0L), reads)
        }
    }

    private fun drain(capacity: Int, terminal: Boolean): Pair<List<String>, List<Pair<String, Long>>> {
        val reads = mutableListOf<Pair<String, Long>>()
        val relations = recordingRelations(reads, terminal)
        val operations = traversalOperations(relations, TraversalNanoClock { 0L })
        var plan = fixture.plan(a, aggregateRecords = capacity, oneHop = fixture.relationBudget(records = capacity))
        val records = mutableListOf<String>()
        repeat(12) {
            val result = runSuspend { operations.run(plan) }
            val page =
                when (result) {
                    is TraversalResult.Complete -> result.page
                    is TraversalResult.Qualified -> {
                        assertEquals(
                            terminal,
                            RelationLimitation.UNSUPPORTED_ITEM in result.qualification.relationLimitations,
                        )
                        result.page
                    }
                    is TraversalResult.Rejected -> error(result.toString())
                }
            records += page.records.map { it.canonicalProjection() }
            val continuation = (result as? TraversalResult.Qualified)?.qualification
            if (continuation is TraversalQualification.Resumable) {
                plan =
                    TraversalPlan.resume(a, RelationMeaning.Callees, plan.budget, continuation.continuation).refined()
            } else {
                assertEquals(terminal, result is TraversalResult.Qualified)
                if (result is TraversalResult.Qualified) {
                    assertEquals(setOf(TraversalLimitation.ONE_HOP_INCOMPLETE), result.qualification.limitations)
                    assertEquals(
                        setOf(RelationLimitation.UNSUPPORTED_ITEM),
                        result.qualification.relationLimitations,
                    )
                }
                assertEquals(2, page.progress.maximumDepthReached)
                return records to reads
            }
        }
        error("Traversal did not drain")
    }

    private fun recordingRelations(reads: MutableList<Pair<String, Long>>, terminal: Boolean) =
        RelationOperations { request ->
            val position = request.providerCursor.nextPosition.value
            reads += request.subject.name.value to position
            if (request.subject.name.value == "a" && terminal) {
                fixture.terminalRelationResult(
                    request,
                    listOf(fixture.endpoint(request.subject, b)),
                    RelationLimitation.UNSUPPORTED_ITEM,
                )
            } else {
                val targets =
                    when (request.subject.name.value) {
                        "a" -> listOf(b)
                        "b" -> listOf(c, c, d)
                        else -> emptyList()
                    }
                pagedResult(request, targets)
            }
        }

    private fun pagedResult(
        request: io.github.amichne.kast.relation.contract.RelationRequest,
        targets: List<io.github.amichne.kast.symbol.contract.SymbolSelector>,
    ): io.github.amichne.kast.relation.contract.RelationReadResult {
        val facts = facts(request, targets)
        val page =
            facts
                .drop(request.providerCursor.nextPosition.value.toInt())
                .take(request.budget.resources.resultLimit.value)
        val batch =
            io.github.amichne.kast.relation.contract.RelationBatch.create(
                    request,
                    page,
                    io.github.amichne.kast.relation.contract.RelationByteCount.parse(
                            page.sumOf { it.canonicalProjection().toByteArray().size.toLong() }
                        )
                        .refined(),
                    io.github.amichne.kast.relation.contract.RelationWorkCount.parse(page.size.toLong()).refined(),
                    io.github.amichne.kast.relation.contract.RelationResultCount.parse(page.size).refined(),
                )
                .refined()
        return if (request.providerCursor.nextPosition.value + page.size < facts.size) {
            val compilation =
                io.github.amichne.kast.relation.contract.RelationCompilation.qualifiedResumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        page.fold(request.providerCursor) { cursor, fact ->
                            cursor.advance(
                                io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor.parse(
                                        fact.canonicalProjection()
                                    )
                                    .refined()
                            )
                        },
                    )
                    .refined()
            io.github.amichne.kast.relation.contract.RelationReadResult.Qualified(
                batch,
                compilation.coverage,
            )
        } else {
            val compilation = io.github.amichne.kast.relation.contract.RelationCompilation.complete(batch)
            io.github.amichne.kast.relation.contract.RelationReadResult.Complete(
                batch,
                compilation.coverage,
            )
        }
    }

    private fun facts(
        request: io.github.amichne.kast.relation.contract.RelationRequest,
        targets: List<io.github.amichne.kast.symbol.contract.SymbolSelector>,
    ) =
        targets
            .mapIndexed { index, target ->
                io.github.amichne.kast.relation.contract.RelationFact.create(
                        request,
                        request.subject,
                        fixture.endpoint(request.subject, target),
                        io.github.amichne.kast.relation.contract.RelationOccurrence.fromBoundary(
                                request.subject.file,
                                100 + index,
                                101 + index,
                            )
                            .refined(),
                        io.github.amichne.kast.relation.contract.RelationProvenance.K2_AUTHORED_SOURCE,
                    )
                    .refined()
            }
            .sorted()

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
