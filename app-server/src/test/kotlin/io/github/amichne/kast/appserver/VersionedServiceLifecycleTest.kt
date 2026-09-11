package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
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

    private fun product(root: Path): Path {
        listOf("bin", "lib", "share").forEach { Files.createDirectories(root.resolve(it)) }
        Files.writeString(root.resolve("lib/control.jar"), "payload")
        Files.writeString(root.resolve("bin/kast"), "launcher")
        return root.toRealPath()
    }
}
