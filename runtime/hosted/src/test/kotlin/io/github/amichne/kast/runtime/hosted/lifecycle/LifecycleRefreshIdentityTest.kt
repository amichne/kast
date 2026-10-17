@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleRefreshIdentityTest {
    @Test
    fun `child refresh is polled by issued identity while parent stage and deadline stay monotonic`() = runTest {
        val operation =
            (NativeWorkspacePreparation.admit("parent", 1000, { testScheduler.currentTime * NANOS_PER_MILLI })
                    as Refinement.Refined)
                .value
        operation.enter(IdeLifecycleStage.ADMISSION)
        val calls = mutableListOf<String>()
        val stages = mutableListOf<IdeLifecycleStage>()
        val target = IdeProjectTarget("host", "project", "/workspace")
        val result =
            awaitLifecycleRefresh(
                target,
                operation,
                WorkspaceRefreshResult.Pending("child", WorkspaceRefreshStage.EFFECT),
                {
                    calls += it
                    if (calls.size == 1) WorkspaceRefreshResult.Pending("child", WorkspaceRefreshStage.EFFECT)
                    else WorkspaceRefreshResult.Complete("child")
                },
                stages::add,
            )
        assertEquals(listOf("child", "child"), calls)
        assertEquals(listOf(IdeLifecycleStage.ADMISSION), stages)
        assertEquals("parent", operation.requestId)
        assertEquals(900, (operation.remaining() as Refinement.Refined).value.value)
        assertEquals(IdeLifecycleResult.Synced(target), result)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
