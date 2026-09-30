package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SingleInstallationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `management admits one ordinary installation and all integration paths use that location`() {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val installation = root.resolve("installation").toRealPath()
        assertEquals(installation, selectedInstallation(root))
        assertEquals(
            root.resolve("installation/bin/kast-mcp-complete"),
            registrationDestinationFor(root, home, HarnessConnection.CODEX_MCP),
        )
        assertEquals(
            installation.resolve("bin/kast-codex-complete"),
            registrationSourceFor(installation, HarnessConnection.CODEX_APP_SERVER),
        )
        assertFalse(Files.isSymbolicLink(installation))
        assertEquals(
            listOf("installation", "management.json"),
            Files.list(root).use { it.map { file -> file.fileName.toString() }.sorted().toList() },
        )
    }

    @Test
    fun `symbolic installation rejects while preserving the target and management receipt`() {
        val (root, _) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val installation = root.resolve("installation")
        val retained = temporary.resolve("foreign-installation")
        val receipt = Files.readAllBytes(receiptPath(root)).toList()
        Files.move(installation, retained)
        Files.createSymbolicLink(installation, retained)
        val payload = Files.readAllBytes(retained.resolve("installation.json")).toList()
        assertThrows<ManagementRejected> { selectedInstallation(root) }
        assertEquals(receipt, Files.readAllBytes(receiptPath(root)).toList())
        assertEquals(payload, Files.readAllBytes(retained.resolve("installation.json")).toList())
        assertEquals(ObservationState.UNAVAILABLE, readStatus(root, "/owned/kast").resolvedInstallationPath.state)
    }

    @Test
    fun `legacy installation manifests are migration input and cannot qualify native effects`() {
        val (root, _) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val installation = root.resolve("installation")
        val manifest = (readBundledManifest(installation) as BundledManifestRead.Read).manifest
        listOf(1, 2).forEach { schemaVersion ->
            Files.writeString(
                installation.resolve("installation.json"),
                Json.encodeToString(manifest.copy(schemaVersion = schemaVersion)),
            )
            assertThrows<ManagementRejected> { selectedInstallation(root) }
            assertEquals(ObservationState.UNAVAILABLE, readStatus(root, "/owned/kast").resolvedInstallationPath.state)
        }
    }
}
