package io.github.amichne.kast.cli.installation

import kotlinx.serialization.Serializable

@Serializable
internal enum class InstallationChildOutcome {
    COMPLETED,
    EXIT_REJECTED,
    DEADLINE_EXCEEDED,
    IO_REJECTED,
    INTERRUPTED,
}
