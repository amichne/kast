package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpWorkspaceRefreshToolTest {
    @TempDir lateinit var root: Path

    @Test
    fun `refresh selects the exact open project and returns bounded pending status`() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val target = IdeProjectTarget("host", "project", root.toRealPath().toString())
        val other = IdeProjectTarget("host", "other", "/another-workspace")
        val observed = mutableListOf<WorkspaceLifecycleRequest>()
        val tool =
            McpWorkspaceRefreshTool(root) { request ->
                    observed += request
                    when (request) {
                        WorkspaceLifecycleRequest.Inspect -> inspected(other, target)
                        is WorkspaceLifecycleRequest.Sync ->
                            IdeLifecycleResult.Pending(request.requestId, IdeLifecycleStage.ADMISSION, target.host)
                        is WorkspaceLifecycleRequest.Status -> IdeLifecycleResult.Synced(target)
                        else -> error("unexpected lifecycle request")
                    }
                }
                .tool

        val started = tool.invoke(refreshInput())
        assertInstanceOf(CliExit.Qualified::class.java, started)
        val pending = Json.parseToJsonElement(started.document.value).jsonObject
        assertTrue(McpStructuredResults.validates("refresh_workspace", pending))
        assertEquals("partial", pending.getValue("status").jsonPrimitive.content)
        val requestId = pending.getValue("data").jsonObject.getValue("requestId").jsonPrimitive.content
        val sync = observed[1] as WorkspaceLifecycleRequest.Sync
        assertEquals(target, sync.target)
        assertEquals(WorkspaceRefreshEffect.FILE_REFRESH, sync.effect)

        val finished = tool.invoke(refreshInput(requestId))
        assertInstanceOf(CliExit.Complete::class.java, finished)
        val completed = Json.parseToJsonElement(finished.document.value).jsonObject
        assertTrue(McpStructuredResults.validates("refresh_workspace", completed))
        assertEquals("complete", completed.getValue("status").jsonPrimitive.content)
        assertEquals(WorkspaceLifecycleRequest.Status(target.host, requestId), observed.last())
    }

    @Test
    fun `refresh preserves native unsaved document rejection`() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val target = IdeProjectTarget("host", "project", root.toRealPath().toString())
        val tool =
            McpWorkspaceRefreshTool(root) { request ->
                    when (request) {
                        WorkspaceLifecycleRequest.Inspect -> inspected(target)
                        is WorkspaceLifecycleRequest.Sync ->
                            IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSAVED_DOCUMENTS)
                        else -> error("unexpected lifecycle request")
                    }
                }
                .tool

        val result = tool.invoke(refreshInput())
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        val error = Json.parseToJsonElement(result.document.value).jsonObject.getValue("error").jsonObject
        assertEquals("LIFECYCLE_REJECTED", error.getValue("code").jsonPrimitive.content)
        assertEquals("UNSAVED_DOCUMENTS", error.getValue("cause").jsonPrimitive.content)
    }

    private fun inspected(vararg targets: IdeProjectTarget) =
        IdeLifecycleResult.Inspected(
            "host",
            "/idea",
            "IU-262.1",
            targets.map { IdeProjectDescription(it, IdeProjectOwnership.BORROWED, 1) },
        )

    private fun refreshInput(requestId: String? = null): JsonObject =
        Json.encodeToJsonElement(RefreshInput(requestId)).jsonObject

    @Serializable private data class RefreshInput(val requestId: String? = null)
}
