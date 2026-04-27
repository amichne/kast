package io.github.amichne.kast.cli.runtime

import io.github.amichne.kast.api.client.StandaloneServerOptions

/**
 * Lifecycle-specific request parameters for ensure/status/stop operations.
 * Combines runtime selection with lifecycle behavior flags.
 */
internal data class RuntimeLifecycleRequest(
    val selection: RuntimeSelection,
    val acceptIndexing: Boolean = false,
    val noAutoStart: Boolean = false,
    val standaloneOptions: StandaloneServerOptions? = null,
)
