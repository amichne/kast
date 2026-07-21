package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@TestApplication
class IdeaIndexSemanticAdmissionTest {
    @Test
    fun `index admission waits until a Kotlin compiler model is semantically usable`() {
        var nowNanos = 0L
        var attempts = 0
        val admission = IdeaIndexSemanticAdmission(
            project = projectStub(),
            inspectProject = {
                attempts += 1
                if (attempts == 1) {
                    IdeaIndexSemanticAdmission.Inspection.Pending("kotlin runtime unresolved")
                } else {
                    IdeaIndexSemanticAdmission.Inspection.Ready
                }
            },
            nanoTime = { nowNanos },
            pause = { millis -> nowNanos += millis * 1_000_000L },
            maxWaitMillis = 1_000,
            pollIntervalMillis = 25,
        )

        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)

        admission.await { false }

        assertEquals(2, attempts)
        assertEquals(25_000_000L, nowNanos)
        assertEquals(IdeaIndexSemanticAdmission.Status.Ready, admission.status())
    }

    @Test
    fun `index admission fails typed instead of publishing ready after timeout`() {
        var nowNanos = 0L
        val admission = IdeaIndexSemanticAdmission(
            project = projectStub(),
            inspectProject = {
                IdeaIndexSemanticAdmission.Inspection.Pending("JDK symbol java.nio.file.Path unresolved in :app")
            },
            nanoTime = { nowNanos },
            pause = { millis -> nowNanos += millis * 1_000_000L },
            maxWaitMillis = 50,
            pollIntervalMillis = 25,
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            admission.await { false }
        }

        assertTrue(failure.message.orEmpty().contains("java.nio.file.Path"))
        assertTrue(
            (admission.status() as IdeaIndexSemanticAdmission.Status.Failed)
                .detail
                .contains("java.nio.file.Path"),
        )
    }

    @Test
    fun `semantic admission yields to a pending EDT write action`() {
        val application = ApplicationManager.getApplication()
        val readStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val stopRead = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        val admission = IdeaIndexSemanticAdmission(
            project = projectStub(),
            inspectProject = {
                assertTrue(application.isReadAccessAllowed, "semantic inspection must run with read access")
                readStarted.countDown()
                while (writeCompleted.count > 0 && !stopRead.get()) {
                    ProgressManager.checkCanceled()
                    Thread.sleep(10)
                }
                IdeaIndexSemanticAdmission.Inspection.Ready
            },
        )
        val admissionFuture = executor.submit { admission.await(stopRead::get) }
        var writeFuture: Future<*>? = null

        try {
            assertTrue(readStarted.await(1, TimeUnit.SECONDS), "semantic inspection did not start")
            writeFuture = executor.submit {
                application.invokeAndWait {
                    application.runWriteAction {
                        writeCompleted.countDown()
                    }
                }
            }

            assertTrue(
                writeCompleted.await(2, TimeUnit.SECONDS),
                "semantic admission read action should yield when the EDT needs a write action",
            )
            admissionFuture.get(2, TimeUnit.SECONDS)
            writeFuture.get(2, TimeUnit.SECONDS)
        } finally {
            stopRead.set(true)
            admissionFuture.cancel(true)
            writeFuture?.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun projectStub(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "stub"
                "isDisposed" -> false
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "ProjectStub"
                else -> null
            }
        } as Project
}
