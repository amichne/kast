package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.workspace.contract.WorkspaceLifecycle
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshness
import io.github.amichne.kast.workspace.contract.TransitionBlocker
import io.github.amichne.kast.workspace.contract.TransitionBlockerKind
import io.github.amichne.kast.workspace.contract.TransitionPhase
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.idea.transition.captureSourceWorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
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
        captureSourceWorkspaceTransitionRequest(
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

class WorkspaceTransitionIngressReviewRegressionTest {
    @Test
    fun `uncovered request dispatches before a newer generation can satisfy its waiter`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(30))
        val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(31))
        val admission = raceReadyAdmission(initial)
        val order = mutableListOf<String>()
        var observations = 0
        val ingress = WorkspaceTransitionIngress(
            semanticAdmission = admission,
            admissionObservation = WorkspaceTransitionAdmissionObservation {
                observations += 1
                when (observations) {
                    1 -> IdeaIndexSemanticAdmission.Status.Ready(initial).also { order += "route" }
                    2 -> IdeaIndexSemanticAdmission.Status.Ready(next).also { order += "accept" }
                    else -> IdeaIndexSemanticAdmission.Status.Ready(next)
                }
            },
            transitionAwaiter = raceAwaiter(),
        )
        ingress.bindRequest { order += "dispatch" }

        try {
            val outcome = runBlocking {
                ingress.reconcile(WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source))
            }

            assertEquals(WorkspaceTransitionOutcome.Published(next), outcome)
            assertEquals(listOf("route", "dispatch", "accept"), order)
        } finally {
            ingress.close()
        }
    }

    @Test
    fun `uncovered request retains a blocked outcome published during dispatch`() {
        val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(32))
        val admission = raceReadyAdmission(initial)
        val blocker = TransitionBlocker(
            phase = TransitionPhase.Reconciling,
            kind = TransitionBlockerKind.AdapterFailure,
            detail = "compiler reconciliation failed",
        )
        val ingress = WorkspaceTransitionIngress(admission, raceAwaiter())
        ingress.bindRequest {
            ingress.observe(
                WorkspaceTransitionSnapshot(
                    lifecycle = WorkspaceLifecycle.Blocked,
                    pendingSignals = emptySet(),
                    published = PublishedWorkspaceGenerationState.Published(initial),
                    blocker = blocker,
                    observedEventCount = 32,
                    activeSourceFreshness = WorkspaceSourceFreshness.Absent,
                ),
            )
        }

        try {
            val outcome = runBlocking {
                ingress.reconcile(WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source))
            }

            assertEquals(
                WorkspaceTransitionOutcome.Rejected(WorkspaceTransitionFailure.Blocked(blocker)),
                outcome,
            )
        } finally {
            ingress.close()
        }
    }
}

internal fun assertCoveredSourcePublicationRace(workspaceRoot: Path) {
    val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(20))
    val next = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(21))
    val admission = raceReadyAdmission(initial)
    val source = workspaceRoot.resolve("src/Racing.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, "class Racing\n")
    val request = captureSourceWorkspaceTransitionRequest(
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

        assertEquals(WorkspaceTransitionOutcome.Published(next), published)
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
    val request = captureSourceWorkspaceTransitionRequest(
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

        assertEquals(WorkspaceTransitionOutcome.Published(next), published)
        assertFalse(transitionRequested.get())
    } finally {
        ingress.close()
    }
}

internal fun assertCoveredSourceBlockedRegistrationRace(workspaceRoot: Path) {
    val initial = testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(24))
    val source = workspaceRoot.resolve("src/Blocked.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, "class Blocked\n")
    val request = captureSourceWorkspaceTransitionRequest(
        workspaceRoot = workspaceRoot,
        paths = listOf(NormalizedPath.of(source)),
    ) as WorkspaceTransitionRequest.SourceFiles
    val active = TransitionObservation.Observed(
        WorkspaceTransitionSnapshot(
            lifecycle = WorkspaceLifecycle.Reconciling,
            pendingSignals = emptySet(),
            published = PublishedWorkspaceGenerationState.Published(initial),
            blocker = null,
            observedEventCount = 6,
            activeSourceFreshness = WorkspaceSourceFreshness.Claimed(request.claims),
        ),
    )
    val join = WorkspaceTransitionRoute.derive(
        status = IdeaIndexSemanticAdmission.Status.Pending("covered source transition is active"),
        observation = active,
        request = request,
    ) as WorkspaceTransitionRoute.Join.Awaiting
    val blocker = TransitionBlocker(
        phase = TransitionPhase.Reconciling,
        kind = TransitionBlockerKind.AdapterFailure,
        detail = "compiler reconciliation failed",
    )

    val registration = WorkspaceTransitionJoinRegistration.derive(
        join = join,
        observation = TransitionObservation.Observed(
            WorkspaceTransitionSnapshot(
                lifecycle = WorkspaceLifecycle.Blocked,
                pendingSignals = emptySet(),
                published = PublishedWorkspaceGenerationState.Published(initial),
                blocker = blocker,
                observedEventCount = 7,
                activeSourceFreshness = WorkspaceSourceFreshness.Absent,
            ),
        ),
    )

    assertEquals(WorkspaceTransitionJoinRegistration.Blocked(blocker), registration)
}

private fun raceReadyAdmission(
    generation: PublishedWorkspaceGeneration,
): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(raceProjectStub()).also { admission ->
    val token = admission.beginReconciliation("test generation")
    check(
        admission.publishReady(token) { testWorkspacePublicationCommit(generation) } is
            IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
    )
}

private fun racePublish(
    admission: IdeaIndexSemanticAdmission,
    generation: PublishedWorkspaceGeneration,
) {
    admission.dirty("test transition")
    val token = admission.beginReconciliation("test reconciliation")
    check(
        admission.publishReady(token) { testWorkspacePublicationCommit(generation) } is
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
    generation: PublishedWorkspaceGeneration,
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
