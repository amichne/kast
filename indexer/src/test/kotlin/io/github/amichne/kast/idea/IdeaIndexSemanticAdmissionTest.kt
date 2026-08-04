package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@TestApplication
class IdeaIndexSemanticAdmissionTest {
    @Test
    fun `compiler readiness waits until the model is usable without admitting semantic reads`() {
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
        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        assertThrows(IllegalStateException::class.java) { admission.openRead() }
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
    fun `event invalidates an in-flight reconciliation token`() {
        val admission = readyAdmission()
        val token = admission.beginReconciliation("workspace reconciliation is active")

        assertTrue(admission.isReconciliationCurrent(token))
        admission.dirty("source file changed")

        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        assertFalse(admission.isReconciliationCurrent(token))
        assertEquals(
            IdeaIndexSemanticAdmission.ReadyPublication.InvalidatedBeforeCommit,
            admission.publishReady(token) { durableCommit(publishedGeneration()) },
        )
    }

    @Test
    fun `compiler readiness does not admit reads before the first workspace generation`() {
        val admission = IdeaIndexSemanticAdmission(
            project = projectStub(),
            inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
        )

        admission.await { false }

        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        assertThrows(IllegalStateException::class.java) { admission.openRead() }
    }

    @Test
    fun `workspace mutation outside ready fails with typed admission evidence`() {
        val admission = IdeaIndexSemanticAdmission(projectStub())

        val failure = assertThrows(
            IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionUnavailableException::class.java,
        ) {
            admission.beginMutation("workspace mutation requires ready")
        }

        assertTrue(failure.admissionStatus is IdeaIndexSemanticAdmission.Status.Pending)
        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
    }

    @Test
    fun `workspace mutation permit carries the exact ready generation`() {
        val generation = publishedGeneration()
        val admission = readyAdmission(generation)

        admission.beginMutation("workspace mutation is active").use { permit ->
            assertEquals(generation, permit.generation)
        }
    }

    @Test
    fun `read token detects a workspace transition during a request`() {
        val admission = readyAdmission()
        val token = admission.openRead()

        admission.dirty("build model changed")

        assertEquals(false, admission.isReadCurrent(token))
        token.close()
    }

    @Test
    fun `event withdraws readiness without waiting for a slow publication commit`() {
        val admission = readyAdmission()
        val token = admission.beginReconciliation("workspace reconciliation is active")
        val publicationStarted = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val dirtyCompleted = CountDownLatch(1)
        val publicationResult = AtomicReference<IdeaIndexSemanticAdmission.ReadyPublication>()
        val publisher = Thread {
            publicationResult.set(admission.publishReady(token) {
                publicationStarted.countDown()
                releasePublication.await()
                durableCommit(publishedGeneration())
            })
        }
        val invalidator = Thread {
            publicationStarted.await()
            admission.dirty("source changed during publication")
            dirtyCompleted.countDown()
        }

        publisher.start()
        invalidator.start()
        assertTrue(publicationStarted.await(1, TimeUnit.SECONDS))
        assertTrue(dirtyCompleted.await(1, TimeUnit.SECONDS))

        releasePublication.countDown()
        publisher.join(1_000)
        invalidator.join(1_000)

        assertTrue(
            publicationResult.get() is IdeaIndexSemanticAdmission.ReadyPublication.InvalidatedAfterCommit,
        )
        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
    }

    @Test
    fun `reconciliation waits for admitted readers after readiness is withdrawn`() {
        val admission = readyAdmission()
        val read = admission.openRead()
        val reconciliationStarted = CountDownLatch(1)
        val reconciliationCompleted = CountDownLatch(1)
        val reconciliation = Thread {
            reconciliationStarted.countDown()
            admission.beginReconciliation("workspace reconciliation is active")
            reconciliationCompleted.countDown()
        }

        reconciliation.start()
        assertTrue(reconciliationStarted.await(1, TimeUnit.SECONDS))
        assertFalse(reconciliationCompleted.await(100, TimeUnit.MILLISECONDS))
        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)

        read.close()
        assertTrue(reconciliationCompleted.await(1, TimeUnit.SECONDS))
        reconciliation.join(1_000)
    }

