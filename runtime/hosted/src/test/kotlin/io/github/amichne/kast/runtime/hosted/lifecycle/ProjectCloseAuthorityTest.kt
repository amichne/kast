package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.ProjectCloseApprovalPayload
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectCloseAuthorityTest {
    @TempDir lateinit var home: Path
    private val state = IdeLifecycleState(UUID.randomUUID())
    private val client = LifecycleClient("thread")

    private fun confirmation(target: IdeProjectTarget) =
        ProjectCloseApprovalPayload(target, "close", "thread", "thread", "turn", "call")

    private fun target() =
        state.observe(
            UUID.randomUUID(),
            (CanonicalWorkspaceRoot.fromCanonicalPath(home) as Refinement.Refined).value,
            IdeProjectOwnership.PRESENTED,
        )

    @Test
    fun `exact local close intent can close presented project but never another incarnation`() {
        val target = target()
        val wire = IdeLifecycleCommand.AuthorizedClose("close", client.value, target, confirmation(target))
        val authority = (ProjectCloseAuthority.UserDirected.admit(wire) as Refinement.Refined).value
        assertInstanceOf(
            LifecycleSubmission.Start::class.java,
            state.begin(
                IdeLifecycleCommand.Close("close", client.value, target),
                LifecycleRequest("close"),
                client,
                authority,
            ),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            ProjectCloseAuthority.UserDirected.admit(wire.copy(target = target())),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            ProjectCloseAuthority.UserDirected.admit(wire.copy(client = "other")),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            ProjectCloseAuthority.UserDirected.admit(wire.copy(requestId = "replay")),
        )
    }

    @Test
    fun `authority never overrides shared use and release never closes`() {
        val target = target()
        state.begin(
            IdeLifecycleCommand.Present("present", "other", target),
            LifecycleRequest("present"),
            LifecycleClient("other"),
        )
        state.complete(LifecycleRequest("present"), IdeLifecycleResult.Presented(target))
        val wire = IdeLifecycleCommand.AuthorizedClose("close", client.value, target, confirmation(target))
        val authority = (ProjectCloseAuthority.UserDirected.admit(wire) as Refinement.Refined).value
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.OTHER_CLIENTS)),
            state.begin(
                IdeLifecycleCommand.Close("close", client.value, target),
                LifecycleRequest("close"),
                client,
                authority,
            ),
        )
        state.begin(
            IdeLifecycleCommand.Release("release", "other", target),
            LifecycleRequest("release"),
            LifecycleClient("other"),
        )
        assertEquals(0, state.inspect().single().users)
        assertEquals(IdeProjectOwnership.PRESENTED, state.inspect().single().ownership)
        assertInstanceOf(
            LifecycleSubmission.Start::class.java,
            state.begin(
                IdeLifecycleCommand.Close("close", client.value, target),
                LifecycleRequest("close"),
                client,
                authority,
            ),
        )
    }

    @Test
    fun `missing or mismatched controller invocation fails before close`() {
        val target = target()
        val payload = confirmation(target)
        val wire = IdeLifecycleCommand.AuthorizedClose("close", client.value, target, payload)
        listOf(payload.copy(threadId = "other"), payload.copy(turnId = ""), payload.copy(callId = "a".repeat(4097)))
            .forEach { invalid ->
                assertInstanceOf(
                    Refinement.Rejected::class.java,
                    ProjectCloseAuthority.UserDirected.admit(wire.copy(confirmation = invalid)),
                )
            }
        assertEquals(
            LifecycleSubmission.Existing(IdeLifecycleResult.Blocked(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)),
            state.begin(IdeLifecycleCommand.Close("close", client.value, target), LifecycleRequest("close"), client),
        )
    }
}
