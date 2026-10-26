package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.LocalBindingReadRemainder
import io.github.amichne.kast.relation.contract.ValueFlowRead
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class PipelineCheckpointHashWorkTest {
    @Test
    fun `native only progress avoids quadratic checkpoint history comparisons`(reporter: TestReporter) = runTest {
        val measurements =
            listOf(128, 256, 512).map { pages ->
                val case = QueryImpactProofResumeTest.NativeResumeCase(pages + 1)
                val checkpoints = nativeCheckpoints(case, pages)
                val work = measureHistory(checkpoints)
                reporter.publishEntry(
                    "pages-$pages",
                    "$work providerVisits=${case.enumeratedReferences}",
                )
                assertTrue(work.remainderVisits <= 3L * pages, "Hashing repeatedly traversed native progress metadata")
                assertNativeCollisions(checkpoints, case)
                finishNativePages(case, checkpoints.last(), pages)
                work
            }
        val comparisons = measurements.map { it.comparisons }
        assertTrue(comparisons[2] <= 3 * comparisons[1], "Doubling native pages amplified equality work: $comparisons")
        assertEquals(
            listOf(0L, 0L, 0L),
            measurements.map { it.evidenceVisits },
            "History lookup traversed retained evidence",
        )
    }

    private suspend fun nativeCheckpoints(
        case: QueryImpactProofResumeTest.NativeResumeCase,
        pages: Int,
    ): List<PipelineCheckpoint> {
        val checkpoints = mutableListOf<PipelineCheckpoint>()
        repeat(pages) { index ->
            val result =
                assertInstanceOf(
                    QueryExecutionResult.Qualified::class.java,
                    case.service.run(case.request(1, checkpoints.lastOrNull())),
                )
            assertTrue(assertInstanceOf(QueryRows.ValuePaths::class.java, result.result.rows).values.isEmpty())
            val checkpoint =
                assertInstanceOf(
                    PipelineCheckpoint::class.java,
                    assertInstanceOf(QueryContinuationState.Resumable::class.java, result.continuation).checkpoint,
                )
            assertEquals(0.queryCount(), checkpoint.emittedCount)
            assertEquals(2, checkpoint.tasks.size)
            assertEquals(checkpoints.firstOrNull()?.tasks ?: checkpoint.tasks, checkpoint.tasks)
            val impact = requireNotNull(checkpoint.impact)
            assertEquals(index + 1, impact.remainders.getValue(case.binding).consumed.size)
            val prefix = assertInstanceOf(ValueFlowRead.Observed::class.java, impact.reads[case.binding]).step
            assertEquals(case.locals.take(index + 1), prefix.transfers.map { it.target })
            checkpoints += checkpoint
        }
        assertEquals((0 until pages).toList(), case.confirmations)
        assertEquals(List(pages) { 1 }, case.bindingGrants.map { it.outputResults.value })
        return checkpoints
    }

    private data class HistoryWork(
        val comparisons: Long,
        val taskReads: Int,
        val evidenceVisits: Long,
        val remainderVisits: Long,
    )

    private fun measureHistory(checkpoints: List<PipelineCheckpoint>): HistoryWork {
        val taskLists = checkpoints.map { CountedTasks(it.tasks) }
        var evidenceVisits = 0L
        var remainderVisits = 0L
        // Include reconstruction and hashing, not only lookup. Wrappers preserve List and Map equality.
        val candidates = checkpoints.mapIndexed { index, checkpoint ->
            val impact = requireNotNull(checkpoint.impact)
            checkpoint.copy(
                tasks = taskLists[index],
                impact =
                    impact.copy(
                        reads = CountedMap(impact.reads) { evidenceVisits++ },
                        receipts = CountedMap(impact.receipts) { evidenceVisits++ },
                        remainders = CountedMap(impact.remainders) { remainderVisits++ },
                    ),
            )
        }
        val seen = mutableSetOf<PipelineCheckpoint>()
        candidates.forEach {
            assertFalse(it in seen)
            assertTrue(seen.add(it))
        }
        val work =
            HistoryWork(
                taskLists.sumOf { it.comparisons },
                taskLists.sumOf { it.elementReads },
                evidenceVisits,
                remainderVisits,
            )
        assertEquals(checkpoints.size, seen.size)
        assertTrue(candidates.first().copy(tasks = candidates.first().tasks.toList()) in seen)
        assertTrue(candidates.last().copy(tasks = candidates.last().tasks.toList()) in seen)
        return work
    }

    private suspend fun finishNativePages(
        case: QueryImpactProofResumeTest.NativeResumeCase,
        checkpoint: PipelineCheckpoint,
        pages: Int,
    ) {
        // Finish the same native scan with the same one-result child grant, then drain output only.
        val lastRead =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.service.run(case.request(1, checkpoint)),
            )
        assertInstanceOf(ValueFlowRead.Observed::class.java, case.bindingReads.last())
        assertEquals((0..pages).toList(), case.confirmations)
        val outputCheckpoint =
            assertInstanceOf(QueryContinuationState.Resumable::class.java, lastRead.continuation).checkpoint
        val terminal =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                case.service.run(case.request(pages + 1, outputCheckpoint)),
            )
        assertInstanceOf(QueryContinuationState.Terminal::class.java, terminal.continuation)
        val paths =
            (lastRead.result.rows as QueryRows.ValuePaths).values +
                (terminal.result.rows as QueryRows.ValuePaths).values
        assertEquals(case.arguments, paths.map { it.destination })
        assertEquals(pages + 1, case.bindingReads.size)
        case.nonlocal.assertConsumed()
    }

    private fun assertNativeCollisions(
        checkpoints: List<PipelineCheckpoint>,
        case: QueryImpactProofResumeTest.NativeResumeCase,
    ) {
        val first = checkpoints.first()
        val snapshot = requireNotNull(first.impact)
        val remainder = snapshot.remainders.getValue(case.binding)
        val otherAddress = requireNotNull(checkpoints[1].impact).remainders.getValue(case.binding).consumed.last()
        val changedRemainder =
            LocalBindingReadRemainder.admit(
                    remainder.source,
                    remainder.boundary,
                    setOf(otherAddress),
                    remainder.emitted,
                )
                .let { (it as io.github.amichne.kast.kernel.Refinement.Refined).value }
        val colliding = first.copy(impact = snapshot.copy(remainders = mapOf(case.binding to changedRemainder)))
        assertNotEquals(first, colliding)
        assertEquals(first.hashCode(), colliding.hashCode())
        val seen = hashSetOf(first, checkpoints[1])
        assertTrue(seen.add(colliding))
        val reconstructed =
            first.copy(
                tasks = first.tasks.toList(),
                impact =
                    snapshot.copy(
                        reads = snapshot.reads.entries.reversed().associate { it.toPair() },
                        remainders = snapshot.remainders.toMap(),
                        receipts = snapshot.receipts.toMap(),
                    ),
            )
        assertNotSame(first.impact, reconstructed.impact)
        assertEquals(first, reconstructed)
        assertEquals(first.hashCode(), reconstructed.hashCode())
        assertFalse(seen.add(reconstructed), "An older accepted state escaped detection after reconstruction")
        assertEquals(checkpoints[1].hashCode(), first.copy(impact = checkpoints[1].impact).hashCode())
        val tail = CountedTasks(first.tasks + List(4096) { PipelineTask.ImpactFinalize })
        val withTail = first.copy(tasks = tail)
        assertTrue(hashSetOf(withTail).contains(withTail))
        assertEquals(0, tail.elementReads, "Native progress hashing scanned an unchanged task tail")
    }

    @Test
    fun `hash work does not traverse retained task inventory`() {
        val original = checkpoint()
        val tasks = CountedTasks(List(4096) { original.tasks.first() })
        val checkpoint = original.copy(tasks = tasks)
        val seen = hashSetOf<PipelineCheckpoint>()

        assertTrue(seen.add(checkpoint))
        assertTrue(checkpoint in seen)

        assertEquals(0, tasks.elementReads, "Hash insertion and lookup traversed the retained task inventory")
    }

    @Test
    fun `reconstructed equal checkpoint keeps the same hash and duplicate detection`() {
        val original = checkpoint()
        val reconstructed =
            PipelineCheckpoint(
                seed = original.seed,
                lease = original.lease,
                tasks = original.tasks.map { it },
                identityRows = original.identityRows.toMap(),
                joinState = QueryJoinSnapshot(original.joinState.indexes.toMap()),
                limitations = original.limitations.toSet(),
                emittedCount = original.emittedCount,
                impact = original.impact,
            )
        val seen = hashSetOf(original)

        assertNotSame(original, reconstructed)
        assertNotSame(original.tasks, reconstructed.tasks)
        assertEquals(original, reconstructed)
        assertEquals(original.hashCode(), reconstructed.hashCode())
        assertFalse(seen.add(reconstructed))
        assertEquals(1, seen.size)
    }

    @Test
    fun `colliding progress keeps distinct checkpoint state`() {
        val original = checkpoint()
        val incomplete = original.copy(limitations = setOf(QueryLimitation.DISCOVERY_INCOMPLETE))
        val seen = hashSetOf(original)

        assertNotEquals(original, incomplete)
        assertEquals(original.hashCode(), incomplete.hashCode())
        assertTrue(seen.add(incomplete))
        assertEquals(2, seen.size)
        assertFalse(seen.add(incomplete.copy(tasks = incomplete.tasks.toList())))
    }

    private fun checkpoint(): PipelineCheckpoint {
        val fixture = QueryServiceTest()
        val request = fixture.request(fixture.symbolPlan(), 8L)
        return PipelineCheckpoint(
            seed = PipelineSeed.Accounted.create(request.plan),
            lease = request.lease,
            tasks = initialTasks(request.plan),
            identityRows = emptyMap(),
            joinState = QueryJoinSnapshot(emptyMap()),
            limitations = emptySet(),
            emittedCount = 0.queryCount(),
        )
    }

    private class CountedTasks(private val values: List<PipelineTask>) : AbstractList<PipelineTask>() {
        var elementReads = 0
            private set

        var comparisons = 0L
            private set

        override val size: Int
            get() = values.size

        override fun equals(other: Any?): Boolean {
            comparisons++
            return super.equals(other)
        }

        override fun hashCode(): Int = super.hashCode()

        override fun get(index: Int): PipelineTask {
            elementReads++
            return values[index]
        }
    }

    private class CountedMap<K, V>(private val backing: Map<K, V>, private val visit: () -> Unit) :
        AbstractMap<K, V>() {
        override val size: Int
            get() = backing.size

        override val entries: Set<Map.Entry<K, V>> =
            object : AbstractSet<Map.Entry<K, V>>() {
                override val size: Int
                    get() = backing.size

                override fun iterator(): Iterator<Map.Entry<K, V>> {
                    val delegate = backing.entries.iterator()
                    return object : Iterator<Map.Entry<K, V>> {
                        override fun hasNext(): Boolean = delegate.hasNext()

                        override fun next(): Map.Entry<K, V> {
                            if (!delegate.hasNext()) throw NoSuchElementException()
                            visit()
                            return delegate.next()
                        }
                    }
                }
            }
    }
}
