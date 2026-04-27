package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.StandaloneServerOptions
import io.github.amichne.kast.cli.runtime.RuntimeLifecycleRequest
import io.github.amichne.kast.cli.runtime.RuntimeSelection
import java.nio.file.Path

/**
 * Bridge type for CLI command parsing to runtime lifecycle operations.
 * Converts parsed command options into the appropriate runtime request types.
 */
internal data class RuntimeCommandOptions(
    val workspaceRoot: Path,
    val backendName: String?,
    val waitTimeoutMillis: Long,
    val standaloneOptions: StandaloneServerOptions? = null,
    val acceptIndexing: Boolean = false,
    val noAutoStart: Boolean = false,
) {
    fun toRuntimeSelection(): RuntimeSelection = RuntimeSelection(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        waitTimeoutMillis = waitTimeoutMillis,
    )

    fun toLifecycleRequest(): RuntimeLifecycleRequest = RuntimeLifecycleRequest(
        selection = toRuntimeSelection(),
        acceptIndexing = acceptIndexing,
        noAutoStart = noAutoStart,
        standaloneOptions = standaloneOptions,
    )
}
