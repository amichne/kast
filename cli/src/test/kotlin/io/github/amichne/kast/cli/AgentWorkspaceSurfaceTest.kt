package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.workspace.WorkspaceLifecycleAction
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentWorkspaceSurfaceTest {
    @Test
    fun `workspace lifecycle is visible and invocable by users and the agent runtime`() {
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val help = assertInstanceOf(CliCommandParsing.Help::class.java, graph.parse(listOf("--help")))
        assertTrue(Regex("(?m)^\\s+workspace\\s").containsMatchIn(help.document.value))
        assertTrue(graph.surface.localCommands.any { it.usage.startsWith("workspace ") })
        assertInstanceOf(CliCommandParsing.Rejected::class.java, graph.parse(listOf("workspace", "open", "/worktree")))
        val request = WorkspaceLifecycleRequest.Open("/worktree", "agent-open-1")
        val parsed =
            assertInstanceOf(
                CliCommandParsing.Parsed::class.java,
                graph.parse(
                    listOf(
                        "workspace",
                        "lifecycle",
                        Json.encodeToString<WorkspaceLifecycleRequest>(request),
                        "--client",
                        "thread-1",
                    )
                ),
            )
        val action = assertInstanceOf(CliAction.Local.WorkspaceLifecycle::class.java, parsed.action)
        assertEquals(WorkspaceLifecycleAction.Control(request, "thread-1"), action.action)
        assertTrue(
            installedServerProjection(graph.surface).hostedBootstrap.tools.any { it.name == "workspace_lifecycle" }
        )
    }
}
