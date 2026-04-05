package io.github.amichne.kast.cli

import io.github.amichne.kast.api.StandaloneServerOptions
import java.nio.file.Path

internal data class RuntimeCommandOptions(
    val workspaceRoot: Path,
    val backendName: String?,
    val waitTimeoutMillis: Long,
    val standaloneOptions: StandaloneServerOptions? = null,
)
