package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationRecoveryReceiptTest {
    @Test
    fun `physical installation prepares schema three with no selection or history`(@TempDir temporary: Path) {
        val installation = installation(temporary)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(installation))
        val receipt =
            Json.parseToJsonElement(Files.readString(temporary.resolve("recovery/installation/receipt.json")))
                .jsonObject
        assertEquals(
            setOf("schemaVersion", "installation", "installationIdentity", "plugin", "pluginRoot", "stage"),
            receipt.keys,
        )
        assertEquals("3", receipt.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(installation.toString(), receipt.getValue("installation").jsonPrimitive.content)
        assertEquals("Prepared", receipt.getValue("stage").jsonPrimitive.content)
        assertInstanceOf(InstallationRecoveryAdmission.Admitted::class.java, admitInstallationRecovery(installation))
        Files.walk(temporary).use { paths -> assertTrue(paths.noneMatch(Files::isSymbolicLink)) }
    }

    @Test
    fun `fresh preparation preserves an admitted existing record and rejects unproven recovery`(
        @TempDir temporary: Path
    ) {
        val installation = installation(temporary)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(installation))
        val receipt = installation.parent.resolve("recovery/installation/receipt.json")
        val encoded = Files.readString(receipt)
        val original = identity(receipt)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(installation))
        assertEquals(encoded, Files.readString(receipt))
        assertEquals(original, identity(receipt))
        Files.writeString(receipt, "unproven recovery")
        assertEquals(InstallationRecoveryPreparation.Rejected, prepareInstallationRecovery(installation))
        assertEquals("unproven recovery", Files.readString(receipt))
        assertEquals(original, identity(receipt))
    }

    @Test
    fun `unknown stage and changed installation identity reject without modifying the receipt`(
        @TempDir temporary: Path
    ) {
        val installation = installation(temporary)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(installation))
        for (document in
            listOf(
                ReceiptFixture(
                    installation = installation.toString(),
                    installationIdentity = identity(installation),
                    stage = "Unknown",
                ),
                ReceiptFixture(
                    installation = installation.toString(),
                    installationIdentity = identity(installation).copy(inode = -1),
                ),
            )) {
            val encoded = Json { encodeDefaults = true }.encodeToString(document)
            val path = temporary.resolve("recovery/installation/receipt.json")
            Files.writeString(path, encoded)
            assertEquals(InstallationRecoveryAdmission.Rejected, admitInstallationRecovery(installation))
            assertEquals(encoded, Files.readString(path))
        }
    }

    @Test
    fun `interrupted plugin preparation is resumable and cannot become a transferable baseline`(
        @TempDir temporary: Path
    ) {
        val installation = installation(temporary)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(installation))
        val plugins = Files.createDirectory(installation.parent.resolve("plugins"))
        val destination = Files.createDirectory(plugins.resolve("kast-ide-hosted"))
        val prior = identity(destination)
        val token = "a".repeat(32)
        val candidate = Files.createDirectory(plugins.resolve(".kast-ide-hosted.install-$token"))
        val backup = plugins.resolve(".kast-ide-hosted.baseline-$token")
        val quarantine = plugins.resolve(".kast-ide-hosted.detached-$token")
        val plugin =
            PluginFixture(
                destination.toString(),
                candidate.toString(),
                identity(candidate),
                backup.toString(),
                prior,
                quarantine.toString(),
            )
        val document =
            ReceiptFixture(
                installation = installation.toString(),
                installationIdentity = identity(installation),
                plugin = plugin,
                pluginRoot = plugins.toString(),
                stage = "PluginPrepared",
            )
        Files.writeString(
            temporary.resolve("recovery/installation/receipt.json"),
            Json { encodeDefaults = true }.encodeToString(document),
        )
        assertEquals(InstallationRecoveryAdmission.Pending(installation), admitInstallationRecovery(installation))
        Files.move(destination, backup)
        assertEquals(InstallationRecoveryAdmission.Pending(installation), admitInstallationRecovery(installation))
        Files.move(candidate, destination)
        assertEquals(InstallationRecoveryAdmission.Pending(installation), admitInstallationRecovery(installation))
        val finalizing = document.copy(stage = "UpgradeFinalizing")
        Files.writeString(
            temporary.resolve("recovery/installation/receipt.json"),
            Json { encodeDefaults = true }.encodeToString(finalizing),
        )
        assertEquals(InstallationRecoveryAdmission.Pending(installation), admitInstallationRecovery(installation))
        Files.delete(backup)
        assertEquals(InstallationRecoveryAdmission.Pending(installation), admitInstallationRecovery(installation))
        Files.move(destination, plugins.resolve("foreign"))
        Files.createDirectory(destination)
        assertEquals(InstallationRecoveryAdmission.Rejected, admitInstallationRecovery(installation))
    }

    @Test
    fun `historical host receipt stays in rollback bundle and never becomes control ownership`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val prior = installation(root)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(prior))
        val plugins = Files.createDirectory(root.resolve("plugins"))
        val host = Files.createDirectory(plugins.resolve("kast-ide-hosted"))
        val bytes = Files.writeString(host.resolve("host.jar"), "P1")
        val hostIdentity = identity(host)
        val token = "a".repeat(32)
        val historical =
            ReceiptFixture(
                installation = prior.toString(),
                installationIdentity = identity(prior),
                plugin =
                    PluginFixture(
                        host.toString(),
                        plugins.resolve(".kast-ide-hosted.install-$token").toString(),
                        hostIdentity,
                        plugins.resolve(".kast-ide-hosted.baseline-$token").toString(),
                        null,
                        plugins.resolve(".kast-ide-hosted.detached-$token").toString(),
                    ),
                pluginRoot = plugins.toString(),
                stage = "Active",
            )
        val path = root.resolve("recovery/installation/receipt.json")
        val encoded = Json { encodeDefaults = true }.encodeToString(historical)
        Files.writeString(path, encoded)
        val baseline =
            assertInstanceOf(InstallationRecoveryAdmission.Admitted::class.java, admitInstallationRecovery(prior))
                .baseline
        val retained = root.resolve("historical-control")
        Files.move(prior, retained)
        Files.move(root.resolve("recovery/installation"), root.resolve("historical-recovery"))
        val replacement = installation(root)
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(replacement, baseline))
        val current = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, current.getValue("plugin"))
        assertEquals(kotlinx.serialization.json.JsonNull, current.getValue("pluginRoot"))
        assertEquals(encoded, Files.readString(root.resolve("historical-recovery/receipt.json")))
        assertEquals(hostIdentity, identity(host))
        assertEquals("P1", Files.readString(bytes))
    }

    private fun installation(temporary: Path): Path {
        val installation = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val scripts = Files.createDirectories(installation.resolve("share/kast"))
        for (name in listOf("installation-recovery.py", "installation-lifecycle.py")) Files.writeString(
            scripts.resolve(name),
            "fixture",
        )
        return installation
    }
}

@Serializable
private data class ReceiptFixture(
    val schemaVersion: Int = 3,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val plugin: PluginFixture? = null,
    val pluginRoot: String? = null,
    val stage: String = "Prepared",
)

@Serializable
private data class PluginFixture(
    val destination: String,
    val candidate: String,
    val candidateIdentity: InstallationFilesystemIdentity,
    val backup: String,
    val priorIdentity: InstallationFilesystemIdentity?,
    val quarantine: String,
)

private fun identity(path: Path) =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )
