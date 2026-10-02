package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExactReferences
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QuerySourceSyntax
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOmissionSamples
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderConsumption
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import io.github.amichne.kast.traversal.service.TraversalNanoClock
import io.github.amichne.kast.traversal.service.traversalOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The production traversal and query schedulers consume one retained relation inventory together. */
class QueryWalkRetainedProgressTest {
    @Test
    fun depthBoundDrainsAcrossResultPages() = runTest {
        val graph =
            mapOf(
                "PaymentService" to listOf("B", "C", "D", "E"),
                "B" to listOf("F"),
                "C" to listOf("G"),
                "D" to listOf("H"),
                "E" to listOf("I"),
            )
        val expected =
            listOf(
                Triple("PaymentService", "B", 1),
                Triple("PaymentService", "C", 1),
                Triple("PaymentService", "D", 1),
                Triple("PaymentService", "E", 1),
                Triple("B", "F", 2),
                Triple("C", "G", 2),
                Triple("D", "H", 2),
                Triple("E", "I", 2),
            )
        val baseline = RetainedWalkFixture(graph, depth = 2).drain(resultLimit = 100)
        val complete = assertInstanceOf(QueryExecutionResult.Complete::class.java, baseline.single())
        assertEquals(8, complete.coverage.resultCount.value)
        for (capacity in listOf(1, 2)) {
            val fixture = RetainedWalkFixture(graph, depth = 2)
            val pages = fixture.drain(resultLimit = capacity)
            val final = assertInstanceOf(QueryExecutionResult.Complete::class.java, pages.last())
            val records = pages.walkRecords()
            assertEquals(
                expected,
                records
                    .map { Triple(it.fact.source.name.value, it.related.name.value, it.depth.value) }
                    .sortedWith(compareBy({ it.third }, { it.first }, { it.second })),
            )
            val semanticRecords = records.map(TraversalRecord::canonicalProjection)
            assertEquals(
                baseline.walkRecords().map(TraversalRecord::canonicalProjection).sorted(),
                semanticRecords.sorted(),
            )
            assertEquals(8, semanticRecords.distinct().size)
            assertEquals(complete.coverage, final.coverage)
            assertResumableDepthBoundPages(pages, capacity)
            assertEquals(
                mapOf("PaymentService" to (4 / capacity), "B" to 1, "C" to 1, "D" to 1, "E" to 1),
                fixture.reads.groupingBy { it.first }.eachCount(),
            )
        }
    }

    @Test
    fun `retained one hop page limits discharge after exact child exhaustion`() = runTest {
        val fixture = RetainedWalkFixture()
        val pages = fixture.drain()
        fixture.assertExactDrain(pages)
        val final = assertInstanceOf(QueryExecutionResult.Complete::class.java, pages.last())
        assertEquals(2, final.coverage.resultCount.value)
    }

    @Test
    fun `retained one hop progress preserves measured permanent omission evidence`() = runTest {
        assertPreservedOmission(RelationLimitation.UNRESOLVED_TARGET)
    }

    @Test
    fun `measured page bound omissions remain incomplete through retained progress`() = runTest {
        assertPreservedOmission(RelationLimitation.RESULT_LIMIT_REACHED)
    }

