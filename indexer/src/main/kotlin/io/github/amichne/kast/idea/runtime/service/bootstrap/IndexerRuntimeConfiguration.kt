package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.RuntimeCapabilityLeaseRegistry
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.server.AnalysisServerConfig

internal sealed interface IndexerAdmission {
    data object Pending : IndexerAdmission

    data object Ready : IndexerAdmission

    data class Failed(val error: Throwable) : IndexerAdmission

    companion object {
        fun fromStartIndexing(startIndexing: Boolean): IndexerAdmission =
            if (startIndexing) Ready else Pending
    }
}

internal fun indexerServerLimits(config: KastConfig): ServerLimits = ServerLimits(
    maxConcurrentRequests = config.server.maxConcurrentRequests.value.coerceAtLeast(1),
    requestTimeoutMillis = config.server.requestTimeoutMillis.value,
    maxResults = config.server.maxResults.value,
)

internal fun indexerAnalysisServerConfig(
    transport: AnalysisTransport,
    runtimeInstanceId: RuntimeInstanceId?,
    limits: ServerLimits,
    config: KastConfig,
    workspaceFileCountProvider: () -> Int,
    runtimeCapabilityLeases: RuntimeCapabilityLeaseRegistry,
): AnalysisServerConfig = AnalysisServerConfig(
    transport = transport,
    runtimeInstanceId = runtimeInstanceId,
    runtimeCapabilityLeases = runtimeCapabilityLeases,
    requestTimeoutMillis = limits.requestTimeoutMillis,
    maxResults = limits.maxResults,
    maxConcurrentRequests = limits.maxConcurrentRequests,
    continuationTtlMillis = limits.continuationTtlMillis,
    continuationCapacity = limits.continuationCapacity,
    descriptorDirectory = config.paths.descriptorDir.toPath(),
    workspaceFileCount = workspaceFileCountProvider(),
    workspaceFileCountProvider = workspaceFileCountProvider,
)
