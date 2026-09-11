package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registration-only fixture. Semantic request/result contracts are tested at their own boundaries. */
internal fun installedKastCatalogFixture(): String = buildJsonObject {
    put("schemaVersion", 1)
    put("serverProjection", buildJsonObject {
        put("schemaVersion", 10)
        put("namespace", "kast")
        put("hostedBootstrap", buildJsonObject {
            put("schemaVersion", 1)
            put("policy", CanonicalAgentToolDefinitions.policy.text)
            put("tools", buildJsonArray {
                CanonicalAgentToolDefinitions.all.forEach { definition ->
                    add(buildJsonObject {
                        put("operationId", definition.operation.id.value)
                        put("name", definition.name.value)
                        put("description", definition.description.value)
                        put("deferLoading", definition.loading == HostedToolLoading.DEFERRED)
                        put("effect", definition.operation.effect.name.lowercase())
                        put("approvalPolicy", definition.approval.name.lowercase())
                        put("executionBudget", buildJsonObject {
                            put("readinessMillis", OperationExecutionBudget.WORKSPACE_READINESS.value)
                            put("operationMillis", OperationExecutionBudget.forOperation(definition.operation.operation).operation.value)
                        })
                        put("inputSchema", when (val input = definition.inputBinding) {
                            is AgentToolInputBinding.Facade -> PublicToolContract.parameters(input.identity)
                            AgentToolInputBinding.Canonical -> buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                            put("additionalProperties", false)
                        } })
                        put("outputSchema", buildJsonObject { put("type", "object") })
                    })
                }
            })
        })
        put("cliInvocations", buildJsonObject {
            put("schemaVersion", 3)
            put("operations", buildJsonArray {
                CanonicalAgentToolDefinitions.all.forEach { definition ->
                    val command = when (val input = definition.inputBinding) {
                        is AgentToolInputBinding.Facade -> listOf("tool", input.identity.toolName)
                        AgentToolInputBinding.Canonical -> definition.operation.id.value.split('.')
                    }
                    add(buildJsonObject {
                        put("operationId", definition.operation.id.value)
                        put("toolName", definition.name.value)
                        put("cliUsage", command.joinToString(" ") + " < request.json")
                        put("invocation", buildJsonObject {
                            put("type", "CLI")
                            put("command", buildJsonArray { command.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        })
                    })
                }
            })
        })
    })
}.toString()
