package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.AgentSessionBootstrap
import io.github.amichne.kast.cli.broker.core.AgentSessionBootstrapQualification
import io.github.amichne.kast.cli.broker.core.HostedToolDefinition
import io.github.amichne.kast.cli.broker.core.HostedToolCatalogFailure
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.protocol.copilot.toCopilotFixtureProjection
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSessionProjectionTest {
    @Test
    fun `one exact bootstrap deterministically projects to Codex and Copilot`() {
        val bootstrap = agentSessionBootstrapFixture()

        val codex = bootstrap.toCodexSessionProjection()
        val copilot = bootstrap.toCopilotFixtureProjection()

        assertEquals(bootstrap.policy.text, codex.developerInstructions)
        assertEquals(bootstrap.policy.text, copilot.policy)
        val codexTool = codex.namespace.getValue("tools").jsonArray.single().jsonObject
        val copilotTool = copilot.tools.single()
        assertEquals(copilotTool.name, codexTool.getValue("name").jsonPrimitive.content)
        assertEquals(copilotTool.description, codexTool.getValue("description").jsonPrimitive.content)
        assertEquals("symbol.discover", copilotTool.operationId)
        assertEquals("intellij_read", copilotTool.effect)
    }

    @Test
    fun `bootstrap rejects an advertised tool without an executable route`() {
        val qualified = agentSessionBootstrapFixture()

        assertEquals(
            AgentSessionBootstrapQualification.Rejected(
                HostedToolCatalogFailure.ADVERTISED_ROUTE_MISSING,
            ),
            AgentSessionBootstrap.qualify(
                definitions = qualified.tools.definitions,
                policy = qualified.policy,
                executableRoutes = emptySet(),
            ),
        )
    }
}

internal fun agentSessionBootstrapFixture(): AgentSessionBootstrap {
    val metadata = CanonicalAgentToolDefinitions.symbolLookup
    val schema = when (
        val compilation = NetworkntJsonSchemaCompiler.compile(
            Json.parseToJsonElement(
                """{"type":"object","additionalProperties":false,"properties":{}}""",
            ).jsonObject,
        )
    ) {
        is Refinement.Refined -> compilation.value
        is Refinement.Rejected -> error(compilation.failure)
    }
    val route = when (val admission = ToolName.admit(metadata.name.value)) {
        is Refinement.Refined -> admission.value
        is Refinement.Rejected -> error(admission.failure)
    }
    return when (
        val qualification = AgentSessionBootstrap.qualify(
            definitions = listOf(
                HostedToolDefinition(
                    operation = metadata.operation.operation,
                    name = metadata.name,
                    description = metadata.description,
                    inputSchema = schema,
                    outputSchema = schema,
                    effect = metadata.operation.effect,
                    approval = metadata.approval,
                    executionBudget = metadata.operation.executionBudget,
                    loading = metadata.loading,
                ),
            ),
            policy = CanonicalAgentToolDefinitions.policy,
            executableRoutes = setOf(route),
        )
    ) {
        is AgentSessionBootstrapQualification.Qualified -> qualification.bootstrap
        is AgentSessionBootstrapQualification.Rejected -> error(qualification.failure)
    }
}
