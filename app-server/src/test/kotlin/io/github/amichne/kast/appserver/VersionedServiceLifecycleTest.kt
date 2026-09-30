package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VersionedServiceLifecycleTest {
    @Test
    fun `restart retains installation epoch while separate installations remain distinct`(@TempDir root: Path) {
        val first = product(root.resolve("a"))
        val second = product(root.resolve("b"))
        val a = (BrokerInstallationState.admit(first) as Refinement.Refined).value
        assertEquals(a, (BrokerInstallationState.admit(first) as Refinement.Refined).value)
        assertNotEquals(a, (BrokerInstallationState.admit(second) as Refinement.Refined).value)
    }

    @Test
    fun `changed payload cannot reuse an old epoch`(@TempDir root: Path) {
        val installation = product(root.resolve("product"))
        assertTrue(BrokerInstallationState.admit(installation) is Refinement.Refined)
        val epoch = Files.readString(installation.resolve("state/epoch.json"))
        Files.writeString(installation.resolve("lib/control.jar"), "different payload")
        assertEquals(
            Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED),
            BrokerInstallationState.admit(installation),
        )
        assertEquals(epoch, Files.readString(installation.resolve("state/epoch.json")))
    }

    @Test
    fun `malformed epoch and foreign state never become fresh ownership`(@TempDir root: Path) {
        val installation = product(root.resolve("product"))
        val state = Files.createDirectory(installation.resolve("state"))
        Files.writeString(state.resolve("epoch.json"), "{}")
        assertEquals(
            Refinement.Rejected(InstallationStateFailure.EPOCH_REJECTED),
            BrokerInstallationState.admit(installation),
        )
        assertEquals("{}", Files.readString(state.resolve("epoch.json")))
        val other = product(root.resolve("other"))
        Files.createSymbolicLink(other.resolve("state"), state)
        assertEquals(Refinement.Rejected(InstallationStateFailure.PATH_REJECTED), BrokerInstallationState.admit(other))
        assertEquals("{}", Files.readString(state.resolve("epoch.json")))
    }

    @Test
    fun `replacement preserves an admitted epoch for identical final payload identity`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val prior = product(root.resolve("installation"))
        val owner = (BrokerInstallationState.admit(prior) as Refinement.Refined).value
        val staged = product(root.resolve(".install-candidate"))
        val copied = copyEpoch(prior, staged)
        val source = Files.readString(prior.resolve("state/epoch.json"))
        assertEquals(InstalledEpochRetention.Preserved, InstalledEpochRetention.retain(prior, staged, prior))
        assertEquals(source, Files.readString(copied))
        assertEquals(
            Refinement.Refined(owner),
            BrokerInstallationState.observeCopiedEpoch(staged.resolve("state"), owner.installationId.value),
        )
    }

    @Test
    fun `changed replacement discards only the admitted copied epoch and startup creates the new owner`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val prior = product(root.resolve("installation"))
        val owner = (BrokerInstallationState.admit(prior) as Refinement.Refined).value
        val source = Files.readString(prior.resolve("state/epoch.json"))
        val staged = product(root.resolve(".install-candidate"))
        Files.writeString(staged.resolve("lib/control.jar"), "next payload")
        val copied = copyEpoch(prior, staged)
        val journal = Files.writeString(staged.resolve("state/protected-journal"), "protected recovery evidence")
        assertEquals(InstalledEpochRetention.Regenerate, InstalledEpochRetention.retain(prior, staged, prior))
        assertFalse(Files.exists(copied))
        assertEquals(source, Files.readString(prior.resolve("state/epoch.json")))
        assertEquals("protected recovery evidence", Files.readString(journal))
        Files.move(prior, root.resolve("retired"))
        Files.move(staged, prior)
        val next = (BrokerInstallationState.admit(prior) as Refinement.Refined).value
        assertNotEquals(owner.installationId, next.installationId)
        assertNotEquals(owner.stateEpoch, next.stateEpoch)
        assertEquals("protected recovery evidence", Files.readString(prior.resolve("state/protected-journal")))
    }

    @Test
    fun `unadmitted copied epoch is never deleted`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val prior = product(root.resolve("installation"))
        assertTrue(BrokerInstallationState.admit(prior) is Refinement.Refined)
        val staged = product(root.resolve(".install-candidate"))
        Files.writeString(staged.resolve("lib/control.jar"), "next payload")
        val copied = copyEpoch(prior, staged)
        Files.writeString(copied, "corrupt copied epoch")
        assertEquals(
            InstalledEpochRetention.Rejected(InstalledEpochRetentionFailure.COPIED_EPOCH_REJECTED),
            InstalledEpochRetention.retain(prior, staged, prior),
        )
        assertEquals("corrupt copied epoch", Files.readString(copied))
    }

    private fun copyEpoch(prior: Path, staged: Path): Path {
        Files.createDirectory(staged.resolve("state"))
        return Files.copy(
            prior.resolve("state/epoch.json"),
            staged.resolve("state/epoch.json"),
            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
        )
    }

    @Test
    fun `current distribution above historical inventory ceiling is admitted`(@TempDir root: Path) {
        val installation = product(root.resolve("product"))
        repeat(4_097) { Files.writeString(installation.resolve("share/resource-$it"), "") }
        assertTrue(BrokerInstallationState.admit(installation) is Refinement.Refined)
    }

    @Test
    fun `traversal budget applies across all payload trees`(@TempDir root: Path) {
        val installation = product(root.resolve("product"))
        for (tree in listOf("bin", "lib", "share")) {
            repeat(5_500) { Files.createDirectory(installation.resolve("$tree/d-$it")) }
        }
        assertTrue(BrokerInstallationState.admit(installation) is Refinement.Rejected)
        assertFalse(Files.exists(installation.resolve("state")))
    }

    @Test
    fun `shared admission preserves the historical payload identity bytes`(@TempDir root: Path) {
        val installation = product(root.resolve("product"))
        assertTrue(BrokerInstallationState.admit(installation) is Refinement.Refined)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
        expected.update(installation.toString().toByteArray())
        for ((path, content) in listOf("bin/kast" to "launcher", "lib/control.jar" to "payload")) {
            expected.update(0)
            expected.update(path.toByteArray())
            expected.update(0)
            expected.update(content.toByteArray())
        }
        val epoch =
            kotlinx.serialization.json.Json.parseToJsonElement(
                Files.readString(installation.resolve("state/epoch.json"))
            )
        assertEquals(
            "sha256:" + java.util.HexFormat.of().formatHex(expected.digest()),
            epoch.jsonObject.getValue("installation").jsonPrimitive.content,
        )
    }

    private fun product(root: Path): Path {
        listOf("bin", "lib", "share").forEach { Files.createDirectories(root.resolve(it)) }
        Files.writeString(root.resolve("lib/control.jar"), "payload")
        Files.writeString(root.resolve("bin/kast"), "launcher")
        return root.toRealPath()
    }
}
