package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceWaitConfigurationTest {
    // Budget: one physical installation, workspace, selected file, and workspace overlay.
    // Configuration inspection only; no lifecycle request, IDE, or child process.
    @Test
    fun `explicit configuration selection survives workspace overlay and environment precedence`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val installation = Files.createDirectory(root.resolve("installation"))
        val workspace = canonicalRootFixture(Files.createDirectory(root.resolve("workspace")))
        val defaultFile = Files.createDirectories(installation.resolve("config")).resolve("environment")
        Files.writeString(defaultFile, "KAST_WORKSPACE_PREPARATION_WAIT=invalid\n")
        val selected = Files.writeString(root.resolve("selected-environment"), "KAST_WORKSPACE_PREPARATION_WAIT=41\n")
        val environment = mapOf("KAST_CONFIGURATION_FILE" to selected.toString())
        val configured = mcpWorkspaceOperationClient(root, environment, installation, workspace)
        val bounded = configured.preparationAllowance() as Refinement.Refined
        assertEquals(41L, bounded.value.protocolMillis())
        val identity =
            MessageDigest.getInstance("SHA-256")
                .digest(workspace.path.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val overlay =
            Files.createDirectories(installation.resolve("config/workspaces/$identity")).resolve("environment")
        Files.writeString(overlay, "KAST_WORKSPACE_PREPARATION_WAIT=indefinite\n")
        val overlaid = mcpWorkspaceOperationClient(root, environment, installation, workspace)
        assertEquals(Refinement.Refined(ElapsedTimeAllowance.Unlimited), overlaid.preparationAllowance())
        val overridden =
            mcpWorkspaceOperationClient(
                root,
                environment + ("KAST_WORKSPACE_PREPARATION_WAIT" to "25"),
                installation,
                workspace,
            )
        val explicit = overridden.preparationAllowance() as Refinement.Refined
        assertEquals(25L, explicit.value.protocolMillis())
        assertEquals("KAST_WORKSPACE_PREPARATION_WAIT=41\n", Files.readString(selected))
        assertEquals("KAST_WORKSPACE_PREPARATION_WAIT=invalid\n", Files.readString(defaultFile))
    }

    @Test
    fun `explicit waiting resolves the workspace overlay and environment precedence before lifecycle effects`(
        @TempDir temporary: Path
    ) {
        val installation = Files.createDirectory(temporary.resolve("installation")).toRealPath()
        val workspace = canonicalRootFixture(Files.createDirectory(temporary.resolve("workspace")).toRealPath())
        val identity =
            MessageDigest.getInstance("SHA-256")
                .digest(workspace.path.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val overlay =
            Files.createDirectories(installation.resolve("config/workspaces/$identity")).resolve("environment")
        Files.writeString(overlay, "KAST_WORKSPACE_PREPARATION_WAIT=indefinite\n")
        val configured = mcpWorkspaceOperationClient(temporary, emptyMap(), installation, workspace)
        assertEquals(Refinement.Refined(ElapsedTimeAllowance.Unlimited), configured.preparationAllowance())
        val selected =
            mcpWorkspaceOperationClient(
                temporary,
                mapOf("KAST_WORKSPACE_PREPARATION_WAIT" to "25"),
                installation,
                workspace,
            )
        val bounded = selected.preparationAllowance() as Refinement.Refined
        assertEquals(25L, bounded.value.protocolMillis())
        Files.writeString(overlay, "KAST_WORKSPACE_PREPARATION_WAIT=invalid\n")
        val rejected = mcpWorkspaceOperationClient(temporary, emptyMap(), installation, workspace)
        assertEquals(
            Refinement.Rejected(
                DaemonOperationFailure.Host(
                    io.github.amichne.kast.appserver.ide.ExistingIdeFailure.CONFIGURATION_REJECTED
                )
            ),
            rejected.preparationAllowance(),
        )
    }
}
