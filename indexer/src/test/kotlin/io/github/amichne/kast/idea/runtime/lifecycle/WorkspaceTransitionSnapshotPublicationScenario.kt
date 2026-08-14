package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastSourceIndexSummary
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublication
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.indexer.gradle.bootstrap.readyInitialProjectModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import java.lang.reflect.Proxy

internal object WorkspaceTransitionSnapshotPublicationScenario {
    fun verify() {
        val buildInputs = BuildSemanticInputIdentity("stable-build-inputs")
        val publication = RepositorySnapshotPublication.Suppressed
        val completed = mutableListOf<CompletedWorkspaceReconciliation>()
        val worker = WorkspaceTransitionWorker(
            initialConfig = KastConfig.defaults(),
            initialProjectModelAuthority = readyInitialProjectModel(buildInputs),
            resolveBuildSemanticInputIdentity = { buildInputs },
            semanticAdmission = IdeaIndexSemanticAdmission(projectStub()),
            eventWakeup = WorkspaceEventWakeup(),
            refreshWorkspace = {},
            loadLiveConfig = { it },
            captureCandidate = { _, _ ->
                WorkspaceReconciliationCandidate(
                    identity = WorkspaceStateIdentity("snapshot-bound-candidate"),
                    indexingCandidate = null,
                    snapshotPublication = publication,
                )
            },
            runIndexingPass = { _, _, _ ->
                IndexingPassResult(KastSourceIndexSummary(), GraphLaneOutcome.Committed)
            },
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            waitForNextPass = { false },
            isCancelled = { false },
            onConfigFallback = {},
            onCompleted = completed::add,
            onFailure = { throw it },
            onTransition = {},
        )

        worker.observe(WorkspaceSignal.Source)
        worker.run()

        assertEquals(1, completed.size)
        assertSame(publication, completed.single().snapshotPublication)
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
