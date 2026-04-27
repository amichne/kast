package io.github.amichne.kast.cli.runtime

import java.nio.file.Path

/**
 * Shared runtime selection parameters used across lifecycle operations.
 * Identifies which runtime to target and basic connection settings.
 */
internal data class RuntimeSelection(
    val workspaceRoot: Path,
    val backendName: String?,
    val waitTimeoutMillis: Long,
)
