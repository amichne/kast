package io.github.amichne.kast.indexer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DetachedIndexerTransportExecutorTest {
    @Test
    fun `long lived transport does not inherit the completed startup thread context`() {
        val startupContext = InheritableThreadLocal<String>()
        startupContext.set("completed-startup-job")

        val execution = DetachedIndexerTransportExecutor.execute {
            startupContext.get()
        }

        val completed = assertInstanceOf(
            IndexerTransportExecution.Completed::class.java,
            execution,
        )
        assertNull(completed.value)
    }

    @Test
    fun `transport execution failure remains finite data`() {
        val execution = DetachedIndexerTransportExecutor.execute<Unit> {
            error("transport failed")
        }

        assertEquals(IndexerTransportExecution.Failed, execution)
    }
}