    @Test
    fun `workspace mutation withdraws readiness and excludes reconciliation until release`() {
        val admission = readyAdmission()
        val read = admission.openRead()
        val mutationStarted = CountDownLatch(1)
        val mutationAcquired = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val reconciliationCompleted = CountDownLatch(1)
        val mutation = Thread {
            mutationStarted.countDown()
            admission.beginMutation("workspace mutation is active").use {
                mutationAcquired.countDown()
                releaseMutation.await()
            }
        }

        var reconciliation: Thread? = null
        try {
            mutation.start()
            assertTrue(mutationStarted.await(1, TimeUnit.SECONDS))
            assertTrue(awaitCondition { admission.status() is IdeaIndexSemanticAdmission.Status.Pending })
            assertFalse(mutationAcquired.await(100, TimeUnit.MILLISECONDS))
            read.close()
            assertTrue(mutationAcquired.await(1, TimeUnit.SECONDS))

            reconciliation = Thread {
                admission.beginReconciliation("workspace reconciliation is active")
                reconciliationCompleted.countDown()
            }.also(Thread::start)
            assertFalse(reconciliationCompleted.await(100, TimeUnit.MILLISECONDS))

            releaseMutation.countDown()
            assertTrue(reconciliationCompleted.await(1, TimeUnit.SECONDS))
        } finally {
            read.close()
            releaseMutation.countDown()
            mutation.join(1_000)
            reconciliation?.join(1_000)
        }
    }

    @Test
    fun `workspace mutation is rejected when an event arrives before its permit is acquired`() {
        val admission = readyAdmission()
        val read = admission.openRead()
        val mutationStarted = CountDownLatch(1)
        val mutationAcquired = AtomicBoolean(false)
        val mutationFailure = AtomicReference<Throwable>()
        val mutation = Thread {
            mutationStarted.countDown()
            runCatching {
                admission.beginMutation("workspace mutation is waiting for readers").use {
                    mutationAcquired.set(true)
                }
            }.onFailure(mutationFailure::set)
        }

        try {
            mutation.start()
            assertTrue(mutationStarted.await(1, TimeUnit.SECONDS))
            assertTrue(awaitCondition { admission.status() is IdeaIndexSemanticAdmission.Status.Pending })

            admission.dirty("source changed before mutation admission")
            read.close()
            mutation.join(1_000)

            assertFalse(mutation.isAlive)
            assertFalse(mutationAcquired.get())
            val failure = mutationFailure.get()
            assertTrue(failure is IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionInvalidatedException)
            failure as IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionInvalidatedException
            assertTrue(failure.actualRevision > failure.expectedRevision)
        } finally {
            read.close()
            mutation.interrupt()
            mutation.join(1_000)
        }
    }

    @Test
    fun `ready and read token carry the exact published generation`() {
        val generation = publishedGeneration()
        val admission = readyAdmission(generation)

        val ready = admission.status() as IdeaIndexSemanticAdmission.Status.Ready
        val read = admission.openRead()

        assertEquals(generation, ready.generation)
        assertEquals(generation, read.generation)
        read.close()
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
        val admissionFuture = executor.submit { admission.await(cancelled = stopRead::get) }
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

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!condition() && System.nanoTime() < deadline) Thread.onSpinWait()
        return condition()
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

    private fun readyAdmission(
        generation: PublishedWorkspaceGenerationManifest = publishedGeneration(),
    ): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = projectStub(),
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    ).also { admission ->
        admission.await { false }
        val token = admission.beginReconciliation("test generation is verified")
        check(
            admission.publishReady(token) { durableCommit(generation) } is
                IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
        )
    }

    private fun publishedGeneration(): PublishedWorkspaceGenerationManifest = testPublishedWorkspaceGeneration(
        generation = WorkspaceSemanticGeneration(1),
        identity = WorkspaceStateIdentity("test-workspace-state"),
    )

    private fun durableCommit(
        generation: PublishedWorkspaceGenerationManifest,
    ): WorkspaceGenerationCommit = WorkspaceGenerationCommit.Durable(generation)
}
