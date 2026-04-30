package io.github.amichne.kast.cli.options

import io.github.amichne.kast.api.client.StandaloneServerOptions
import java.nio.file.Path

internal data class RuntimeCommandOptions(
    val workspaceRoot: Path,
    val backendName: String?,
    val waitTimeoutMillis: Long,
    val standaloneOptions: StandaloneServerOptions? = null,
    val acceptIndexing: Boolean = false,
    val noAutoStart: Boolean = false,
)
