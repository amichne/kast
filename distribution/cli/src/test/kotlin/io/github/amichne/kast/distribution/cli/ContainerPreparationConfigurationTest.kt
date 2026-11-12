package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerPreparationConfigurationTest {
    @Test
    fun `preparation uses exact workspace policy and rejects malformed overrides`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val installation = Files.createDirectories(home.resolve(".local/share/kast/installation"))
        val workspace = Files.createDirectory(home.resolve("workspace"))
        val neighbor = Files.createDirectory(home.resolve("neighbor"))
        val config = Files.createDirectories(installation.resolve("config"))
        Files.writeString(config.resolve("environment"), "KAST_WORKSPACE_PREPARATION_WAIT=71000\n")
        val key = MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray()).toHexString()
        val overlay = Files.createDirectories(config.resolve("workspaces/$key"))
        Files.writeString(overlay.resolve("environment"), "KAST_WORKSPACE_PREPARATION_WAIT=23000\n")
        val environment = mapOf("HOME" to home.toString())
        val selected = containerPreparationConfiguration(installation, workspace, environment) as Refinement.Refined
        val unrelated = containerPreparationConfiguration(installation, neighbor, environment) as Refinement.Refined
        assertEquals(23000.milliseconds, selected.value.workspacePreparationWait.duration)
        assertEquals(71000.milliseconds, unrelated.value.workspacePreparationWait.duration)
        val overridden =
            containerPreparationConfiguration(
                installation,
                workspace,
                environment + ("KAST_WORKSPACE_PREPARATION_WAIT" to "13000"),
            )
                as Refinement.Refined
        assertEquals(13000.milliseconds, overridden.value.workspacePreparationWait.duration)
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_CONFIGURATION),
            containerPreparationConfiguration(
                installation,
                workspace,
                environment + ("KAST_WORKSPACE_PREPARATION_WAIT" to "invalid"),
            ),
        )
        assertFalse(Files.exists(home.resolve(".local/share/kast-workspaces")))
    }
}
