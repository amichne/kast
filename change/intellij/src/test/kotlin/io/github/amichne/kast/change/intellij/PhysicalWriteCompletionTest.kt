package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.SourceWriteFailure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PhysicalWriteCompletionTest {
    @Test
    fun `physical observation waits for queued content and emits completion evidence`() {
        var disk = "before".toByteArray()
        val after = "after".toByteArray()
        val evidence = mutableListOf<PhysicalWriteCompletion>()
        val observed =
            observeCompletedPhysicalWrite(
                complete = {
                    disk = after
                    IntellijSessionStepResult.Completed
                },
                observe = {
                    assertEquals(listOf(PhysicalWriteCompletion.Completed), evidence)
                    IntellijPhysicalSourceObservation.Observed(disk, setOf("source.kt"))
                },
                emit = evidence::add,
            )

        assertArrayEquals(
            after,
            assertInstanceOf(IntellijPhysicalSourceObservation.Observed::class.java, observed).bytes,
        )
        assertEquals(listOf(PhysicalWriteCompletion.Completed), evidence)
        assertEquals("{\"type\":\"completed\"}", Json.encodeToString<PhysicalWriteCompletion>(evidence.single()))
    }

    @Test
    fun `failed platform completion prevents physical observation and retains finite failure`() {
        val evidence = mutableListOf<PhysicalWriteCompletion>()
        val observed =
            observeCompletedPhysicalWrite(
                complete = { IntellijSessionStepResult.Rejected(SourceWriteFailure.OBSERVATION_FAILED) },
                observe = { error("Physical observation must not precede successful write completion") },
                emit = evidence::add,
            )

        assertEquals(IntellijPhysicalSourceObservation.Rejected(SourceWriteFailure.OBSERVATION_FAILED), observed)
        assertEquals(listOf(PhysicalWriteCompletion.Rejected(SourceWriteFailure.OBSERVATION_FAILED)), evidence)
        assertEquals(
            "{\"type\":\"rejected\",\"failure\":\"OBSERVATION_FAILED\"}",
            Json.encodeToString<PhysicalWriteCompletion>(evidence.single()),
        )
    }
}
