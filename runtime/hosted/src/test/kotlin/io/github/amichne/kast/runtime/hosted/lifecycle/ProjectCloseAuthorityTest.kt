package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.ProjectCloseApprovalPayload
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectCloseAuthorityTest {
    @TempDir lateinit var home: Path
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val state = IdeLifecycleState(UUID.randomUUID())
    private val client = LifecycleClient("thread")

    private fun enroll() {
        val directory = Files.createDirectories(home.resolve(".kast/approval"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val key = Files.write(directory.resolve("broker.pub"), pair.public.encoded)
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"))
    }

    private fun assertion(target: IdeProjectTarget): String {
        val payload = Json {
            encodeDefaults = true
        }
            .encodeToString(ProjectCloseApprovalPayload(target, "close", "thread", "thread", "turn", "call"))
            .toByteArray()
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(payload)
                sign()
            }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(payload)}.${encoder.encodeToString(signature)}"
    }

    private fun target() =
        state.observe(
            UUID.randomUUID(),
            (CanonicalWorkspaceRoot.fromCanonicalPath(home) as Refinement.Refined).value,
            IdeProjectOwnership.PRESENTED,
        )

    @Test
    fun `signed exact target can close presented project but never another incarnation`() {
        enroll()
        val target = target()
        val wire = IdeLifecycleCommand.AuthorizedClose("close", client.value, target, assertion(target))
        val authority = (ProjectCloseAuthority.UserApproved.verify(home, wire) as Refinement.Refined).value
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
            ProjectCloseAuthority.UserApproved.verify(home, wire.copy(target = target())),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            ProjectCloseAuthority.UserApproved.verify(home, wire.copy(client = "other")),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            ProjectCloseAuthority.UserApproved.verify(home, wire.copy(requestId = "replay")),
        )
    }

    @Test
    fun `authority never overrides shared use and release never closes`() {
        enroll()
        val target = target()
        state.begin(
            IdeLifecycleCommand.Present("present", "other", target),
            LifecycleRequest("present"),
            LifecycleClient("other"),
        )
        state.complete(LifecycleRequest("present"), IdeLifecycleResult.Presented(target))
        val wire = IdeLifecycleCommand.AuthorizedClose("close", client.value, target, assertion(target))
        val authority = (ProjectCloseAuthority.UserApproved.verify(home, wire) as Refinement.Refined).value
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
    fun `missing and replaced authority fail closed`() {
        val target = target()
        val wire = IdeLifecycleCommand.AuthorizedClose("close", client.value, target, assertion(target))
        assertInstanceOf(Refinement.Rejected::class.java, ProjectCloseAuthority.UserApproved.verify(home, wire))
        enroll()
        Files.write(
            home.resolve(".kast/approval/broker.pub"),
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded,
        )
        assertInstanceOf(Refinement.Rejected::class.java, ProjectCloseAuthority.UserApproved.verify(home, wire))
    }
}
