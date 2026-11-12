package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parsers.CommandLineParser
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.contract.configuration.selectCliOutputFormat
import io.github.amichne.kast.distribution.output.cliOutputRejection
import io.github.amichne.kast.kernel.Refinement
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    if (arguments.firstOrNull() == "--internal-install") {
        executeInternalInstall(arguments.toList())
        return
    }
    val output =
        when (val format = selectManagementEntryOutputFormat(arguments.toList(), System.getenv("KAST_OUTPUT_TOON"))) {
            is Refinement.Refined -> ManagementOutput(format.value)
            is Refinement.Rejected -> {
                println(cliOutputRejection(format.failure, CliOutputFormat.JSON))
                exitProcess(2)
            }
        }
    when (val parsed = parseManagementCommand(arguments.toList())) {
        is ManagementParsing.Selected ->
            try {
                perform(parsed.command, output)
            } catch (failure: ManagementRejected) {
                System.err.println("kast: ${failure.stage}: ${failure.reason}")
                output.rejected("${failure.stage}: ${failure.reason}")
                exitProcess(1)
            }
        is ManagementParsing.Print -> {
            if (parsed.error) output.rejected(parsed.text)
            (if (parsed.error) System.err else System.out).println(parsed.text)
            if (parsed.error) exitProcess(2)
        }
    }
}

/** Semantic format flags also govern startup rejection before the query child is admitted. */
internal fun selectManagementEntryOutputFormat(
    arguments: List<String>,
    environmentSetting: String?,
): Refinement<CliOutputFormat, CliOutputFailure> =
    if (arguments.firstOrNull() in publicQueryNamespaces && "--json" in arguments)
        Refinement.Refined(CliOutputFormat.JSON)
    else selectCliOutputFormat(environmentSetting)

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
                InstallCommand(),
                VersionsCommand(),
                UpgradeCommand(),
                UninstallCommand(),
                PublicQueryNamespace("query"),
                PublicQueryNamespace("result"),
                PublicQueryNamespace("source"),
                PublicQueryNamespace("diagnostic"),
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

    override fun help(context: Context) = "Query Kotlin and manage agent integrations."

    override fun selection() = if (currentContext.invokedSubcommand == null) ManagementCommand.Status(false) else null
}

private class StatusCommand : ManagementNode("status") {
    private val json by option("--json", help = "Print a typed JSON status document").flag()
    private val workspace by option("--workspace", help = "Inspect one Gradle workspace.")
    private val all by option("--all", help = "Inspect every recorded workspace, including unreadable records.").flag()

    override fun help(context: Context) = "Show installation and runtime status without startup."

    override fun selection() = ManagementCommand.Status(json, selectWorkspaceScope(workspace, all))
}

private class StopCommand : ManagementNode("stop") {
    private val json by option("--json", help = "Print the typed lifecycle outcome.").flag()
    private val workspace by option("--workspace", help = "Select one Gradle workspace.")
    private val all by option("--all", help = "Stop every recorded workspace Host.").flag()

    override fun help(context: Context) = "Stop owned workspace Hosts; the next semantic request may restart them."

    override fun selection() = ManagementCommand.Stop(selectWorkspaceScope(workspace, all), json)
}

private class UpgradeCommand : ManagementNode("upgrade") {
    private val channel by option("--channel", help = "Select stable or preview (main is a preview alias).")
    private val version by option("--version", help = "Pin an exact stable release.")
    private val commit by
        option("--commit", help = "Pin a published main revision or its displayed 12-character prefix.")
    private val repair by
        option("--repair", help = "Reinstall the exact selected product, including its runtime.").flag()
    private val dryRun by
        option("--dry-run", help = "Admit the selected artifacts without installation effects.").flag()
    private val json by option("--json", help = "Print one terminal management result.").flag()

    override fun help(context: Context) = "Install the latest verified release on the selected channel."

    override fun selection(): ManagementCommand =
        when (val selected = admitBuildRequest(channel, version, commit, repair)) {
            is SelectionAdmission.Admitted ->
                ManagementCommand.Upgrade(
                    request = selected.value,
                    dryRun = dryRun,
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
    private val harness by argument("harness").optional()
    private val force by
        option("--force", help = "Reset legacy state; managed products require ordinary uninstall.").flag()
    private val json by option("--json", help = "Print the force reset result.").flag()

    override fun help(context: Context) = "Remove one harness integration, or this installation when omitted."

    override fun selection(): ManagementCommand {
        val invalidOptions = if (force) harness != null else json
        if (invalidOptions) throw CliktError("--force [--json] requires uninstall without a harness")
        if (force) return ManagementCommand.ForceUninstall(json)
        return when (val selected = harness) {
            null -> ManagementCommand.Uninstall
            else ->
                ManagementCommand.UninstallHarness(
                    Harness.parse(selected) ?: throw CliktError("Select codex, copilot, or pi")
                )
        }
    }
}

internal data class ManagementRejected(val stage: String, val reason: String) : RuntimeException()

/** Each user has one installation; an explicit root can only bind that same installation. */
internal fun resolveManagementRoot(environment: Map<String, String>): ManagementRootResolution {
    val home = environment["HOME"] ?: return ManagementRootResolution.Rejected(ManagementRootFailure.HOME_UNAVAILABLE)
    return ManagementRootResolution.Selected.admit(home, environment["KAST_INSTALL_ROOT"])
}
