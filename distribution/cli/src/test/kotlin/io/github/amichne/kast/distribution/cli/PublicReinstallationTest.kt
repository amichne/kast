package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicReinstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `receipt recovery repairs the recorded Pi directory with the new adapter`() {
        for (composition in HarnessComposition.entries) restorePiComposition(composition)
    }

    private fun restorePiComposition(composition: HarnessComposition) {
        val case = Files.createDirectory(temporary.resolve(composition.name))
        val (root, home) = integrationFixture(case, HarnessConnection.PI, "old adapter", composition)
        val selected = case.resolve("selected-pi")
        val directory =
            (ConnectionDirectory.admit(selected.toString()) as ConnectionDirectoryAdmission.Admitted).directory
        connectHarness(root, home, HarnessConnection.PI, directory = directory, composition = composition)
        val destination = registrationDestinationFor(root, HarnessConnection.PI, directory)
        val recovery = case.resolve("saved-connections.json")
        val environment =
            mapOf(
                "HOME" to home.toString(),
                "KAST_PUBLIC_RESET_RECOVERY" to recovery.toString(),
                "PI_CODING_AGENT_DIR" to case.resolve("unrelated-pi").toString(),
            )
        assertEquals(LifecycleEffect.Completed, savePublicResetConnections(root, environment))
        integrationFixture(case, HarnessConnection.PI, "new adapter", composition)
        assertEquals(LifecycleEffect.Completed, restorePublicResetConnections(root, environment))
        assertTrue(Files.readString(destination).contains("new adapter"))
        val receipt = (readReceipt(root) as ReceiptRead.Read).receipt
        assertEquals(selected.toString(), receipt.registrations.single().directory)
        assertEquals(composition, receipt.registrations.single().composition)
        assertFalse(Files.exists(case.resolve("unrelated-pi")))
    }

    @Test
    fun `receipt recovery keeps legacy Codex removal facts without recreating its launcher`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)
        val launcher = legacyAppServerRegistration(root, home)
        val registrations = (readReceipt(root) as ReceiptRead.Read).receipt.registrations
        val environment =
            mapOf(
                "HOME" to home.toString(),
                "KAST_PUBLIC_RESET_RECOVERY" to temporary.resolve("saved-connections.json").toString(),
            )
        assertEquals(LifecycleEffect.Completed, savePublicResetConnections(root, environment))
        Files.delete(launcher)
        integrationFixture(temporary, HarnessConnection.CODEX_APP_SERVER)

        assertEquals(LifecycleEffect.Completed, restorePublicResetConnections(root, environment))

        assertFalse(Files.exists(launcher))
        assertEquals(registrations, (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
    }

    @Test
    fun `saved connections survive erasure and foreign recovery receipts cannot select connections`() {
        val home = Files.createDirectories(temporary.toRealPath().resolve("home"))
        val root = Files.createDirectories(home.resolve(".local/share/kast"))
        val command = home.resolve(".local/bin/kast")
        val registration =
            ManagedRegistration(HarnessConnection.PI, home.resolve(".pi/extensions/kast.ts").toString(), "1".repeat(64))
        val receipt =
            ManagementReceipt(
                2,
                root.toString(),
                command.toString(),
                "0".repeat(64),
                ReleaseChannel.DEVELOPER,
                listOf(registration),
            )
        writeManagementReceipt(root, receipt)
        val recovery = temporary.resolve("recovery.json")
        val environment = mapOf("KAST_PUBLIC_RESET_RECOVERY" to recovery.toString())
        savePublicResetConnections(root, environment)
        Files.delete(root.resolve("management.json"))
        assertEquals(listOf(registration), publicResetConnections(root, environment))
        Files.writeString(
            recovery,
            managementJson.encodeToString(receipt.copy(installationRoot = home.resolve("foreign").toString())),
        )
        org.junit.jupiter.api.assertThrows<ManagementRejected> { publicResetConnections(root, environment) }
    }
}
