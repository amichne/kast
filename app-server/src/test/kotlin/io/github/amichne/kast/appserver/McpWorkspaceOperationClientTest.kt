@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class McpWorkspaceOperationClientTest {
    @Test
    fun `health preparation waits for the single native operation and preserves a blocked reason`() = runTest {
        val root = CanonicalRoot(Path.of("/workspace"))
        val response = CompletableDeferred<IdeLifecycleResult>()
        var opens = 0
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    assertEquals(root.path.toString(), (request as WorkspaceLifecycleRequest.Open).root)
                    opens++
                    response.await()
                },
            )
        val session =
            McpWorkspaceOperationClient(
                DaemonOperationClient { _, _ -> error("health preparation cannot send a semantic operation") },
                preparations,
            )
        assertEquals(Refinement.Refined(Unit), session.start(root))
        val waiting = async { session.awaitPreparation(root) }
        runCurrent()
        assertEquals(1, opens)
        assertFalse(waiting.isCompleted)
        response.complete(
            IdeLifecycleResult.Blocked(io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.PLUGIN_UNAVAILABLE)
        )
        val failure = (waiting.await() as Refinement.Rejected).failure as DaemonOperationFailure.Workspace
        assertEquals(
            io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Lifecycle(
                io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.PLUGIN_UNAVAILABLE
            ),
            failure.cause,
        )
        assertEquals(1, opens)
        preparations.close()
    }

    @Test
    fun `health preparation accepts only the completed exact workspace`() = runTest {
        val root = CanonicalRoot(Path.of("/workspace"))
        val response = CompletableDeferred<IdeLifecycleResult>()
        val preparations = WorkspacePreparations(this, { response.await() })
        val session =
            McpWorkspaceOperationClient(
                DaemonOperationClient { _, _ -> error("no semantic operation expected") },
                preparations,
            )
        val waiting = async { session.awaitPreparation(root) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        response.complete(
            IdeLifecycleResult.Opened(
                IdeProjectTarget(
                    "00000000-0000-0000-0000-000000000002",
                    "00000000-0000-0000-0000-000000000003",
                    root.path.toString(),
                )
            )
        )
        assertEquals(Refinement.Refined(Unit), waiting.await())
        preparations.close()
        assertEquals(
            Refinement.Rejected(DaemonOperationFailure.Preparation(WorkspacePreparationFailure.CLOSED)),
            session.awaitPreparation(root),
        )
    }

    @Test
    fun `inspection uses the session lifecycle client without semantic read`() {
        val request = WorkspaceLifecycleRequest.Inspect
        val inspected =
            IdeLifecycleResult.Inspected(
                host = "host",
                home = "/ide",
                build = "build",
                projects = emptyList(),
                compatibility =
                    io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument(
                        ideBuild = "262.1.1",
                        kotlinPluginBuild = "262.1.1-IJ",
                        hostedPluginVersion = "0.49.0",
                        hostedContract = io.github.amichne.kast.protocol.wire.CanonicalHostedContract.document,
                    ),
            )
        var observedClient: String? = null
        var observedRequest: WorkspaceLifecycleRequest? = null
        val lifecycle =
            object : WorkspaceLifecycleClient {
                override fun execute(request: WorkspaceLifecycleRequest, client: String): IdeLifecycleResult {
                    observedRequest = request
                    observedClient = client
                    return inspected
                }

                override fun approvedClose(
                    invocation: io.github.amichne.kast.protocol.contract.ApprovedProjectCloseInvocation,
                    client: String,
                ): IdeLifecycleResult = error("close was not requested")
            }
        val session =
            McpWorkspaceOperationClient(
                DaemonOperationClient { _, _ -> error("semantic read was not requested") },
                null,
                lifecycle,
            )

        assertEquals(inspected, session.lifecycle(request))
        assertEquals(request, observedRequest)
        assertEquals("kast-mcp", observedClient)
    }

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
