package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeLifecycleStateTest {
    private val state = IdeLifecycleState(UUID.randomUUID())
    private val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/worktree")) as Refinement.Refined).value
    private val client = LifecycleClient("client")

    private fun project(ownership: IdeProjectOwnership) = state.observe(UUID.randomUUID(), root, ownership)

    @Test
    fun `refresh rule configuration keeps exact project and request identity`() {
        val target = project(IdeProjectOwnership.BORROWED)
        val rule = WorkspaceRefreshRule.TaskSuccess(":generateSources", WorkspaceRefreshEffect.FILE_REFRESH)
        val command = IdeLifecycleCommand.ConfigureSync("configure", client.value, target, rule)
        val started = state.begin(command, LifecycleRequest("configure"), client)
        assertInstanceOf(LifecycleSubmission.Start::class.java, started)
        assertEquals(
            LifecycleSubmission.Existing((started as LifecycleSubmission.Start).pending),
            state.begin(command, LifecycleRequest("configure"), client),
        )
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.REQUEST_CONFLICT)),
            state.begin(command.copy(rule = WorkspaceRefreshRule.Off), LifecycleRequest("configure"), client),
        )
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.STALE_PROJECT)),
            state.begin(
                command.copy(requestId = "foreign", target = target.copy(project = UUID.randomUUID().toString())),
                LifecycleRequest("foreign"),
                client,
            ),
        )
        val result = IdeLifecycleResult.Configured(target, rule)
        state.complete(LifecycleRequest("configure"), result)
        assertEquals(result, state.status(LifecycleRequest("configure")))
    }

    @Test
    fun `zero projects is valid and duplicate opens join with conflict rejection`() {
        assertTrue(state.inspect().isEmpty())
        val command = IdeLifecycleCommand.Open(state.host.toString(), "first", client.value, root.value)
        val first = state.begin(command, LifecycleRequest("first"), client)
        assertInstanceOf(LifecycleSubmission.Start::class.java, first)
        assertInstanceOf(
            LifecycleSubmission.Existing::class.java,
            state.begin(command.copy(requestId = "second"), LifecycleRequest("second"), client),
        )
        val target = project(IdeProjectOwnership.MANAGED)
        state.complete(LifecycleRequest("first"), IdeLifecycleResult.Opened(target))
        assertEquals(IdeLifecycleResult.Opened(target), state.status(LifecycleRequest("second")))
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.REQUEST_CONFLICT)),
            state.begin(command.copy(root = "/other"), LifecycleRequest("first"), client),
        )
    }

    @Test
    fun `joined opens retain each request identity through progress and completion`() {
        val first = IdeLifecycleCommand.Open(state.host.toString(), "first", client.value, root.value)
        val second = first.copy(requestId = "second", client = "other")
        assertEquals(
            LifecycleSubmission.Start(
                IdeLifecycleResult.Pending("first", IdeLifecycleStage.OPENING, state.host.toString())
            ),
            state.begin(first, LifecycleRequest("first"), client),
        )
        assertEquals(
            LifecycleSubmission.Existing(
                IdeLifecycleResult.Pending("second", IdeLifecycleStage.OPENING, state.host.toString())
            ),
            state.begin(second, LifecycleRequest("second"), LifecycleClient("other")),
        )
        state.progress(LifecycleRequest("first"), IdeLifecycleStage.IMPORTING)
        assertEquals(
            IdeLifecycleResult.Pending("first", IdeLifecycleStage.IMPORTING, state.host.toString()),
            state.status(LifecycleRequest("first")),
        )
        assertEquals(
            IdeLifecycleResult.Pending("second", IdeLifecycleStage.IMPORTING, state.host.toString()),
            state.status(LifecycleRequest("second")),
        )
        val target = project(IdeProjectOwnership.MANAGED)
        state.complete(LifecycleRequest("first"), IdeLifecycleResult.Opened(target))
        assertEquals(IdeLifecycleResult.Opened(target), state.status(LifecycleRequest("second")))
        assertEquals(2, state.inspect().single().users)
    }

    @Test
    fun `borrowed and presented projects cannot be cleaned up`() {
        for (ownership in listOf(IdeProjectOwnership.BORROWED, IdeProjectOwnership.PRESENTED)) {
            val target = project(ownership)
            assertEquals(
                LifecycleSubmission.Existing(
                    IdeLifecycleResult.Blocked(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)
                ),
                state.begin(
                    IdeLifecycleCommand.Close("close-$ownership", client.value, target),
                    LifecycleRequest("close-$ownership"),
                    client,
                ),
            )
        }
    }

    @Test
    fun `close veto restores admission and successful close remains after retirement`() {
        val target = project(IdeProjectOwnership.MANAGED)
        state.begin(IdeLifecycleCommand.Close("close", client.value, target), LifecycleRequest("close"), client)
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.PROJECT_BUSY)),
            state.begin(
                IdeLifecycleCommand.Open(state.host.toString(), "attach", "other", root.value),
                LifecycleRequest("attach"),
                LifecycleClient("other"),
            ),
        )
        state.complete(LifecycleRequest("close"), IdeLifecycleResult.Blocked(IdeLifecycleFailure.CLOSE_VETOED))
        assertEquals(0, state.inspect().single().users)
        assertInstanceOf(
            LifecycleSubmission.Start::class.java,
            state.begin(IdeLifecycleCommand.Close("retry", client.value, target), LifecycleRequest("retry"), client),
        )
        state.retire(UUID.fromString(target.project))
        state.complete(LifecycleRequest("retry"), IdeLifecycleResult.Closed(target))
        assertEquals(IdeLifecycleResult.Closed(target), state.status(LifecycleRequest("retry")))
        val successor = project(IdeProjectOwnership.BORROWED)
        assertNotEquals(target.project, successor.project)
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Closed(target)),
            state.begin(IdeLifecycleCommand.Close("retry", client.value, target), LifecycleRequest("retry"), client),
        )
    }
}
