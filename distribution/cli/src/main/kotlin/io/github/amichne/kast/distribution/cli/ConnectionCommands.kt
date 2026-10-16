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
                help = "Home directory for the Pi or Copilot integration.",
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
        "Install the Codex plugin or register the release-bundled Pi or Copilot integration."

    override fun selection(): ManagementCommand {
        val requiresHarness = force || strict || directory != null
        if (requiresHarness && harness == null)
            throw CliktError("--force, --destination and --no-recover require a selected harness")
        if (force && strict) throw CliktError("--force and --no-recover cannot be combined")
        return selectedCommand(requiresHarness)
    }

    private fun selectedCommand(requiresHarness: Boolean): ManagementCommand =
        when (val admission = admitConnection(harness, transport)) {
            is ConnectionAdmission.Selected -> selectedRegistration(admission.connection)
            is ConnectionAdmission.Plugin -> {
                if (requiresHarness) throw CliktError(ConnectionFailure.CODEX_PLUGIN_OPTIONS.explanation)
                ManagementCommand.Plugin(admission.harness, false)
            }
            is ConnectionAdmission.Rejected -> throw CliktError(admission.failure.explanation)
            ConnectionAdmission.ListAvailable -> selectedRegistration(null)
        }

    private fun selectedRegistration(connection: HarnessConnection?) =
        ManagementCommand.Connect(
            connection,
            when {
                force -> RegistrationOwnership.REPLACE_SELECTED_SLOT
                strict -> RegistrationOwnership.REQUIRE_OWNED
                else -> RegistrationOwnership.RECOVER_SELECTED_SLOT
            },
            directory,
        )
}

internal class DisconnectCommand : ManagementNode("disconnect") {
    private val harness by
        argument("harness").convert { raw ->
            Harness.parse(raw) ?: fail("Supported harnesses: codex, copilot, pi")
        }

    override fun help(context: Context) = "Remove a registration owned by this installation."

    override fun selection() = ManagementCommand.Disconnect(harness)
}
