package io.github.amichne.kast.distribution.cli

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
/** A saved Codex connection directory, read only so uninstall can retire Kast's former home configuration. */
internal enum class HarnessConnection {
    CODEX_MCP,
    CODEX_APP_SERVER,
}

internal sealed interface ManagementCommand {
    /** Opaque argv. Only the JVM public command owner interprets semantic options. */
    data class PublicQuery(val arguments: List<String>) : ManagementCommand

    data class Status(val json: Boolean, val scope: WorkspaceScope = WorkspaceScope.Current) : ManagementCommand

    data class Config(val workspace: Path, val action: WorkspaceConfigAction, val json: Boolean) : ManagementCommand

    data class Stop(val scope: WorkspaceScope, val json: Boolean) : ManagementCommand

    data object Version : ManagementCommand

    /** Exports the canonical Kast agent skill; agent configuration is delivered through dotagents. */
    data class InstallSkill(val output: String?, val json: Boolean) : ManagementCommand

    data class Versions(val json: Boolean) : ManagementCommand

    data class Upgrade(
        val request: BuildRequest = BuildRequest.RecordedPolicy,
        val dryRun: Boolean = false,
        val json: Boolean = false,
    ) : ManagementCommand

    data object Uninstall : ManagementCommand

    data class ForceUninstall(val json: Boolean) : ManagementCommand

    data object Help : ManagementCommand
}

internal enum class DestinationFailure {
    HOME_INVALID,
    XDG_RELATIVE,
    DESTINATION_UNWRITABLE,
    FOREIGN_ENTRY,
}

internal sealed interface DestinationSelection {
    data class Selected(val executable: Path, val onPath: Boolean) : DestinationSelection

    data class Rejected(val failure: DestinationFailure) : DestinationSelection
}

/** Pure policy; the installer separately checks actual directory ownership and write access. */
@Suppress("CognitiveComplexMethod")
internal fun selectDestination(home: String, xdgConfigHome: String?, path: String?): DestinationSelection {
    val homePath =
        try {
            Path.of(home)
        } catch (_: InvalidPathException) {
            return DestinationSelection.Rejected(DestinationFailure.HOME_INVALID)
        }
    if (!homePath.isAbsolute || homePath.normalize() != homePath)
        return DestinationSelection.Rejected(DestinationFailure.HOME_INVALID)
    val directory =
        if (xdgConfigHome.isNullOrEmpty()) homePath.resolve(".local/bin")
        else {
            val candidate =
                try {
                    Path.of(xdgConfigHome)
                } catch (_: InvalidPathException) {
                    return DestinationSelection.Rejected(DestinationFailure.XDG_RELATIVE)
                }
            if (!candidate.isAbsolute || candidate.normalize() != candidate)
                return DestinationSelection.Rejected(DestinationFailure.XDG_RELATIVE)
            candidate
        }
    val onPath =
        path?.split(':')?.any { raw ->
            raw.isNotEmpty() &&
                try {
                    Path.of(raw).toAbsolutePath().normalize() == directory
                } catch (_: InvalidPathException) {
                    false
                }
        } ?: false
    return DestinationSelection.Selected(directory.resolve("kast"), onPath)
}
