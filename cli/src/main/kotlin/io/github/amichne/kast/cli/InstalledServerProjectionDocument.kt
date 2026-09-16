package io.github.amichne.kast.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One closed server-facing projection owned by this installed command graph. */
@Serializable
internal data class InstalledServerProjectionDocument(
    val schemaVersion: Int,
    val namespace: String,
    val hostedBootstrap: InstalledHostedBootstrapDocument,
    val cliInvocations: InstalledCliInvocationsDocument,
)

@Serializable
internal data class InstalledHostedBootstrapDocument(
    val schemaVersion: Int,
    val policy: String,
    val tools: List<InstalledHostedToolDocument>,
)

@Serializable
internal data class InstalledHostedToolDocument(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val effect: String,
    val approvalPolicy: String,
    val executionBudget: InstalledServerExecutionBudgetDocument,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
)

@Serializable
internal data class InstalledServerExecutionBudgetDocument(
    val readinessMillis: Long,
    val operationMillis: Long,
)

@Serializable
internal data class InstalledCliInvocationsDocument(
    val schemaVersion: Int,
    val operations: List<InstalledCliOperationInvocationDocument>,
)

@Serializable
internal data class InstalledCliOperationInvocationDocument(
    val toolName: String,
    val operationId: String,
    val cliUsage: String,
    val invocation: InstalledServerCliInvocationDocument,
)

@Serializable
internal data class InstalledServerCliInvocationDocument(
    val type: InstalledServerInvocationType,
    val command: List<String>,
)

@Serializable
internal enum class InstalledServerInvocationType {
    CLI
}
