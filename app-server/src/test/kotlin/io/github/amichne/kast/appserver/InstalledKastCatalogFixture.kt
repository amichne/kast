package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.provider.KastApprovalPolicy
import io.github.amichne.kast.appserver.provider.KastCapabilityBoundary
import io.github.amichne.kast.appserver.provider.KastExecutionBudgetBoundary
import io.github.amichne.kast.appserver.provider.KastHostedBootstrapBoundary
import io.github.amichne.kast.appserver.provider.KastHostedToolBoundary
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
                18,
                "kast",
                KastHostedBootstrapBoundary(
                    io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION,
                    CanonicalAgentToolDefinitions.policy.text,
                    CanonicalAgentToolDefinitions.all.map { definition ->
                        KastHostedToolBoundary(
                            operationId = definition.operation.id.value,
                            name = definition.name.value,
                            description = definition.description.value,
                            deferLoading = definition.loading == HostedToolLoading.DEFERRED,
                            effect = definition.operation.effect.name.lowercase(),
                            approvalPolicy =
                                when (definition.approval) {
                                    HostedApprovalPolicy.NONE -> KastApprovalPolicy.NONE
                                    HostedApprovalPolicy.EXPLICIT -> KastApprovalPolicy.EXPLICIT
                                    HostedApprovalPolicy.EXACT_PROJECT_CLOSE -> KastApprovalPolicy.EXACT_PROJECT_CLOSE
                                },
                            executionBudget =
                                KastExecutionBudgetBoundary(
                                    OperationExecutionBudget.WORKSPACE_READINESS.value,
                                    OperationExecutionBudget.forOperation(definition.operation.operation)
                                        .operation
                                        .value,
                                ),
                            inputSchema =
                                when (val input = definition.inputBinding) {
                                    is AgentToolInputBinding.Facade -> PublicToolContract.parameters(input.identity)
                                    AgentToolInputBinding.Canonical ->
                                        catalogFixtureJson.encodeToJsonElement(FixtureInputSchema())
                                },
                            outputSchema = catalogFixtureJson.encodeToJsonElement(FixtureOutputSchema()),
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
