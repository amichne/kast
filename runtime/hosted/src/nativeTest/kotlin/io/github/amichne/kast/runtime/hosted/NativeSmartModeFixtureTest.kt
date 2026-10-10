package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Shared native ownership and error guards for concrete boundary fixtures. */
// JUnit must discover the concrete cases only, never this fixture as an empty test class.
@Suppress("AbstractClassCanBeConcreteClass")
abstract class NativeSmartModeFixtureTest : HeavyPlatformTestCase() {
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

    protected suspend fun CoroutineScope.withNativeWriteHeld(block: suspend (NativeWriteBarrier) -> Unit) {
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

    protected class NativeWriteBarrier(val release: CountDownLatch, val job: Job)

    protected suspend fun withNativeCaller(block: suspend CoroutineScope.() -> Unit) {
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
}
