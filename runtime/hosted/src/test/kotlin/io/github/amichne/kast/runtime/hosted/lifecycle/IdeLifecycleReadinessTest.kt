package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Real readiness policy and coroutine deadline; detached observations do not reproduce an IDE. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdeLifecycleReadinessTest {
    private val facts =
        WorkspaceReadinessFixture(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        )
    private val target = IdeProjectTarget("host", "project", "/workspace")

    private fun missing() =
        WorkspaceCapabilityReadiness.Unavailable(
            facts.identity,
            WorkspaceReadinessReason.MODEL_INCOMPLETE,
            WorkspaceReadinessNextAction.REFRESH_MODEL,
        )

    @Test
    fun `settled model obligation gets one reload and fresh admission within remaining parent budget`() = runTest {
        val operation =
            (NativeWorkspacePreparation.admit("parent", 1000, { testScheduler.currentTime * NANOS_PER_MILLI })
                    as Refinement.Refined)
                .value
        delay(400)
        var current: WorkspaceCapabilityReadiness = missing()
        var reloads = 0
        val result =
            IdeLifecycleReadiness(
                    { false },
                    target,
                    { current },
                    {
                        reloads++
                        assertEquals(600, (operation.remaining() as Refinement.Refined).value.value)
                        delay(100)
                        current = facts.ready()
                        IdeLifecycleResult.Synced(target)
                    },
                    operation,
                )
                .await()
        assertEquals(IdeLifecycleResult.Opened(target), result)
        assertEquals(1, reloads)
        assertEquals(600, testScheduler.currentTime)
    }

    @Test
    fun `indexing consumes parent allowance without a competing reload`() = runTest {
        val operation =
            (NativeWorkspacePreparation.admit("parent", 1000, { testScheduler.currentTime * NANOS_PER_MILLI })
                    as Refinement.Refined)
                .value
        delay(700)
        var reloads = 0
        val result =
            IdeLifecycleReadiness(
                    { false },
                    target,
                    {
                        WorkspaceCapabilityReadiness.Pending(
                            facts.identity,
                            WorkspaceReadinessReason.INDEXING,
                            WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                        )
                    },
                    {
                        reloads++
                        error("Indexing must not trigger an import")
                    },
                    operation,
                )
                .await() as IdeLifecycleResult.Blocked
        assertEquals(IdeLifecycleFailure.DEADLINE_EXCEEDED, result.reason)
        assertEquals(1000, testScheduler.currentTime)
        assertEquals(0, reloads)
    }

    @Test
    fun `a second model obligation ends preparation without another reload or manufactured Ready`() = runTest {
        val operation =
            (NativeWorkspacePreparation.admit("parent", 1000, { testScheduler.currentTime * NANOS_PER_MILLI })
                    as Refinement.Refined)
                .value
        var reloads = 0
        val result =
            IdeLifecycleReadiness(
                    { false },
                    target,
                    ::missing,
                    {
                        reloads++
                        IdeLifecycleResult.Synced(target)
                    },
                    operation,
                )
                .await() as IdeLifecycleResult.Blocked
        assertEquals(IdeLifecycleFailure.MODEL_RELOAD_REQUIRED, result.reason)
        assertEquals(1, reloads)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
