package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal enum class McpPreparationFailure(val provisionFailure: ProvisionFailure) {
    MCP_INSTALLATION_REJECTED(ProvisionFailure.MCP_INSTALLATION_REJECTED),
    MCP_STATE_REJECTED(ProvisionFailure.MCP_STATE_REJECTED),
    MCP_ENVIRONMENT_MISSING(ProvisionFailure.MCP_ENVIRONMENT_MISSING),
    MCP_PREPARATION_INCOMPLETE(ProvisionFailure.MCP_PREPARATION_INCOMPLETE),
    MCP_WHEEL_INSTALL_FAILED(ProvisionFailure.MCP_WHEEL_INSTALL_FAILED),
    MCP_IMPORT_CHECK_FAILED(ProvisionFailure.MCP_IMPORT_CHECK_FAILED),
    MCP_PROCESS_TIMEOUT(ProvisionFailure.MCP_PROCESS_TIMEOUT),
    MCP_FILESYSTEM_REJECTED(ProvisionFailure.MCP_FILESYSTEM_REJECTED),
    MCP_PROCESS_UNAVAILABLE(ProvisionFailure.MCP_PROCESS_UNAVAILABLE),
    MCP_CONCURRENT_CHANGE(ProvisionFailure.MCP_CONCURRENT_CHANGE),
}

@Serializable
internal enum class McpPreparationDisposition {
    CREATED,
    REUSED,
}

@Serializable
internal sealed interface McpPreparationResult {
    @Serializable
    @SerialName("MCP_PREPARED")
    data class Prepared(
        val installationRoot: String,
        val environmentRoot: String,
        val disposition: McpPreparationDisposition,
    ) : McpPreparationResult

    @Serializable
    @SerialName("MCP_REJECTED")
    data class Rejected(val failure: McpPreparationFailure) : McpPreparationResult
}

internal val mcpPreparationJson = Json { classDiscriminator = "status" }

internal fun observeMcpPreparation(installation: Path, observation: ProcessObservation): PluginObservation<Unit> =
    when (observation) {
        ProcessObservation.Unavailable -> PluginObservation.Rejected(PluginInstallFailure.ProcessUnavailable)
        is ProcessObservation.Exited ->
            try {
                admitMcpPreparation(
                    mcpPreparationJson.decodeFromString<McpPreparationResult>(observation.output),
                    installation,
                    observation.code,
                )
            } catch (_: SerializationException) {
                PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
            }
    }

private fun admitMcpPreparation(
    reply: McpPreparationResult,
    installation: Path,
    exitCode: Int,
): PluginObservation<Unit> =
    when (reply) {
        is McpPreparationResult.Rejected ->
            if (exitCode == 1) PluginObservation.Rejected(PluginInstallFailure.McpPreparationRejected(reply.failure))
            else PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
        is McpPreparationResult.Prepared ->
            if (
                exitCode == 0 &&
                    reply.installationRoot == installation.toString() &&
                    reply.environmentRoot == installation.resolve("state/mcp").toString()
            )
                PluginObservation.Observed(Unit)
            else PluginObservation.Rejected(PluginInstallFailure.ObservationRejected)
    }
