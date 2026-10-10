@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedSmartModeWaitTest {
    @Test
    fun `already smart records one status poll and no wait`() = runTest {
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val result =
            waitForHostedSmartMode(
                state = { HostedIndexingState.SMART },
                observe = events::add,
                clock = { testScheduler.currentTime * NANOS_PER_MILLI },
            )
        assertEquals(IndexingWait.Ready, result)
        assertEquals(
            listOf(
                event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                event(HostedSmartModeWaitOutcome.READY, 0, 1),
            ),
            events,
        )
    }

    @Test
    fun `native indexing observations become ready after exactly two scheduled delays`() = runTest {
        val states =
            ArrayDeque(listOf(HostedIndexingState.INDEXING, HostedIndexingState.INDEXING, HostedIndexingState.SMART))
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val result =
            waitForHostedSmartMode(
                state = states::removeFirst,
                observe = events::add,
                clock = { testScheduler.currentTime * NANOS_PER_MILLI },
            )
        assertEquals(IndexingWait.Ready, result)
        assertEquals(emptyList<HostedIndexingState>(), states.toList())
        assertEquals(
            listOf(
                event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                event(HostedSmartModeWaitOutcome.READY, 200 * NANOS_PER_MILLI, 3),
            ),
            events,
        )
    }

    @Test
    fun `unchanged original deadline records timeout without a retry or success`() = runTest {
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val result =
            waitForHostedSmartMode(
                state = { HostedIndexingState.INDEXING },
                observe = events::add,
                clock = { testScheduler.currentTime * NANOS_PER_MILLI },
            )
        assertEquals(IndexingWait.TimedOut, result)
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(
            listOf(
                event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                event(HostedSmartModeWaitOutcome.TIMED_OUT, 15_000 * NANOS_PER_MILLI, 150),
            ),
            events,
        )
    }

    @Test
    fun `disposed project is a finite outcome before another scheduled poll`() = runTest {
        val states = ArrayDeque(listOf(HostedIndexingState.INDEXING, HostedIndexingState.DISPOSED))
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val result =
            waitForHostedSmartMode(
                state = states::removeFirst,
                observe = events::add,
                clock = { testScheduler.currentTime * NANOS_PER_MILLI },
            )
        assertEquals(IndexingWait.Disposed, result)
        assertEquals(emptyList<HostedIndexingState>(), states.toList())
        assertEquals(
            listOf(
                event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                event(HostedSmartModeWaitOutcome.DISPOSED, 100 * NANOS_PER_MILLI, 2),
            ),
            events,
        )
    }

    @Test
    fun `suspended status admission still uses the original wait deadline`() = runTest {
        val pending = CompletableDeferred<HostedIndexingState>()
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val result =
            waitForHostedSmartMode(
                state = pending::await,
                observe = events::add,
                clock = { testScheduler.currentTime * NANOS_PER_MILLI },
            )
        assertEquals(IndexingWait.TimedOut, result)
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(
            listOf(
                event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                event(HostedSmartModeWaitOutcome.TIMED_OUT, 15_000 * NANOS_PER_MILLI, 1),
            ),
            events,
        )
        assertEquals(false, pending.isCompleted)
    }

    @Test
    fun `caller cancellation drains the wait and preserves cancellation`() = runTest {
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val operation = async {
            waitForHostedSmartMode(
                state = { HostedIndexingState.INDEXING },
                observe = events::add,
                clock = { testScheduler.currentTime * NANOS_PER_MILLI },
            )
        }
        runCurrent()
        operation.cancelAndJoin()
        assertEquals(
            listOf(
                event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                event(HostedSmartModeWaitOutcome.CANCELLED, 0, 1),
            ),
            events,
        )
        assertEquals(true, operation.isCancelled)
    }

    @Test
    fun `caller deadline is cancellation rather than the smart-mode deadline`() = runTest {
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        try {
            withTimeout(50L) {
                waitForHostedSmartMode(
                    state = { HostedIndexingState.INDEXING },
                    observe = events::add,
                    clock = { testScheduler.currentTime * NANOS_PER_MILLI },
                )
            }
            error("The caller deadline must cancel the wait")
        } catch (_: TimeoutCancellationException) {
            assertEquals(
                listOf(
                    event(HostedSmartModeWaitOutcome.STARTED, 0, 0),
                    event(HostedSmartModeWaitOutcome.CANCELLED, 50 * NANOS_PER_MILLI, 1),
                ),
                events,
            )
        }
    }

    @Test
    fun `native cancellation preserves the original exception identity`() = runTest {
        val original = ProcessCanceledException()
        assertFailureObserved(original, HostedSmartModeWaitOutcome.CANCELLED)
    }

    @Test
    fun `runtime failure has its own terminal observation`() = runTest {
        assertFailureObserved(
            IllegalStateException("fixture failure"),
            HostedSmartModeWaitOutcome.PLATFORM_RUNTIME_FAILURE,
        )
    }

    @Test
    fun `linkage failure has its own terminal observation`() = runTest {
        assertFailureObserved(LinkageError("fixture linkage"), HostedSmartModeWaitOutcome.PLATFORM_LINKAGE_FAILURE)
    }

    @Test
    fun `wait record keeps the connection identity and surrounding execution clock`() {
        var now = 0L
        val transports = mutableListOf<HostedTransportObservation>()
        val waits = mutableListOf<HostedCorrelatedSmartModeWait>()
        val observer =
            object : HostedEndpointObserver {
                override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

                override fun transport(observation: HostedTransportObservation) {
                    transports += observation
                }

                override fun smartModeWait(observation: HostedCorrelatedSmartModeWait) {
                    waits += observation
                }
            }
        val trace = HostedTransportTrace(observer, clock = { now })
        trace.enter(HostedTransportStage.EXECUTION)
        now = 500
        trace.smartModeWait(event(HostedSmartModeWaitOutcome.TIMED_OUT, 400, 150))
        now = 600
        trace.emit(HostedEndpointOutcome.REJECTED)
        assertEquals(transports.first().connectionId, waits.single().connectionId)
        assertEquals(600L, transports.last().elapsedNanos)
        assertEquals(HostedTransportStage.EXECUTION, transports.last().stage)
        val encoded = Json { encodeDefaults = true }.encodeToString(waits.single())
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("connectionId", "wait"), root.keys)
        assertEquals(waits.single().connectionId, root.getValue("connectionId").jsonPrimitive.content)
        val fields = root.getValue("wait").jsonObject
        assertEquals(setOf("outcome", "elapsedNanos", "statusPolls", "limitMillis"), fields.keys)
        assertEquals("TIMED_OUT", fields.getValue("outcome").jsonPrimitive.content)
        assertEquals("400", fields.getValue("elapsedNanos").jsonPrimitive.content)
        assertEquals("150", fields.getValue("statusPolls").jsonPrimitive.content)
        assertEquals("15000", fields.getValue("limitMillis").jsonPrimitive.content)
    }

    private suspend fun assertFailureObserved(original: Throwable, expected: HostedSmartModeWaitOutcome) {
        val events = mutableListOf<HostedSmartModeWaitObservation>()
        val caught =
            try {
                waitForHostedSmartMode(state = { throw original }, observe = events::add, clock = { 0L })
                error("Expected original platform failure")
            } catch (failure: Throwable) {
                failure
            }
        // Coroutine stack recovery may copy standard exceptions while retaining the original as its cause.
        assertEquals(original.javaClass, caught.javaClass)
        assertSame(original, if (caught === original) caught else caught.cause)
        assertEquals(listOf(event(HostedSmartModeWaitOutcome.STARTED, 0, 0), event(expected, 0, 1)), events)
    }

    private fun event(outcome: HostedSmartModeWaitOutcome, elapsed: Long, polls: Long) =
        HostedSmartModeWaitObservation(outcome, elapsed, polls, 15_000L)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
