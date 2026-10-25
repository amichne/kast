package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryLimitation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineCheckpointHashWorkTest {
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

        override val size: Int
            get() = values.size

        override fun get(index: Int): PipelineTask {
            elementReads++
            return values[index]
        }
    }
}
