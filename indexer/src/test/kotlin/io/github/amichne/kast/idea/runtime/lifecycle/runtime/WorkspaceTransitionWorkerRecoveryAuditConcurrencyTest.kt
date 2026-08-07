package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublication
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class WorkspaceTransitionWorkerRecoveryAuditConcurrencyTest {
    @Test
    fun `recovery audit withdraws reads before exposing missed drift`() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val initial = testPublishedWorkspaceGeneration(
            WorkspaceSemanticGeneration(8),
            WorkspaceStateIdentity("before-missed-change"),
        )
        val admission = readyAdmission(initial)
        val existingRead = admission.openRead()
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val publications = CopyOnWriteArrayList<WorkspaceStateIdentity>()
        var waitCount = 0
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = buildInputs,
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = admission,
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = { signals ->
                if (signals == setOf(WorkspaceSignal.RecoveryProbe)) {
                    probeStarted.countDown()
                    releaseProbe.await(1, TimeUnit.SECONDS)
                }
            },
            loadLiveConfig = { it },
            captureCandidate = { _, _ ->
                WorkspaceReconciliationCandidate(
                    WorkspaceStateIdentity("missed-change"),
                    null,
                    RepositorySnapshotPublication.Unmanaged,
                )
            },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), null) },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(initial, publications::add),
            waitForNextPass = { waitCount++ == 0 },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )
        val running = thread(isDaemon = true, block = worker::run)

        try {
            assertFalse(
                probeStarted.await(250, TimeUnit.MILLISECONDS),
                "recovery probe started before the admitted read drained",
            )
            assertFalse(admission.isReadCurrent(existingRead))
            assertThrows(IllegalStateException::class.java, admission::openRead)

            existingRead.close()
            assertTrue(probeStarted.await(1, TimeUnit.SECONDS), "recovery probe did not start")
            releaseProbe.countDown()
            running.join(2_000)

            assertFalse(running.isAlive)
            assertEquals(listOf(WorkspaceStateIdentity("missed-change")), publications)
        } finally {
            existingRead.close()
            releaseProbe.countDown()
            running.interrupt()
            running.join(1_000)
        }
    }

    @Test
    fun `cancellation during recovery probe cannot restore READY`() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val identity = WorkspaceStateIdentity("stable-workspace")
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(8), identity)
        val admission = readyAdmission(initial)
        val cancelled = AtomicBoolean()
        val publication = TestWorkspaceGenerationPublication(initial)
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = buildInputs,
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = admission,
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = { signals ->
                if (signals == setOf(WorkspaceSignal.RecoveryProbe)) cancelled.set(true)
            },
            loadLiveConfig = { it },
            captureCandidate = { _, _ ->
                WorkspaceReconciliationCandidate(identity, null, RepositorySnapshotPublication.Unmanaged)
            },
            runIndexingPass = { _, _, _ -> error("cancelled audit must not index") },
            workspaceGenerationPublication = publication,
            waitForNextPass = { false },
            isCancelled = cancelled::get,
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        assertThrows(ProcessCanceledException::class.java, worker::requestRecoveryAudit)

        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        assertEquals(PublishedWorkspaceGenerationState.Published(initial), publication.current())
    }

    private fun readyAdmission(generation: PublishedWorkspaceGenerationManifest) =
        IdeaIndexSemanticAdmission(projectStub()).also { admission ->
            val token = admission.beginReconciliation("test generation")
            check(
                admission.publishReady(token) { WorkspaceGenerationCommit(generation) } is
                    IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
            )
        }

    private fun projectStub(): Project = Proxy.newProxyInstance(
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
