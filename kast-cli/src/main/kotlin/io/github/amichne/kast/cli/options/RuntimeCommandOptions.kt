package io.github.amichne.kast.cli.options

import io.github.amichne.kast.api.client.StandaloneServerOptions
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveLong

internal enum class BackendName {
    STANDALONE,
    INTELLIJ,
    ;

    val cliName: String = name.lowercase()
}

internal data class RuntimeCommandOptions(
    val workspaceRoot: NormalizedPath,
    val backendName: BackendName?,
    val waitTimeoutMillis: PositiveLong,
    val standaloneOptions: StandaloneServerOptions? = null,
    val acceptIndexing: Boolean = false,
    val noAutoStart: Boolean = false,
)
