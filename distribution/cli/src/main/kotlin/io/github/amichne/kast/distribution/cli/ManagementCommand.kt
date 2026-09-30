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

@Serializable
internal enum class HarnessConnection(val harness: Harness, val publicName: String) {
    CODEX_MCP(Harness.CODEX, "codex mcp"),
    CODEX_APP_SERVER(Harness.CODEX, "codex app-server"),
    COPILOT(Harness.COPILOT, "copilot"),
    PI(Harness.PI, "pi"),
}

internal enum class ConnectionFailure(val explanation: String) {
    CODEX_TRANSPORT_REQUIRED("Codex requires an explicit transport: mcp or app-server"),
    CODEX_TRANSPORT_UNSUPPORTED("Supported Codex transports: mcp, app-server"),
    OTHER_HARNESS_TRANSPORT("Only Codex accepts a transport: mcp or app-server"),
}

internal sealed interface ConnectionAdmission {
    data object ListAvailable : ConnectionAdmission

    data class Selected(val connection: HarnessConnection) : ConnectionAdmission

    data class Rejected(val failure: ConnectionFailure) : ConnectionAdmission
}

internal fun admitConnection(harness: Harness?, transport: String?): ConnectionAdmission {
    if (harness != Harness.CODEX && transport != null)
        return ConnectionAdmission.Rejected(ConnectionFailure.OTHER_HARNESS_TRANSPORT)
    return when (harness) {
        Harness.CODEX ->
            when (transport) {
                "mcp" -> ConnectionAdmission.Selected(HarnessConnection.CODEX_MCP)
                "app-server" -> ConnectionAdmission.Selected(HarnessConnection.CODEX_APP_SERVER)
                null -> ConnectionAdmission.Rejected(ConnectionFailure.CODEX_TRANSPORT_REQUIRED)
                else -> ConnectionAdmission.Rejected(ConnectionFailure.CODEX_TRANSPORT_UNSUPPORTED)
            }
        Harness.COPILOT -> ConnectionAdmission.Selected(HarnessConnection.COPILOT)
        Harness.PI -> ConnectionAdmission.Selected(HarnessConnection.PI)
        null -> ConnectionAdmission.ListAvailable
    }
}

internal enum class RegistrationOwnership {
    REQUIRE_OWNED,
    REPLACE_SELECTED_SLOT,
}

internal sealed interface ManagementCommand {
    data class Status(val json: Boolean) : ManagementCommand

    data object Version : ManagementCommand

    data class Connect(
        val connection: HarnessConnection?,
        val ownership: RegistrationOwnership = RegistrationOwnership.REQUIRE_OWNED,
    ) : ManagementCommand

    data class Disconnect(val harness: Harness) : ManagementCommand

    data object ListPluginHarnesses : ManagementCommand

    data class Plugin(val harness: PluginHarness, val json: Boolean) : ManagementCommand

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
