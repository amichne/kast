package io.github.amichne.kast.cli

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import kotlinx.serialization.json.Json

internal class CliService(
    json: Json,
    processLauncher: ProcessLauncher,
) {
    private val httpClient = KastHttpClient(json)
    private val runtimeManager = WorkspaceRuntimeManager(httpClient, processLauncher)

    suspend fun workspaceStatus(options: RuntimeCommandOptions): WorkspaceStatusResult =
        runtimeManager.workspaceStatus(options)

    suspend fun workspaceEnsure(options: RuntimeCommandOptions): WorkspaceEnsureResult =
        runtimeManager.workspaceEnsure(options)

    suspend fun daemonStart(options: RuntimeCommandOptions): WorkspaceEnsureResult =
        runtimeManager.daemonStart(options)

    suspend fun daemonStop(options: RuntimeCommandOptions): DaemonStopResult =
        runtimeManager.daemonStop(options)

    suspend fun capabilities(options: RuntimeCommandOptions): BackendCapabilities {
        val runtime = runtimeManager.ensureRuntime(options)
        return checkNotNull(runtime.selected.capabilities) {
            "Runtime capabilities were not loaded after ensure for ${runtime.selected.descriptor.backendName}"
        }
    }

    suspend fun resolveSymbol(
        options: RuntimeCommandOptions,
        query: SymbolQuery,
    ): SymbolResult {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.RESOLVE_SYMBOL)
        return httpClient.post(runtime.selected.descriptor, "/api/v1/symbol/resolve", query)
    }

    suspend fun findReferences(
        options: RuntimeCommandOptions,
        query: ReferencesQuery,
    ): ReferencesResult {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.FIND_REFERENCES)
        return httpClient.post(runtime.selected.descriptor, "/api/v1/references", query)
    }

    suspend fun diagnostics(
        options: RuntimeCommandOptions,
        query: DiagnosticsQuery,
    ): DiagnosticsResult {
        val runtime = runtimeManager.ensureRuntime(options)
        requireReadCapability(runtime.selected, ReadCapability.DIAGNOSTICS)
        return httpClient.post(runtime.selected.descriptor, "/api/v1/diagnostics", query)
    }

    suspend fun rename(
        options: RuntimeCommandOptions,
        query: RenameQuery,
    ): RenameResult {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.RENAME)
        return httpClient.post(runtime.selected.descriptor, "/api/v1/rename", query)
    }

    suspend fun applyEdits(
        options: RuntimeCommandOptions,
        query: ApplyEditsQuery,
    ): ApplyEditsResult {
        val runtime = runtimeManager.ensureRuntime(options)
        requireMutationCapability(runtime.selected, MutationCapability.APPLY_EDITS)
        return httpClient.post(runtime.selected.descriptor, "/api/v1/edits/apply", query)
    }

    private fun requireReadCapability(
        candidate: RuntimeCandidateStatus,
        capability: ReadCapability,
    ) {
        val capabilities = candidate.capabilities
            ?: throw CliFailure(
                code = "CAPABILITIES_UNAVAILABLE",
                message = "Capabilities are unavailable for ${candidate.descriptor.backendName}",
            )
        if (!capabilities.readCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "${candidate.descriptor.backendName} does not advertise $capability",
            )
        }
    }

    private fun requireMutationCapability(
        candidate: RuntimeCandidateStatus,
        capability: MutationCapability,
    ) {
        val capabilities = candidate.capabilities
            ?: throw CliFailure(
                code = "CAPABILITIES_UNAVAILABLE",
                message = "Capabilities are unavailable for ${candidate.descriptor.backendName}",
            )
        if (!capabilities.mutationCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "${candidate.descriptor.backendName} does not advertise $capability",
            )
        }
    }
}
