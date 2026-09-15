package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.provider.KastApprovalPolicy
import io.github.amichne.kast.appserver.provider.KastCapabilityBoundary
import io.github.amichne.kast.appserver.provider.KastCliInvocationBoundary
import io.github.amichne.kast.appserver.provider.KastCliInvocationsBoundary
import io.github.amichne.kast.appserver.provider.KastCliOperationInvocationBoundary
import io.github.amichne.kast.appserver.provider.KastExecutionBudgetBoundary
import io.github.amichne.kast.appserver.provider.KastHostedBootstrapBoundary
import io.github.amichne.kast.appserver.provider.KastHostedToolBoundary
import io.github.amichne.kast.appserver.provider.KastInvocationType
import io.github.amichne.kast.appserver.provider.KastServerProjectionBoundary
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private val catalogFixtureJson = Json { encodeDefaults = true }

/** Registration-only fixture. Semantic request/result contracts are tested at their own boundaries. */
internal fun installedKastCatalogFixture(): String =
    catalogFixtureJson.encodeToString(
        KastCapabilityBoundary(
            1,
            KastServerProjectionBoundary(
                12,
                "kast",
                KastHostedBootstrapBoundary(
                    1,
                    CanonicalAgentToolDefinitions.policy.text,
                    CanonicalAgentToolDefinitions.all.map { definition ->
                        KastHostedToolBoundary(
                            definition.operation.id.value,
                            definition.name.value,
                            definition.description.value,
                            definition.loading == HostedToolLoading.DEFERRED,
                            definition.operation.effect.name.lowercase(),
                            when (definition.approval) {
                                HostedApprovalPolicy.NONE -> KastApprovalPolicy.NONE
                                HostedApprovalPolicy.EXPLICIT -> KastApprovalPolicy.EXPLICIT
                            },
                            KastExecutionBudgetBoundary(
                                OperationExecutionBudget.WORKSPACE_READINESS.value,
                                OperationExecutionBudget.forOperation(definition.operation.operation).operation.value,
                            ),
                            when (val input = definition.inputBinding) {
                                is AgentToolInputBinding.Facade -> PublicToolContract.parameters(input.identity)
                                AgentToolInputBinding.Canonical ->
                                    catalogFixtureJson.encodeToJsonElement(FixtureInputSchema())
                            },
                            catalogFixtureJson.encodeToJsonElement(FixtureOutputSchema()),
                        )
                    },
                ),
                KastCliInvocationsBoundary(
                    3,
                    CanonicalAgentToolDefinitions.all.map { definition ->
                        val command =
                            when (val input = definition.inputBinding) {
                                is AgentToolInputBinding.Facade -> listOf("tool", input.identity.toolName)
                                AgentToolInputBinding.Canonical -> definition.operation.id.value.split('.')
                            }
                        KastCliOperationInvocationBoundary(
                            definition.name.value,
                            definition.operation.id.value,
                            command.joinToString(" ") + " < request.json",
                            KastCliInvocationBoundary(KastInvocationType.CLI, command),
                        )
                    },
                ),
            ),
        )
    )

@Serializable private class FixtureProperties

@Serializable
private data class FixtureInputSchema(
    val type: String = "object",
    val properties: FixtureProperties = FixtureProperties(),
    val additionalProperties: Boolean = false,
)

@Serializable private data class FixtureOutputSchema(val type: String = "object")
