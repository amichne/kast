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
    CODEX_APP_SERVER_RETIRED("Codex App Server registration is retired; use kast install codex")
}

internal enum class RegistrationOwnership {
    REQUIRE_OWNED,
    RECOVER_SELECTED_SLOT,
    REPLACE_SELECTED_SLOT,
}

internal sealed interface ManagementCommand {
    /** Opaque argv. Only the JVM public command owner interprets semantic options. */
    data class PublicQuery(val arguments: List<String>) : ManagementCommand

    data class Status(val json: Boolean) : ManagementCommand

    data object Version : ManagementCommand

    data class Disconnect(val harness: Harness) : ManagementCommand

    data class Install(val target: ProvisionTarget, val json: Boolean) : ManagementCommand

    data class Versions(val json: Boolean) : ManagementCommand

    data class Lifecycle(val operation: LifecycleOperation, val json: Boolean) : ManagementCommand

    data class Reset(val operation: ForceResetOperation, val json: Boolean) : ManagementCommand

    data class Upgrade(
        val request: BuildRequest = BuildRequest.RecordedPolicy,
        val ideaHome: String? = null,
        val dryRun: Boolean = false,
        val restartIde: Boolean = false,
        val json: Boolean = false,
    ) : ManagementCommand

    data object Uninstall : ManagementCommand

    data object Help : ManagementCommand
}

@Serializable
internal enum class HarnessComposition {
    CliOnly,
    CliWithMcp,
}

internal sealed interface ProvisionTarget {
    data class Skill(val output: String?) : ProvisionTarget

    data class Provider(val harness: Harness, val composition: HarnessComposition) : ProvisionTarget
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

internal sealed interface ProvisionTargetAdmission {
    data class Admitted(val target: ProvisionTarget) : ProvisionTargetAdmission

    data class Rejected(val reason: String) : ProvisionTargetAdmission
}

internal fun admitProvisionTarget(raw: String, output: String?, mcp: Boolean): ProvisionTargetAdmission =
    when {
        raw == "skill" && mcp -> ProvisionTargetAdmission.Rejected("--mcp needs a harness")
        raw == "skill" -> ProvisionTargetAdmission.Admitted(ProvisionTarget.Skill(output))
        output != null -> ProvisionTargetAdmission.Rejected("--output needs install skill")
        else ->
            when (val harness = Harness.parse(raw)) {
                null -> ProvisionTargetAdmission.Rejected("Select codex, copilot, pi, or skill")
                else ->
                    ProvisionTargetAdmission.Admitted(
                        ProvisionTarget.Provider(
                            harness,
                            if (mcp) HarnessComposition.CliWithMcp else HarnessComposition.CliOnly,
                        )
                    )
            }
    }
