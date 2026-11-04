package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceWaitConfigurationTest {
    @Test
    fun `explicit waiting resolves the workspace overlay and environment precedence before lifecycle effects`(
        @TempDir temporary: Path
    ) {
        val installation = Files.createDirectory(temporary.resolve("installation")).toRealPath()
        val workspace = CanonicalRoot(Files.createDirectory(temporary.resolve("workspace")).toRealPath())
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
