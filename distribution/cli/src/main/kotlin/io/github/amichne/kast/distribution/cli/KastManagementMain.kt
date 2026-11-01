package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parsers.CommandLineParser
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    if (arguments.firstOrNull() == "--internal-install") {
        executeInternalInstall(arguments.toList())
        return
    }
    when (val parsed = parseManagementCommand(arguments.toList())) {
        is ManagementParsing.Selected ->
            try {
                perform(parsed.command)
            } catch (failure: ManagementRejected) {
                System.err.println("kast: ${failure.stage}: ${failure.reason}")
                exitProcess(1)
            }
        is ManagementParsing.Print -> {
            (if (parsed.error) System.err else System.out).println(parsed.text)
            if (parsed.error) exitProcess(2)
        }
    }
}

internal sealed interface ManagementParsing {
    data class Selected(val command: ManagementCommand) : ManagementParsing

    data class Print(val text: String, val error: Boolean) : ManagementParsing
}

internal fun parseManagementCommand(arguments: List<String>): ManagementParsing {
    if (arguments.firstOrNull() in publicQueryNamespaces)
        return ManagementParsing.Selected(ManagementCommand.PublicQuery(arguments.toList()))
    if (arguments.firstOrNull() in setOf("connect", "plugin"))
        return ManagementParsing.Print("kast: setup syntax is retired; use kast install <harness> [--mcp]", true)
    val root =
        ManagementRoot()
            .subcommands(
                StatusCommand(),
                StopCommand(),
                ReinstallCommand(),
                DisconnectCommand(),
                InstallCommand(),
                VersionsCommand(),
                UpgradeCommand(),
                UninstallCommand(),
                PublicQueryNamespace("query"),
                PublicQueryNamespace("result"),
                PublicQueryNamespace("source"),
                PublicQueryNamespace("describe"),
            )
    var selected: ManagementCommand? = null
    return try {
        val parsed = CommandLineParser.parse(root, arguments)
        CommandLineParser.run(parsed.invocation) { command -> command.selection()?.let { selected = it } }
        selected?.let(ManagementParsing::Selected) ?: ManagementParsing.Print(root.getFormattedHelp().orEmpty(), true)
    } catch (version: PrintMessage) {
        ManagementParsing.Print(version.message.orEmpty(), false)
    } catch (help: PrintHelpMessage) {
        ManagementParsing.Print(root.getFormattedHelp(help).orEmpty(), help.error)
    } catch (failure: CliktError) {
        ManagementParsing.Print(root.getFormattedHelp(failure).orEmpty(), true)
    }
}

internal abstract class ManagementNode(name: String) : BaseCliktCommand<ManagementNode>(name) {
    abstract fun selection(): ManagementCommand?
}

internal class ManagementRoot : ManagementNode("kast") {
    override val invokeWithoutSubcommand = true
    override val printHelpOnEmptyArgs = false

    init {
        eagerOption("--version", help = "Print this executable's version") { throw PrintMessage(MANAGEMENT_VERSION) }
    }

    override fun help(context: Context) = "Query Kotlin and manage one Kast installation and its agent integrations."

    override fun selection() = if (currentContext.invokedSubcommand == null) ManagementCommand.Status(false) else null
}

private class StatusCommand : ManagementNode("status") {
    private val json by option("--json", help = "Print a typed JSON status document").flag()

    override fun help(context: Context) = "Inspect installation receipts and passive runtime observations."

    override fun selection() = ManagementCommand.Status(json)
}

private class StopCommand : ManagementNode("stop") {
    private val json by option("--json", help = "Print the typed lifecycle outcome.").flag()
    private val force by option("--force", help = "Terminate exactly owned tool processes instead of waiting.").flag()

    override fun help(context: Context) = "Disable automatic restart and verify shutdown; preserve the installation."

    override fun selection() =
        ManagementCommand.Lifecycle(if (force) LifecycleOperation.STOP_FORCE else LifecycleOperation.STOP, json)
}

