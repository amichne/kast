package io.github.amichne.kast.cli.broker.protocol.copilot

import io.github.amichne.kast.cli.broker.core.AgentSessionBootstrap
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget

/** Deterministic provider fixture proving the hosted contract has no Codex dependency. */
internal data class CopilotFixtureProjection(
    val policy: String,
    val tools: List<CopilotFixtureTool>,
)

internal data class CopilotFixtureTool(
    val operationId: String,
    val name: String,
    val description: String,
    val inputSchema: String,
    val outputSchema: String,
    val effect: String,
    val approval: String,
    val readinessMillis: Long,
    val operationMillis: Long,
)

internal fun AgentSessionBootstrap.toCopilotFixtureProjection(): CopilotFixtureProjection =
    CopilotFixtureProjection(
        policy = policy.text,
        tools = tools.definitions.map { tool ->
            CopilotFixtureTool(
                operationId = tool.operation.id.value,
                name = tool.name.value,
                description = tool.description.value,
                inputSchema = canonicalJson(tool.inputSchema.document),
                outputSchema = canonicalJson(tool.outputSchema.document),
                effect = tool.effect.name.lowercase(),
                approval = tool.approval.name.lowercase(),
                readinessMillis = OperationExecutionBudget.WORKSPACE_READINESS.value,
                operationMillis = tool.executionBudget.operation.value,
            )
        },
    )
