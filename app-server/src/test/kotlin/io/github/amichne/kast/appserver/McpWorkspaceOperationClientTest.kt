@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationActivity
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationActivityOutcome
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationObserver
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.nio.file.Path
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpWorkspaceOperationClientTest {
    @Test
    fun `startup queues one exact open and retains finite terminal evidence`() = runTest {
        val root = CanonicalRoot(Path.of("/workspace"))
        val requests = mutableListOf<WorkspaceLifecycleRequest>()
        val events = mutableListOf<WorkspacePreparationActivity>()
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    requests += request
                    IdeLifecycleResult.Opened(
                        IdeProjectTarget(
                            "00000000-0000-0000-0000-000000000002",
                            "00000000-0000-0000-0000-000000000003",
                            root.path.toString(),
                        )
                    )
                },
                observer = WorkspacePreparationObserver { events += it },
            )
        val session =
            McpWorkspaceOperationClient(DaemonOperationClient { _, _ -> error("no read requested") }, preparations)

        assertEquals(Refinement.Refined(Unit), session.start(root))
        assertEquals(Refinement.Refined(Unit), session.start(root))
        assertEquals(emptyList<WorkspaceLifecycleRequest>(), requests)
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(root.path.toString(), (requests.single() as WorkspaceLifecycleRequest.Open).root)
        assertEquals(
            listOf(
                WorkspacePreparationActivityOutcome.Pending(IdeLifecycleStage.OPENING),
                WorkspacePreparationActivityOutcome.Completed,
            ),
            events.map { it.outcome },
        )

        preparations.close()
        assertEquals(
            Refinement.Rejected(DaemonOperationFailure.Preparation(WorkspacePreparationFailure.CLOSED)),
            session.start(root),
        )
    }
}
