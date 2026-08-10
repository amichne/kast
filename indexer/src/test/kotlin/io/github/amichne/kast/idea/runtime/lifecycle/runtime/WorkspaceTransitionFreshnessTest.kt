package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceSourceFreshness
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexer.gradle.settlement.MonotonicClock
import io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiter
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressWaitPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

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

internal fun assertCoveredSourcePublicationRace(workspaceRoot: Path) {
    val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(20))
    val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(21))
    val admission = raceReadyAdmission(initial)
    val source = workspaceRoot.resolve("src/Racing.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, "class Racing\n")
    val request = WorkspaceTransitionRequest.sourceFiles(
        workspaceRoot = workspaceRoot,
        paths = listOf(NormalizedPath.of(source)),
    ) as WorkspaceTransitionRequest.SourceFiles
    val transitionRequested = AtomicBoolean(false)
    val ingress = WorkspaceTransitionIngress(admission, raceAwaiter())
    ingress.bind { transitionRequested.set(true) }
    admission.dirty("covered source transition is active")
    ingress.observe(
        WorkspaceTransitionSnapshot(
            lifecycle = WorkspaceLifecycle.Reconciling,
            pendingSignals = emptySet(),
            published = PublishedWorkspaceGenerationState.Published(initial),
            blocker = null,
            observedEventCount = 4,
            activeSourceFreshness = WorkspaceSourceFreshness.Claimed(request.claims),
        ),
    )
    racePublish(admission, next)

    try {
        val published = runBlocking { ingress.reconcile(request) }

        assertEquals(next, published)
        assertFalse(transitionRequested.get())
    } finally {
        ingress.close()
    }
}

internal fun assertCoveredSourceStaleReadyRace(workspaceRoot: Path) {
    val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(22))
    val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(23))
    val admission = raceReadyAdmission(initial)
    val source = workspaceRoot.resolve("src/Stale.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, "class Stale\n")
    val request = WorkspaceTransitionRequest.sourceFiles(
        workspaceRoot = workspaceRoot,
        paths = listOf(NormalizedPath.of(source)),
    ) as WorkspaceTransitionRequest.SourceFiles
    val transitionRequested = AtomicBoolean(false)
    var ingress: WorkspaceTransitionIngress by Delegates.notNull()
    ingress = WorkspaceTransitionIngress(
        semanticAdmission = admission,
        transitionAwaiter = raceAwaiter { elapsed ->
            if (elapsed == Duration.ofMillis(1)) {
                racePublish(admission, next)
                ingress.observe(raceReadySnapshot(next))
            }
        },
    )
    ingress.bind { transitionRequested.set(true) }
    ingress.observe(
        WorkspaceTransitionSnapshot(
            lifecycle = WorkspaceLifecycle.Reconciling,
            pendingSignals = emptySet(),
            published = PublishedWorkspaceGenerationState.Published(initial),
            blocker = null,
            observedEventCount = 5,
            activeSourceFreshness = WorkspaceSourceFreshness.Claimed(request.claims),
        ),
    )

    try {
        val published = runBlocking { ingress.reconcile(request) }

        assertEquals(next, published)
        assertFalse(transitionRequested.get())
    } finally {
        ingress.close()
    }
}

private fun raceReadyAdmission(
    generation: PublishedWorkspaceGenerationManifest,
): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(raceProjectStub()).also { admission ->
    val token = admission.beginReconciliation("test generation")
    check(
        admission.publishReady(token) { WorkspaceGenerationCommit(generation) } is
            IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
    )
}

private fun racePublish(
    admission: IdeaIndexSemanticAdmission,
    generation: PublishedWorkspaceGenerationManifest,
) {
    admission.dirty("test transition")
    val token = admission.beginReconciliation("test reconciliation")
    check(
        admission.publishReady(token) { WorkspaceGenerationCommit(generation) } is
            IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
    )
}

private fun raceAwaiter(
    onPause: (Duration) -> Unit = {},
): ProgressAwareFutureAwaiter {
    var elapsed = Duration.ZERO
    return ProgressAwareFutureAwaiter(
        policy = RuntimeProgressWaitPolicy.derive(
            noProgressTimeout = Duration.ofMillis(2),
            maximumWait = Duration.ofMillis(10),
            observationInterval = Duration.ofMillis(1),
        ),
        clock = MonotonicClock.fromRaw { elapsed.toNanos() },
        pause = { duration ->
            elapsed = elapsed.plus(duration)
            onPause(elapsed)
        },
    )
}

private fun raceReadySnapshot(
    generation: PublishedWorkspaceGenerationManifest,
): WorkspaceTransitionSnapshot = WorkspaceTransitionSnapshot(
    lifecycle = WorkspaceLifecycle.Ready,
    pendingSignals = emptySet(),
    published = PublishedWorkspaceGenerationState.Published(generation),
    blocker = null,
    observedEventCount = generation.generation.value,
    activeSourceFreshness = WorkspaceSourceFreshness.Absent,
)

private fun raceProjectStub(): Project = Proxy.newProxyInstance(
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
