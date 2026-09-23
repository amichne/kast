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
                14,
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
                                HostedApprovalPolicy.EXACT_PROJECT_CLOSE -> KastApprovalPolicy.EXACT_PROJECT_CLOSE
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
