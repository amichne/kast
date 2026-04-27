package io.github.amichne.kast.cli.runtime

/**
 * Lifecycle-specific request parameters for ensure/status/stop operations.
 * Combines runtime selection with lifecycle behavior flags.
 */
internal data class RuntimeLifecycleRequest(
    val selection: RuntimeSelection,
    val acceptIndexing: Boolean = false,
)
