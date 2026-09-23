package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentWorkspaceSurfaceTest {
    @Test
    fun `workspace lifecycle remains an agent tool without a public CLI command`() {
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val help = assertInstanceOf(CliCommandParsing.Help::class.java, graph.parse(listOf("--help")))
        assertFalse(Regex("(?m)^\\s+workspace\\s").containsMatchIn(help.document.value))
        assertTrue(graph.surface.localCommands.none { it.usage.startsWith("workspace ") })
        assertInstanceOf(
            CliCommandParsing.Rejected::class.java,
            graph.parse(listOf("workspace", "open", "/worktree")),
        )
        assertInstanceOf(
            CliCommandParsing.Rejected::class.java,
            graph.parse(listOf("workspace", "lifecycle", "retired")),
        )
        val projection = installedServerProjection(graph.surface)
        assertTrue(projection.hostedBootstrap.tools.any { it.name == "workspace_lifecycle" })
        assertFalse(projection.cliInvocations.operations.any { it.toolName == "workspace_lifecycle" })
    }
}
