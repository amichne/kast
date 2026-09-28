package io.github.amichne.kast.distribution.cli

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
internal enum class Harness(val publicName: String) {
    CODEX("codex"),
    COPILOT("copilot"),
    PI("pi");

    companion object {
        fun parse(raw: String): Harness? = entries.singleOrNull { it.publicName == raw.lowercase() }
    }
}

internal enum class RegistrationOwnership { REQUIRE_OWNED, REPLACE_SELECTED_SLOT }

internal sealed interface ManagementCommand {
    data class Status(val json: Boolean) : ManagementCommand

    data object Version : ManagementCommand

    data class Connect(val harness: Harness?, val ownership: RegistrationOwnership = RegistrationOwnership.REQUIRE_OWNED) : ManagementCommand

    data class Disconnect(val harness: Harness) : ManagementCommand

    data object Upgrade : ManagementCommand

    data object Uninstall : ManagementCommand

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
