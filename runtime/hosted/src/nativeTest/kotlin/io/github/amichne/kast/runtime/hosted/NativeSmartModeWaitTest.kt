package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbServiceImpl
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ThrowableRunnable
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** The native contract check rejects off-EDT status reads that have no read access. */
class NativeSmartModeWaitTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread() = false

    override fun runBare(runnable: ThrowableRunnable<Throwable>) {
        val errors = AtomicInteger()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    cause: Throwable?,
                ): Set<Action> {
                    errors.incrementAndGet()
                    return super.processError(category, message, details, cause)
                }
            }
        LoggedErrorProcessor.executeWith<Throwable>(processor) { super.runBare(runnable) }
        assertEquals("Native platform logged errors", 0, errors.get())
    }

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

    private suspend fun CoroutineScope.withNativeWriteHeld(block: suspend (NativeWriteBarrier) -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { writerCaller ->
            val enteredWrite = CompletableDeferred<Unit>()
            val releaseWrite = CountDownLatch(1)
            val writer =
                async(writerCaller) {
                    runInEdtAndWait {
                        WriteAction.run<RuntimeException> {
                            enteredWrite.complete(Unit)
                            releaseWrite.await()
                        }
                    }
                }
            try {
                enteredWrite.await()
                block(NativeWriteBarrier(releaseWrite, writer))
            } finally {
                withContext(NonCancellable) {
                    releaseWrite.countDown()
                    writer.await()
                }
            }
        }
    }

    private class NativeWriteBarrier(val release: CountDownLatch, val job: Job)

    private suspend fun withNativeCaller(block: suspend CoroutineScope.() -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { nativeCaller ->
            withContext(nativeCaller) { withNativeStatusContract { coroutineScope(block) } }
        }
    }

    private suspend fun withNativeStatusContract(block: suspend () -> Unit) {
        assertPinnedStatusImplementation()
        assertFalse(DumbServiceImpl.ALWAYS_SMART)
        val guardOwner = Disposer.newDisposable("native-smart-mode-contract")
        Registry.get("ide.check.is.dumb.contract").setValue(true, guardOwner)
        try {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            block()
        } finally {
            Disposer.dispose(guardOwner)
        }
    }

    private fun assertPinnedStatusImplementation() {
        val implementation = DumbService.getInstance(project).javaClass
        assertEquals(DumbServiceImpl::class.java, implementation)
        val entry = implementation.name.replace('.', '/') + ".class"
        val loadedJar = Path.of(implementation.protectionDomain.codeSource.location.toURI())
        val sdkJar = Path.of(System.getProperty("idea.home.path")).resolve("lib/intellij.platform.ide.impl.jar")
        JarFile(loadedJar.toFile()).use { loaded ->
            JarFile(sdkJar.toFile()).use { pinned ->
                val actual = loaded.getInputStream(loaded.getJarEntry(entry)).use { it.readBytes() }
                val expected = pinned.getInputStream(pinned.getJarEntry(entry)).use { it.readBytes() }
                assertTrue("Loaded status class differs from the pinned distribution", expected.contentEquals(actual))
            }
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
