package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.output.PlaintextHelpFormatter
import com.github.ajalt.clikt.parameters.options.eagerOption

internal class KastRootCommand : KastCommand("kast") {
    override val invokeWithoutSubcommand: Boolean = true
    override val printHelpOnEmptyArgs: Boolean = false

    init {
        configureContext {
            helpFormatter = { context ->
                PlaintextHelpFormatter(
                    context,
                    showDefaultValues = true,
                    showRequiredTag = true,
                )
            }
        }
        eagerOption("--version", help = "Show the installed IntelliJ plugin product version") {
            throw CliLocalCommandMessage(CliLocalMetadataCommand.VERSION)
        }
        eagerOption("--schema", help = "Print the installed machine-readable schema") {
            throw CliLocalCommandMessage(CliLocalMetadataCommand.SCHEMA)
        }
    }

    override fun help(context: Context): String =
        "Query installed knowledge or the existing IDEA index; inspect and change a workspace through its Kast plugin."

    override fun helpEpilog(context: Context): String =
        "Semantic results are one JSON document on stdout. Diagnostics are one JSON document on stderr. Bootstrap commands: kast config show|explain|validate|schema; kast installation inspect|recover-read-only|reset|remove. Use kast config --help or kast installation --help for options. The release bootstrap runs kast installation install; install.sh --force resets and reinstalls the complete suite."

    override fun resolveAction(): CliNodeResolution =
        if (currentContext.invokedSubcommand == null) {
            CliActionResolution.Selected(CliAction.Local.Inspect)
        } else CliNodeResolution.NoAction
}

internal class CliLocalCommandMessage(val command: CliLocalMetadataCommand) : PrintMessage(command.name.lowercase())