    private fun assertResumableDepthBoundPages(pages: List<QueryExecutionResult>, capacity: Int) {
        assertTrue(pages.all { it.result().symbolRows().size <= capacity && it.result().failures.isEmpty() })
        pages.dropLast(1).forEach { page ->
            val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, page)
            val continuation = assertInstanceOf(QueryContinuationState.Resumable::class.java, qualified.continuation)
            (continuation.checkpoint as PipelineCheckpoint)
                .tasks
                .filterIsInstance<PipelineTask.Walk>()
                .mapNotNull { it.cursor }
                .forEach { cursor -> assertTrue(cursor.checkpoint.frontier.all { it.depth.value < 2 }) }
        }
        assertEquals(QueryWalkCoverage.Complete, pages.flatMap { it.result().walkObservations }.last().coverage)
    }

    private suspend fun assertPreservedOmission(reason: RelationLimitation) {
        val fixture = RetainedWalkFixture()
        val expected = fixture.measuredOmission(reason)
        val pages = fixture.drain(listOf(expected))
        val final = assertInstanceOf(QueryExecutionResult.Qualified::class.java, pages.last())
        fixture.assertExactDrain(pages)
        assertEquals(listOf(QueryLimitation.TRAVERSAL_INCOMPLETE), final.coverage.limitations)
        assertEquals(
            QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
            final.continuation,
        )
        val observations = pages.flatMap { it.result().walkObservations }
        assertTrue(observations.flatMap { it.partialExpansions }.flatMap { it.omissions }.contains(expected))
        assertTrue(observations.flatMap { it.inheritedOmissions }.flatMap { it.omissions }.contains(expected))
    }
}

