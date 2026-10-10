package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** The native contract check rejects off-EDT status reads that have no read access. */
class NativeSmartModeWaitTest : NativeSmartModeFixtureTest() {
    fun testAlreadySmartPollUsesNativeReadAccess() = runTest {
        withNativeCaller {
            val records = mutableListOf<HostedSmartModeWaitObservation>()
            assertEquals(IndexingWait.Ready, awaitHostedSmartMode(project, records::add))
            assertEquals(
                listOf(HostedSmartModeWaitOutcome.STARTED, HostedSmartModeWaitOutcome.READY),
                records.map { it.outcome },
            )
            assertEquals(1L, records.last().statusPolls)
        }
    }

    fun testNativeDumbModeReleaseMakesWaitReady() = runTest {
        withNativeCaller {
            val dumbMode = startNativeDumbMode()
            try {
                val firstPoll = CompletableDeferred<Unit>()
                val states = mutableListOf<HostedIndexingState>()
                val records = mutableListOf<HostedSmartModeWaitObservation>()
                val wait = async {
                    waitForHostedSmartMode(
                        state = {
                            readHostedIndexingState(project).also { state ->
                                states += state
                                firstPoll.complete(Unit)
                            }
                        },
                        observe = records::add,
                    )
                }
                firstPoll.await()
                assertTrue(states.isNotEmpty())
                assertTrue(states.all { it == HostedIndexingState.INDEXING })
                dumbMode.releaseAndJoin()
                assertEquals(IndexingWait.Ready, wait.await())
                assertEquals(HostedIndexingState.INDEXING, states.first())
                assertEquals(HostedIndexingState.SMART, states.last())
                assertEquals(
                    listOf(HostedSmartModeWaitOutcome.STARTED, HostedSmartModeWaitOutcome.READY),
                    records.map { it.outcome },
                )
                assertEquals(states.size.toLong(), records.last().statusPolls)
            } finally {
                dumbMode.releaseAndJoin()
            }
        }
    }

    fun testCallerCancellationDrainsWaitBeforeNativeDumbModeRelease() = runTest {
        withNativeCaller {
            val dumbMode = startNativeDumbMode()
            try {
                val firstPoll = CompletableDeferred<Unit>()
                val records = mutableListOf<HostedSmartModeWaitObservation>()
                val wait = async {
                    waitForHostedSmartMode(
                        state = {
                            readHostedIndexingState(project).also { firstPoll.complete(Unit) }
                        },
                        observe = records::add,
                    )
                }
                firstPoll.await()
                wait.cancelAndJoin()
                assertTrue(wait.isCancelled)
                assertTrue(dumbMode.job.isActive)
                assertTrue(readAction { DumbService.isDumb(project) })
                assertEquals(
                    listOf(HostedSmartModeWaitOutcome.STARTED, HostedSmartModeWaitOutcome.CANCELLED),
                    records.map { it.outcome },
                )
                assertTrue(records.last().statusPolls >= 1)
                dumbMode.releaseAndJoin()
                assertFalse(readAction { DumbService.isDumb(project) })
                assertEquals(IndexingWait.Ready, awaitHostedSmartMode(project))
            } finally {
                dumbMode.releaseAndJoin()
            }
        }
    }

    fun testCancellationDrainsStatusReadWhileNativeWriteIsHeld() = runTest {
        withNativeCaller {
            withNativeWriteHeld { write ->
                val attemptedRead = CompletableDeferred<Unit>()
                val returnedStates = AtomicInteger()
                val records = mutableListOf<HostedSmartModeWaitObservation>()
                val wait = async {
                    waitForHostedSmartMode(
                        state = {
                            attemptedRead.complete(Unit)
                            readHostedIndexingState(project).also { returnedStates.incrementAndGet() }
                        },
                        observe = records::add,
                    )
                }
                attemptedRead.await()
                wait.cancelAndJoin()
                assertTrue(wait.isCancelled)
                assertTrue(write.job.isActive)
                assertEquals(1L, write.release.count)
                assertEquals(0, returnedStates.get())
                assertEquals(
                    listOf(HostedSmartModeWaitOutcome.STARTED, HostedSmartModeWaitOutcome.CANCELLED),
                    records.map { it.outcome },
                )
                assertEquals(1L, records.last().statusPolls)
            }
            assertEquals(IndexingWait.Ready, awaitHostedSmartMode(project))
        }
    }

    fun testOriginalDeadlineCancelsStatusReadWhileNativeWriteIsHeld() = runTest {
        withNativeCaller {
            withNativeWriteHeld { write ->
                val returnedStates = AtomicInteger()
                val records = mutableListOf<HostedSmartModeWaitObservation>()
                assertEquals(
                    IndexingWait.TimedOut,
                    waitForHostedSmartMode(
                        state = { readHostedIndexingState(project).also { returnedStates.incrementAndGet() } },
                        observe = records::add,
                    ),
                )
                assertTrue(write.job.isActive)
                assertEquals(1L, write.release.count)
                assertEquals(0, returnedStates.get())
                assertEquals(
                    listOf(HostedSmartModeWaitOutcome.STARTED, HostedSmartModeWaitOutcome.TIMED_OUT),
                    records.map { it.outcome },
                )
                assertEquals(ORIGINAL_WAIT_LIMIT_MILLIS, records.last().limitMillis)
                assertEquals(1L, records.last().statusPolls)
            }
            assertEquals(IndexingWait.Ready, awaitHostedSmartMode(project))
        }
    }

    private suspend fun CoroutineScope.startNativeDumbMode(): NativeDumbModeTask {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            DumbService.getInstance(project).runInDumbMode("native hosted wait fixture") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertTrue(readAction { DumbService.isDumb(project) })
        return NativeDumbModeTask(release, job)
    }

    private class NativeDumbModeTask(private val release: CompletableDeferred<Unit>, val job: Job) {
        suspend fun releaseAndJoin() =
            withContext(NonCancellable) {
                release.complete(Unit)
                job.join()
            }
    }

    private companion object {
        const val ORIGINAL_WAIT_LIMIT_MILLIS = 15_000L
    }
}
