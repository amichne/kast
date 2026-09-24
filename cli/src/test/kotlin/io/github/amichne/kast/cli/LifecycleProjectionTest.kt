package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleProjectionTest {
    private val json = Json { encodeDefaults = true }
    private val target = IdeProjectTarget("host", "project", "/worktree")

    @Test
    fun `canonical schemas admit every actual result and retain finite blockers`() {
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val tool =
            installedServerProjection(graph.surface).hostedBootstrap.tools.single { it.name == "workspace_lifecycle" }
        assertEquals("exact_project_close", tool.approvalPolicy)
        assertNotEquals("intellij_read", tool.effect)
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(tool.outputSchema.toString())
        val results =
            listOf(
                IdeLifecycleResult.Inspected("host", "/idea", "IU-262.99.1", emptyList()),
                IdeLifecycleResult.Pending("request", IdeLifecycleStage.ADMISSION, "host"),
                IdeLifecycleResult.Opened(target),
                IdeLifecycleResult.Presented(target),
                IdeLifecycleResult.Synced(target),
                IdeLifecycleResult.Configured(target, WorkspaceRefreshRule.Off),
                IdeLifecycleResult.Released(target),
                IdeLifecycleResult.Closed(target),
            )
        for (result in results) assertTrue(
            validator.validate(json.encodeToString(Completed(result)), InputFormat.JSON).isEmpty()
        )
        for (failure in IdeLifecycleFailure.entries) {
            val result = IdeLifecycleResult.Blocked(failure)
            val encoded = json.encodeToString(Rejected(result))
            assertTrue(validator.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
            val diagnostic = Json.parseToJsonElement(encoded).jsonObject["diagnostic"]!!.jsonObject
            assertEquals(setOf("type", "reason"), diagnostic.keys)
            assertEquals(failure.name, diagnostic["reason"]!!.jsonPrimitive.content)
        }
        val inspection = json.encodeToJsonElement<IdeLifecycleResult>(results.first()).jsonObject
        assertEquals(
            setOf("type", "host", "home", "build", "projects", "protocol", "background", "capabilities"),
            inspection.keys,
        )
        assertEquals("inspected", inspection["type"]!!.jsonPrimitive.content)
        assertEquals(1, inspection["protocol"]!!.jsonPrimitive.int)
        assertEquals("best_effort", inspection["background"]!!.jsonPrimitive.content)

        val configured = json.encodeToJsonElement<IdeLifecycleResult>(results[5]).jsonObject
        assertEquals(setOf("type", "target", "rule"), configured.keys)
        assertEquals("configured", configured.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `hosted lifecycle request requires exact target and rejects manual sync variants`() {
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val tool =
            installedServerProjection(graph.surface).hostedBootstrap.tools.single { it.name == "workspace_lifecycle" }
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(tool.inputSchema.toString())
        val request = WorkspaceLifecycleRequest.Present(target, "present-1")
        assertTrue(
            validator.validate(json.encodeToString<WorkspaceLifecycleRequest>(request), InputFormat.JSON).isEmpty()
        )
        val missingTarget =
            json.encodeToString<WorkspaceLifecycleRequest>(request).replace(json.encodeToString(target), "null")
        assertTrue(validator.validate(missingTarget, InputFormat.JSON).isNotEmpty())
        for (action in listOf("sync", "configure_sync")) {
            val manual = json.encodeToString<WorkspaceLifecycleRequest>(request).replace("present", action)
            assertTrue(validator.validate(manual, InputFormat.JSON).isNotEmpty(), action)
        }
    }

    @Serializable private data class Completed(val document: IdeLifecycleResult, val status: String = "completed")

    @Serializable private data class Rejected(val diagnostic: IdeLifecycleResult, val status: String = "rejected")
}
