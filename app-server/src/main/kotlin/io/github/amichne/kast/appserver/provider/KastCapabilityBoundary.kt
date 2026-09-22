package io.github.amichne.kast.appserver.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class KastCapabilityBoundary(
    val schemaVersion: Int,
    val serverProjection: KastServerProjectionBoundary,
)

@Serializable
internal data class KastServerProjectionBoundary(
    val schemaVersion: Int,
    val namespace: String,
    val hostedBootstrap: KastHostedBootstrapBoundary,
)

@Serializable
internal data class KastHostedBootstrapBoundary(
    val schemaVersion: Int,
    val policy: String,
    val tools: List<KastHostedToolBoundary>,
)

@Serializable
internal data class KastHostedToolBoundary(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val effect: String,
    val approvalPolicy: KastApprovalPolicy,
    val executionBudget: KastExecutionBudgetBoundary,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
)

@Serializable
internal data class KastExecutionBudgetBoundary(
    val readinessMillis: Long,
    val operationMillis: Long,
)

@Serializable
internal enum class KastApprovalPolicy {
    @SerialName("none") NONE,
    @SerialName("explicit") EXPLICIT,
    @SerialName("exact_project_close") EXACT_PROJECT_CLOSE,
}
