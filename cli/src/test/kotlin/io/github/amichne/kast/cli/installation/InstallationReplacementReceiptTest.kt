package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import io.github.amichne.kast.distribution.managed.writeInstallationReplacementReceipt
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationReplacementReceiptTest {
    @Test
    fun `pending record encodes a finite single payload transaction`() {
        val receipt =
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PAYLOAD_COMMITTED,
                installation = "/fixture/kast/installation",
                installationIdentity = InstallationFilesystemIdentity(1, 2, 3),
                previous = PreviousInstallationPayload.None,
            )
        val encoded = Json.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(receipt)).jsonObject
        assertEquals(setOf("schemaVersion", "stage", "installation", "installationIdentity", "previous"), encoded.keys)
        assertEquals("1", encoded.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("PAYLOAD_COMMITTED", encoded.getValue("stage").jsonPrimitive.content)
        assertEquals("/fixture/kast/installation", encoded.getValue("installation").jsonPrimitive.content)
        val identity = encoded.getValue("installationIdentity").jsonObject
        assertEquals(setOf("device", "inode", "owner"), identity.keys)
        assertEquals("1", identity.getValue("device").jsonPrimitive.content)
        assertEquals("2", identity.getValue("inode").jsonPrimitive.content)
        assertEquals("3", identity.getValue("owner").jsonPrimitive.content)
        val previous = encoded.getValue("previous").jsonObject
        assertEquals(setOf("type"), previous.keys)
        assertEquals("NONE", previous.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `finalization intent admits a removed rollback payload and rejects a changed surviving identity`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val installation = Files.createDirectory(root.resolve("installation"))
        val transaction = Files.createDirectories(root.resolve("recovery/replacement"))
        val previous = Files.createDirectory(transaction.resolve("payload"))
        val snapshot =
            PreviousInstallationPayload.Physical(
                installation.toString(),
                observeInstallationFilesystemIdentity(previous),
                previous.toString(),
                transaction.resolve("recovery").toString(),
            )
        val receipt =
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PAYLOAD_COMMITTED,
                installation = installation.toString(),
                installationIdentity = observeInstallationFilesystemIdentity(installation),
                previous = snapshot,
            )
        writeInstallationReplacementReceipt(transaction, receipt)
        Files.move(previous, root.resolve("retired"))
        assertEquals(PendingInstallationReplacement.RecoveryRequired, observePendingInstallationReplacement(root))
        val finalizing = receipt.copy(stage = InstallationReplacementStage.FINALIZING)
        writeInstallationReplacementReceipt(transaction, finalizing)
        assertEquals(PendingInstallationReplacement.Committed(finalizing), observePendingInstallationReplacement(root))
        Files.createDirectory(previous)
        assertEquals(PendingInstallationReplacement.RecoveryRequired, observePendingInstallationReplacement(root))
    }

    @Test
    fun `uncommitted and changed committed identity remain recovery required`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = Files.createDirectory(root.resolve("installation"))
        val transaction = Files.createDirectories(root.resolve("recovery/replacement"))
        val receipt =
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PREPARED,
                installation = installation.toString(),
                installationIdentity = observeInstallationFilesystemIdentity(installation),
                previous = PreviousInstallationPayload.None,
            )
        writeInstallationReplacementReceipt(transaction, receipt)
        assertEquals(PendingInstallationReplacement.RecoveryRequired, observePendingInstallationReplacement(root))
        val committed = receipt.copy(stage = InstallationReplacementStage.PAYLOAD_COMMITTED)
        writeInstallationReplacementReceipt(transaction, committed)
        assertEquals(PendingInstallationReplacement.Committed(committed), observePendingInstallationReplacement(root))
        Files.move(installation, root.resolve("foreign"))
        Files.createDirectory(installation)
        assertEquals(PendingInstallationReplacement.RecoveryRequired, observePendingInstallationReplacement(root))
    }
}