private class ReinstallCommand : ManagementNode("reinstall") {
    private val json by option("--json", help = "Print the typed lifecycle outcome.").flag()
    private val force by
        option(
                "--force",
                help = "Retire scoped daemons, erase Kast without prior ownership checks, then install fresh.",
            )
            .flag()

    override fun help(context: Context) = "Reinstall the selected release, or reset to the latest release with --force."

    override fun selection() =
        if (force) ManagementCommand.Reset(ForceResetOperation.REINSTALL, json)
        else ManagementCommand.Lifecycle(LifecycleOperation.REINSTALL, json)
}

private class UpgradeCommand : ManagementNode("upgrade") {
    private val channel by option("--channel", help = "Select stable or main.")
    private val version by option("--version", help = "Pin an exact stable release.")
    private val commit by
        option("--commit", help = "Pin a published main revision or its displayed 12-character prefix.")
    private val ideaHome by option("--idea-home", help = "Select the exact IDEA installation.")
    private val dryRun by
        option("--dry-run", help = "Admit the selected artifacts without installation effects.").flag()
    private val restartIde by option("--restart-ide", help = "Permit a normal IDEA restart when necessary.").flag()
    private val json by option("--json", help = "Print one terminal management result.").flag()

    override fun help(context: Context) = "Install the latest verified release on the selected channel."

    override fun selection(): ManagementCommand =
        when (val selected = admitBuildRequest(channel, version, commit)) {
            is SelectionAdmission.Admitted ->
                ManagementCommand.Upgrade(
                    request = selected.value,
                    ideaHome = ideaHome,
                    dryRun = dryRun,
                    restartIde = restartIde,
                    json = json,
                )
            is SelectionAdmission.Rejected -> throw CliktError(selected.reason.name)
        }
}

private class VersionsCommand : ManagementNode("versions") {
    private val json by option("--json", help = "Print the captured build catalog.").flag()

    override fun help(context: Context) = "List stable and the five newest selectable published main builds."

    override fun selection() = ManagementCommand.Versions(json)
}

private class InstallCommand : ManagementNode("install") {
    private val target by argument("harness|skill")
    private val output by
        option("--output", help = "Select the parent skill directory. The default is ./.agents/skills.")
    private val mcp by option("--mcp", help = "Install the optional MCP composition.").flag()
    private val json by option("--json", help = "Print one terminal provisioning result.").flag()

    override fun help(context: Context) =
        "Install canonical CLI guidance for codex, copilot, or pi, or export its skill."

    override fun selection(): ManagementCommand =
        when (val selected = admitProvisionTarget(target, output, mcp)) {
            is ProvisionTargetAdmission.Admitted -> ManagementCommand.Install(selected.target, json)
            is ProvisionTargetAdmission.Rejected -> throw CliktError(selected.reason)
        }
}

private class UninstallCommand : ManagementNode("uninstall") {
    private val force by
        option("--force", help = "Retire scoped daemons and erase Kast without prior ownership checks.").flag()
    private val json by option("--json", help = "Print the typed force reset outcome; requires --force.").flag()

    override fun help(context: Context) = "Remove this installation and its owned registrations."

    override fun selection(): ManagementCommand {
        if (json && !force) throw CliktError("--json requires --force for uninstall")
        return if (force) ManagementCommand.Reset(ForceResetOperation.UNINSTALL, json) else ManagementCommand.Uninstall
    }
}

internal data class ManagementRejected(val stage: String, val reason: String) : RuntimeException()

/** Each user has one installation; an explicit root can only bind that same installation. */
internal fun resolveManagementRoot(environment: Map<String, String>): ManagementRootResolution {
    val home = environment["HOME"] ?: return ManagementRootResolution.Rejected(ManagementRootFailure.HOME_UNAVAILABLE)
    return ManagementRootResolution.Selected.admit(home, environment["KAST_INSTALL_ROOT"])
}
