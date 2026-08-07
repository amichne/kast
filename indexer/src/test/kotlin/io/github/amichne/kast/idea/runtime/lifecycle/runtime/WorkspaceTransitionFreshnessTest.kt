package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceSourceFreshness
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceTransitionFreshnessTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `same path and content joins the active source transition`() {
        val source = workspaceRoot.resolve("src/Sample.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Sample\n")
        val active = sourceRequest(source)
        val requested = sourceRequest(source)

        val route = WorkspaceTransitionRoute.derive(
            status = IdeaIndexSemanticAdmission.Status.Pending("source transition is active"),
            observation = activeObservation(active),
            request = requested,
        )

        assertTrue(route is WorkspaceTransitionRoute.Join)
    }

    @Test
    fun `new content enqueues a distinct source transition`() {
        val source = workspaceRoot.resolve("src/Sample.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Sample\n")
        val active = sourceRequest(source)
        Files.writeString(source, "class Changed\n")
        val requested = sourceRequest(source)

        val route = WorkspaceTransitionRoute.derive(
            status = IdeaIndexSemanticAdmission.Status.Pending("source transition is active"),
            observation = activeObservation(active),
            request = requested,
        )

        assertTrue(route is WorkspaceTransitionRoute.Enqueue)
    }

    private fun sourceRequest(source: Path): WorkspaceTransitionRequest.SourceFiles =
        WorkspaceTransitionRequest.sourceFiles(
            workspaceRoot = workspaceRoot,
            paths = listOf(NormalizedPath.of(source)),
        ) as WorkspaceTransitionRequest.SourceFiles

    private fun activeObservation(
        request: WorkspaceTransitionRequest.SourceFiles,
    ): TransitionObservation = TransitionObservation.Observed(
        WorkspaceTransitionSnapshot(
            lifecycle = WorkspaceLifecycle.Reconciling,
            pendingSignals = emptySet(),
            published = PublishedWorkspaceGenerationState.Unpublished,
            blocker = null,
            observedEventCount = 1,
            activeSourceFreshness = WorkspaceSourceFreshness.Claimed(request.claims),
        ),
    )
}
