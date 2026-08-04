package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class WorkspaceTransitionWorkerBuildSemanticTest {
    @Test
    fun `source wakeup refreshes Gradle when build inputs drifted without a build signal`() {
        val importedBuildInputs = BuildSemanticInputIdentity("imported-build-inputs")
        val currentBuildInputs = BuildSemanticInputIdentity("changed-build-inputs")
        val refreshedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val publications = mutableListOf<WorkspaceStateIdentity>()
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialModelBuildSemanticIdentity = importedBuildInputs,
            resolveBuildSemanticInputIdentity = { currentBuildInputs },
            semanticAdmission = IdeaIndexSemanticAdmission(projectStub()),
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = refreshedSignals::add,
            loadLiveConfig = { it },
            captureCandidate = { _, buildInputs ->
                WorkspaceReconciliationCandidate(
                    identity = WorkspaceStateIdentity("state-${buildInputs.value}"),
                    indexingCandidate = null,
                )
            },
            runIndexingPass = { _, _ -> IndexingPassResult(KastSourceIndexSummary(), graphFailure = null) },
            publishWorkspaceGeneration = publications::add,
            waitForNextPass = { false },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = {},
            onFailure = { throw it },
            onTransition = {},
        )

        worker.observe(WorkspaceSignal.Source)
        worker.run()

        assertEquals(
            listOf(setOf(WorkspaceSignal.Source, WorkspaceSignal.BuildSemantic)),
            refreshedSignals,
        )
        assertEquals(listOf(WorkspaceStateIdentity("state-changed-build-inputs")), publications)
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
