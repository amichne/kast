package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

internal class ConnectCommand : ManagementNode("connect") {
    private val force by option("--force", help = "Replace only this harness's Kast registration slot.").flag()
    private val strict by
        option("--no-recover", help = "Reject a conflicting Kast slot instead of recovering it.").flag()
    private val directory by
        option(
                "--destination",
                help = "Harness home directory; for Codex app-server, the directory containing kast-codex.",
            )
            .convert { raw ->
                when (val admitted = ConnectionDirectory.admit(raw)) {
                    is ConnectionDirectoryAdmission.Admitted -> admitted.directory
                    is ConnectionDirectoryAdmission.Rejected -> fail(admitted.failure.explanation)
                }
            }
    private val harness by
        argument("harness")
            .convert { raw ->
                Harness.parse(raw) ?: fail("Supported harnesses: codex, copilot, pi")
            }
            .optional()
    private val transport by argument("transport").optional()

    override fun help(context: Context) =
        "Register the release-bundled integration at user scope. Codex requires mcp or app-server."

    override fun selection(): ManagementCommand {
        val requiresHarness = force || strict || directory != null
        if (requiresHarness && harness == null)
            throw CliktError("--force, --destination and --no-recover require a selected harness")
        if (force && strict) throw CliktError("--force and --no-recover cannot be combined")
        val connection = selectedConnection()
        return ManagementCommand.Connect(
            connection,
            when {
                force -> RegistrationOwnership.REPLACE_SELECTED_SLOT
                strict -> RegistrationOwnership.REQUIRE_OWNED
                else -> RegistrationOwnership.RECOVER_SELECTED_SLOT
            },
            directory,
        )
    }

    private fun selectedConnection(): HarnessConnection? =
        when (val admission = admitConnection(harness, transport)) {
            is ConnectionAdmission.Selected -> admission.connection
            is ConnectionAdmission.Rejected -> throw CliktError(admission.failure.explanation)
            ConnectionAdmission.ListAvailable -> null
        }
}

internal class DisconnectCommand : ManagementNode("disconnect") {
    private val harness by
        argument("harness").convert { raw ->
            Harness.parse(raw) ?: fail("Supported harnesses: codex, copilot, pi")
        }

    override fun help(context: Context) = "Remove a registration owned by this installation."

    override fun selection() = ManagementCommand.Disconnect(harness)
}