private class RetainedWalkFixture(
    private val graph: Map<String, List<String>> =
        mapOf("PaymentService" to listOf("B", "C"), "B" to emptyList(), "C" to emptyList()),
    depth: Int = 3,
) {
    private val scope = QueryServiceTest()
    private val selected = scope.selector(scope.selection())
    val reads = mutableListOf<Pair<String, Long>>()
    private val plan =
        scope.admittedPlan(
            QuerySourceSyntax.ExactReferences(QueryExactReferences.from(listOf(selected)).refinedWalkProgress()),
            listOf(
                QueryStepSyntax.Walk(
                    RelationMeaning.Callees,
                    TraversalDepthLimit.parse(depth).refinedWalkProgress(),
                    TraversalStrategy.BreadthFirst,
                )
            ),
            QueryOutputSyntax.TraversalRecords,
        )

    fun measuredOmission(reason: RelationLimitation): RelationOmissionEvidence =
        RelationOmissionEvidence.fromObservedPage(
                io.github.amichne.kast.relation.contract.RelationProviderKind.INTELLIJ_CALLEES_V2,
                reason,
                RelationOmissionMeasurement.ObservedOnPage(RelationWorkCount.parse(1).refinedWalkProgress()),
                RelationOmissionSamples.observed(
                    listOf(RelationOccurrence.fromBoundary(selected.file, 300, 301).refinedWalkProgress())
                ),
            )
            .refinedWalkProgress()

    suspend fun drain(
        firstOmissions: List<RelationOmissionEvidence> = emptyList(),
        resultLimit: Int = 1,
    ): List<QueryExecutionResult> {
        val relations = RelationOperations { read -> relationPage(read, firstOmissions) }
        val service =
            QueryService(
                scope.discoveryEmpty(false),
                scope.exactOperations(
                    describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                    resolve = { error("No discovery expected") },
                ),
                SourceReadOperations { error("No source effect expected") },
                relations,
                traversalOperations(relations, TraversalNanoClock { 0L }),
                queryTestTraversalCeiling(),
                clock = QueryNanoClock { 0L },
            )
        val initial = scope.request(plan, workLimit = 100L, resultLimit = resultLimit)
        var request = initial
        val pages = mutableListOf<QueryExecutionResult>()
        repeat(64) {
            val page = service.run(request)
            pages += page
            val continuation = (page as? QueryExecutionResult.Qualified)?.continuation
            if (continuation !is QueryContinuationState.Resumable) return pages
            request =
                QueryExecutionRequest.create(plan, initial.lease, initial.budget, continuation.checkpoint)
                    .refinedWalkProgress()
        }
        error("The retained walk did not exhaust within sixty-four pages")
    }

    fun assertExactDrain(pages: List<QueryExecutionResult>) {
        assertEquals(listOf("B", "C"), pages.flatMap { it.result().symbolRows() }.map { it.description.name.value })
        assertEquals(listOf("PaymentService" to 0L, "PaymentService" to 1L), reads.take(2))
        assertEquals(listOf("B" to 0L, "C" to 0L), reads.drop(2).sortedBy { it.first })
        assertTrue(pages.all { it.result().failures.isEmpty() })
    }

    private fun relationPage(
        read: RelationRequest,
        firstOmissions: List<RelationOmissionEvidence>,
    ): RelationReadResult {
        val position =
            (read.position as? RelationReadPosition.Resume)?.continuation?.providerState?.consumedLocatorCount?.value
                ?: 0L
        reads += read.subject.name.value to position
        val facts = graph.getValue(read.subject.name.value).mapIndexed { index, name -> fact(read, name, index) }
        check(position <= facts.size) { "Unexpected relation cursor" }
        val emitted = facts.drop(position.toInt()).take(read.budget.resources.resultLimit.value)
        val inventory = (read.position as? RelationReadPosition.Resume)?.continuation?.providerState ?: inventory(facts)
        val successor =
            emitted.fold(inventory) { state, fact -> state.consume(RelationProviderConsumption.GraphConfirmed(fact)) }
        val omissions =
            if (read.subject.name.value == "PaymentService" && position == 0L) firstOmissions else emptyList()
        val batch = observedBatch(read, emitted, omissions)
        val compilation =
            if (successor.hasUnfinishedWork)
                RelationCompilation.qualifiedResumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED) + omissions.map { it.reason },
                        successor.providerCursor,
                        successor,
                    )
                    .refinedWalkProgress()
            else RelationCompilation.complete(batch)
        return when (compilation) {
            is RelationCompilation.Complete -> RelationReadResult.Complete(batch, compilation.coverage)
            is RelationCompilation.Qualified -> RelationReadResult.Qualified(batch, compilation.coverage)
            is RelationCompilation.Rejected -> error("Unexpected relation compilation failure")
        }
    }

    private fun observedBatch(
        read: RelationRequest,
        emitted: List<RelationFact>,
        omissions: List<RelationOmissionEvidence>,
    ): RelationBatch =
        RelationBatch.create(
                read,
                emitted.sorted(),
                RelationByteCount.parse(
                        emitted.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
                    )
                    .refinedWalkProgress(),
                RelationWorkCount.parse(emitted.size.toLong()).refinedWalkProgress(),
                RelationResultCount.parse(emitted.size).refinedWalkProgress(),
            )
            .refinedWalkProgress()
            .withOmissions(omissions)
            .refinedWalkProgress()

    private fun inventory(facts: List<RelationFact>): RelationProviderState =
        RelationProviderState.callees(
            facts.map { fact ->
                RelationProviderLocator.Callee.Reference(
                    fact.occurrence.file,
                    fact.occurrence.range,
                    RelationProviderItemDescriptor.parse("occurrence:${fact.occurrence.range.startInclusive}")
                        .refinedWalkProgress(),
                )
            }
        )

    private fun fact(read: RelationRequest, name: String, index: Int): RelationFact {
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    selected.file,
                    100 + index * 10,
                    110 + index * 10,
                    name,
                    "sample.$name",
                    selected.kind,
                    CanonicalCompilerSignature.classLike("sample.$name").refinedWalkProgress(),
                )
                .refinedWalkProgress()
        val target = RelationEndpoint.resolve(selected.lease, read.subject.scope, evidence).refinedWalkProgress()
        return RelationFact.create(
                read,
                read.subject,
                target,
                RelationOccurrence.fromBoundary(selected.file, 200 + index, 201 + index).refinedWalkProgress(),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refinedWalkProgress()
    }
}

private fun List<QueryExecutionResult>.walkRecords(): List<TraversalRecord> = flatMap {
    it.result().symbolRows()
}
    .flatMap { (it.walkArrival as QueryWalkArrival.Proven).records }

private fun QueryExecutionResult.result() =
    when (this) {
        is QueryExecutionResult.Complete -> result
        is QueryExecutionResult.Qualified -> result
        is QueryExecutionResult.Rejected -> error("Unexpected query rejection: $reason")
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedWalkProgress(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
