package io.github.amichne.kast.cli.ide

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RetiredApprovalArtifactsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh home cleanup creates no files`() {
        val home = temporary.toRealPath()
        assertEquals(RetiredApprovalOutcome.Absent, RetiredApprovalArtifacts(home).remove())
        assertFalse(Files.exists(home.resolve(".kast")))
    }

    @Test
    fun `retirement removes admitted keys and old lock while preserving unrelated state`() {
        val home = temporary.toRealPath()
        val approval = legacyArtifacts(home)
        val state = Files.createDirectories(home.resolve(".kast/mutation-state"))
        val sentinel = state.resolve("recovery-record")
        Files.writeString(sentinel, "protected")
        assertEquals(RetiredApprovalOutcome.Removed, RetiredApprovalArtifacts(home).remove())
        assertFalse(Files.exists(approval))
        assertEquals("protected", Files.readString(sentinel))
        assertEquals(RetiredApprovalOutcome.Absent, RetiredApprovalArtifacts(home).remove())
    }

    @Test
    fun `unrelated approval files survive admitted key retirement`() {
        val home = temporary.toRealPath()
        val approval = legacyArtifacts(home)
        val sentinel = approval.resolve("unrelated")
        Files.writeString(sentinel, "protected")
        assertEquals(RetiredApprovalOutcome.Removed, RetiredApprovalArtifacts(home).remove())
        assertEquals("protected", Files.readString(sentinel))
        assertFalse(Files.exists(approval.resolve("broker.pk8")))
        assertFalse(Files.exists(approval.resolve("broker.pub")))
    }

    @Test
    fun `malformed key refuses every deletion`() {
        val home = temporary.toRealPath()
        val approval = legacyArtifacts(home)
        Files.writeString(approval.resolve("broker.pub"), "invalid")
        assertEquals(
            RetiredApprovalOutcome.Retained(RetiredApprovalFailure.INVALID_KEY),
            RetiredApprovalArtifacts(home).remove(),
        )
        assertTrue(Files.exists(approval.resolve("broker.pk8")))
        assertTrue(Files.exists(approval.resolve(".enroll.lock")))
        assertEquals("invalid", Files.readString(approval.resolve("broker.pub")))
    }

    @Test
    fun `symlink key refuses deletion and preserves target`() {
        val home = temporary.toRealPath()
        val approval = legacyArtifacts(home)
        val privateKey = approval.resolve("broker.pk8")
        val protected = home.resolve("protected")
        Files.move(privateKey, protected)
        Files.createSymbolicLink(privateKey, protected)
        assertEquals(
            RetiredApprovalOutcome.Retained(RetiredApprovalFailure.UNSAFE_PATH),
            RetiredApprovalArtifacts(home).remove(),
        )
        assertTrue(Files.exists(protected))
        assertTrue(Files.exists(approval.resolve("broker.pub")))
    }

    private fun legacyArtifacts(home: Path): Path {
        val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        Files.createDirectory(home.resolve(".kast"), directoryMode)
        val approval = Files.createDirectory(home.resolve(".kast/approval"), directoryMode)
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        listOf(
                "broker.pk8" to pair.private.encoded,
                "broker.pub" to pair.public.encoded,
                ".enroll.lock" to byteArrayOf(),
            )
            .forEach { (name, bytes) ->
                val file =
                    Files.createFile(
                        approval.resolve(name),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                Files.write(file, bytes)
            }
        return approval
    }
}
