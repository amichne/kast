package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceTransitionWorkerRecoveryAuditTest {
    @Test
    fun `recovery audit reconciles when the current publication cannot be inspected`() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val identity = WorkspaceStateIdentity("stable-workspace")
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(8), identity)
        val admission = readyAdmission(initial)
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val delegate = TestWorkspaceGenerationPublication(initial, publications::add)
        val currentReads = AtomicInteger()
        val publication = object : WorkspaceGenerationPublication {
            override fun current() =
                if (currentReads.incrementAndGet() == 1) delegate.current() else error("unreadable current pointer")

            override fun begin() = delegate.begin()
            override fun prepare(
                open: io.github.amichne.kast.idea.transition.OpenWorkspacePublication,
                identity: WorkspaceStateIdentity,
            ) = delegate.prepare(open, identity)
            override fun commit(prepared: io.github.amichne.kast.idea.transition.PreparedWorkspacePublication) =
                delegate.commit(prepared)

            override fun discard(open: io.github.amichne.kast.idea.transition.OpenWorkspacePublication) =
                delegate.discard(open)

            override fun discard(prepared: io.github.amichne.kast.idea.transition.PreparedWorkspacePublication) =
                delegate.discard(prepared)
        }
        var waitCount = 0
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = buildInputs,
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = admission,
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, _ -> WorkspaceReconciliationCandidate(identity, null) },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), null) },
            workspaceGenerationPublication = publication,
            waitForNextPass = { waitCount++ == 0 },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        assertDoesNotThrow(worker::run)

        assertEquals(listOf(setOf(WorkspaceSignal.RecoveryAudit, WorkspaceSignal.BuildSemantic)), refreshedSignals)
        assertEquals(listOf(identity), publications)
        val current = (delegate.current() as PublishedWorkspaceGenerationState.Published).manifest
        assertEquals(WorkspaceSemanticGeneration(9), current.generation)
        assertEquals(IdeaIndexSemanticAdmission.Status.Ready(current), admission.status())
    }

    @Test
    fun `periodic recovery audit keeps unchanged publication without Gradle refresh or reconciliation`() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val identity = WorkspaceStateIdentity("stable-workspace")
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(8), identity)
        val admission = readyAdmission(initial)
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val indexingPasses = AtomicInteger()
        var waitCount = 0
        val publication = TestWorkspaceGenerationPublication(initial, publications::add)
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = buildInputs,
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = admission,
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, _ -> WorkspaceReconciliationCandidate(identity, null) },
            runIndexingPass = { _, _, _ ->
                indexingPasses.incrementAndGet()
                IndexingPassResult(KastSourceIndexSummary(), null)
            },
            workspaceGenerationPublication = publication,
            waitForNextPass = { waitCount++ == 0 },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        worker.run()

        assertEquals(listOf(setOf(WorkspaceSignal.RecoveryProbe)), refreshedSignals)
        assertEquals(0, indexingPasses.get())
        assertTrue(publications.isEmpty())
        assertEquals(PublishedWorkspaceGenerationState.Published(initial), publication.current())
        assertEquals(IdeaIndexSemanticAdmission.Status.Ready(initial), admission.status())
        assertEquals(2, waitCount)
    }

    @Test
    fun `recovery audit repairs a workspace change with no event`() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val initial = testPublishedWorkspaceGeneration(
            WorkspaceSemanticGeneration(8),
            WorkspaceStateIdentity("before-missed-change"),
        )
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        var waitCount = 0
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = buildInputs,
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = readyAdmission(initial),
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, _ -> WorkspaceReconciliationCandidate(WorkspaceStateIdentity("missed-change"), null) },
            runIndexingPass = { _, _, _ -> IndexingPassResult(KastSourceIndexSummary(), null) },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(initial, publications::add),
            waitForNextPass = { waitCount++ == 0 },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        worker.run()

        assertEquals(
            listOf(
                setOf(WorkspaceSignal.RecoveryProbe),
                setOf(WorkspaceSignal.RecoveryAudit, WorkspaceSignal.BuildSemantic),
            ),
            refreshedSignals,
        )
        assertEquals(listOf(WorkspaceStateIdentity("missed-change")), publications)
        assertEquals(2, waitCount)
    }

    @Test
    fun `recovery audit propagates configuration cancellation without restoring READY`() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val identity = WorkspaceStateIdentity("stable-workspace")
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(8), identity)
        val admission = readyAdmission(initial)
        val fallbackCalled = AtomicBoolean()
        val publication = TestWorkspaceGenerationPublication(initial)
        var waitCount = 0
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = buildInputs,
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = admission,
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = {},
            loadLiveConfig = { throw CancellationException("cancelled recovery audit") },
            captureCandidate = { _, _ -> WorkspaceReconciliationCandidate(identity, null) },
            runIndexingPass = { _, _, _ -> error("cancelled audit must not index") },
            workspaceGenerationPublication = publication,
            waitForNextPass = { waitCount++ == 0 },
            isCancelled = { false },
            onConfigFallback = { fallbackCalled.set(true) },
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        val failure = assertThrows(CancellationException::class.java, worker::run)

        assertEquals("cancelled recovery audit", failure.message)
        assertFalse(fallbackCalled.get())
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
