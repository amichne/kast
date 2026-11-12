package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeComponentWorkspaceTest {
    @Test
    fun `changed control rejects before runtime or workspace effects`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val control = Files.writeString(root.resolve("control"), "changed")
        val input =
            NativeComponentInput(
                root.resolve("store").toString(),
                root.toString(),
                root.resolve("absent.zip").toString(),
                "a".repeat(64),
                control.toString(),
                "b".repeat(64),
                "1.2.3",
                "c".repeat(40),
            )
        assertEquals(Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH), NativeComponentWorkspace.admit(input))
        assertFalse(Files.exists(root.resolve("store")))
    }

    @Test
    fun `real archive admission preserves exact root and leases exclude retirement`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val input = componentInput(root)
        val admitted = NativeComponentWorkspace.admit(input)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        val selected = (admitted as Refinement.Refined).value
        assertEquals(root.resolve("project"), selected.root.path)
        val opened = selected.open(NativeComponentAccess.PREPARE)
        assertTrue(opened is Refinement.Refined, opened.toString())
        (opened as Refinement.Refined).value.use { lease ->
            assertEquals(input.runtimeSha256, lease.deployment.runtimeSha256)
            assertEquals(input.controlSha256, selected.identity.controlSha256)
            assertTrue(Files.isRegularFile(lease.deployment.profile.resolve("config/selected-ide.json")))
            val observed = lease.prepare {
                FileChannel.open(lease.deployment.profile.resolve("preparation.lock"), StandardOpenOption.WRITE).use {
                    channel ->
                    assertThrows(OverlappingFileLockException::class.java) { channel.tryLock() }
                }
                Unit
            }
            assertEquals(Refinement.Refined(Unit), observed)
            assertEquals(
                Refinement.Rejected(ContainerFailure.BUSY),
                selected.stop(NativeComponentStop.SELECTED) { error("must not retire a leased workspace") },
            )
        }
        assertEquals(
            Refinement.Rejected(ContainerFailure.LEASE_CLOSED),
            opened.value.prepare { error("closed request reached preparation") },
        )
        assertFalse(Files.exists(root.resolve("store/build-record.json")))
        assertTrue(NativeComponentWorkspace.select(input) is Refinement.Refined)
        Files.writeString(root.resolve("control"), "replaced")
        assertEquals(Refinement.Rejected(ContainerFailure.DIGEST_MISMATCH), NativeComponentWorkspace.select(input))
    }

    private fun componentInput(root: Path): NativeComponentInput {
        val workspace = Files.createDirectory(root.resolve("project"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val producer =
            ContainerStore(root.resolve("producer"), (ContainerPlatform.current() as Refinement.Refined).value)
        containerRuntimeFixture(producer)
        val archive =
            Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".zip") }.toList().single() }
        val control = Files.writeString(root.resolve("control"), "inert control identity; never executed")
        return NativeComponentInput(
            root.resolve("store").toString(),
            workspace.toString(),
            archive.toString(),
            sha256(archive),
            control.toString(),
            sha256(control),
            "1.2.3",
            "c".repeat(40),
        )
    }
}
