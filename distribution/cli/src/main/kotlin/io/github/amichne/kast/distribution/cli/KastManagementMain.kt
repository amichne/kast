package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parsers.CommandLineParser
import java.nio.file.Path
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

private fun executeInternalInstall(arguments: List<String>) {
    val exit =
        try {
            internalInstall(arguments, System.getenv())
        } catch (failure: ManagementRejected) {
            System.err.println("kast: ${failure.stage}: ${failure.reason}")
            exitProcess(1)
        }
    if (exit != InternalInstallExit.COMPLETED) exitProcess(exit.code)
}

@Suppress("ThrowsCount")
private fun internalInstall(arguments: List<String>, environment: Map<String, String>): InternalInstallExit {
    if (arguments.size != 2) throw ManagementRejected("installer-protocol", "invalid request")
    if ("KAST_INSTALL_ROOT" !in environment)
        throw ManagementRejected("installer-protocol", "installation root unavailable")
    val root =
        when (val resolved = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Selected -> resolved.root
            is ManagementRootResolution.Rejected ->
                throw ManagementRejected("installer-protocol", resolved.failure.reason)
        }
    when (arguments[1]) {
        "preflight" -> {
            println(preflightPublicExecutable(root, environment).path)
            return InternalInstallExit.COMPLETED
        }
        "commit" -> return completeInternalInstall(root, environment, InstallationCompletion.STAGED)
        "commit-active" -> return completeInternalInstall(root, environment, InstallationCompletion.ACTIVATE)
        else -> throw ManagementRejected("installer-protocol", "invalid request")
    }
}

private fun completeInternalInstall(
    root: Path,
    environment: Map<String, String>,
    completion: InstallationCompletion,
): InternalInstallExit {
    val channel =
        when (environment["KAST_MANAGEMENT_CHANNEL"]) {
            "stable" -> ReleaseChannel.STABLE
            "developer" -> ReleaseChannel.DEVELOPER
            else -> throw ManagementRejected("installer-protocol", "release channel unavailable")
        }
    val result = completePublicInstallation(root, environment, channel, completion)
    val destination = result.destination
    println(destination.path)
    if (!destination.onPath)
        System.err.println("kast: installed ${destination.path}; add ${destination.path.parent} to PATH")
    return when (result) {
        is InstallationCompletionResult.Published -> InternalInstallExit.COMPLETED
        is InstallationCompletionResult.ActivationRejected -> InternalInstallExit.ACTIVATION_REJECTED
    }
}

private enum class InternalInstallExit(val code: Int) {
    COMPLETED(0),
    ACTIVATION_REJECTED(1),
}

internal sealed interface ManagementParsing {
    data class Selected(val command: ManagementCommand) : ManagementParsing

    data class Print(val text: String, val error: Boolean) : ManagementParsing
}

internal fun parseManagementCommand(arguments: List<String>): ManagementParsing {
    val root =
        ManagementRoot()
            .subcommands(
                StatusCommand(),
                StopCommand(),
                ReinstallCommand(),
                ConnectCommand(),
                DisconnectCommand(),
                PluginCommand(),
                UpgradeCommand(),
                UninstallCommand(),
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

    override fun help(context: Context) = "Inspect and manage one Kast installation and its agent integrations."

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

private class PluginCommand : ManagementNode("plugin") {
    private val json by option("--json", help = "Print the typed plugin installation outcome.").flag()
    private val harness by
        argument("harness")
            .convert { raw ->
                when (raw.lowercase()) {
                    "codex" -> PluginHarness.CODEX
                    else -> fail("Supported plugin harnesses: codex")
                }
            }
            .optional()

    override fun help(context: Context) =
        "Install the release-bundled MCP and skill plugin. Supported plugin harnesses: codex."

    override fun selection(): ManagementCommand {
        if (json && harness == null) throw CliktError("--json requires a selected plugin harness")
        return harness?.let { ManagementCommand.Plugin(it, json) } ?: ManagementCommand.ListPluginHarnesses
    }
}

private class UpgradeCommand : ManagementNode("upgrade") {
    private val controlOnly by
        option("--control-only", help = "Upgrade control and reuse the admitted running IntelliJ host.").flag()

    override fun help(context: Context) = "Install the latest verified release on the selected channel."

    override fun selection() = ManagementCommand.Upgrade(controlOnly)
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

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
private fun perform(command: ManagementCommand) {
    val environment = System.getenv()
    val home = environment["HOME"] ?: throw ManagementRejected("environment", "HOME is unavailable")
    val root =
        when (val resolved = resolveManagementRoot(environment)) {
            is ManagementRootResolution.Selected -> resolved.root
            is ManagementRootResolution.Rejected -> throw ManagementRejected("environment", resolved.failure.reason)
        }
    when (command) {
        is ManagementCommand.Status -> printManagementStatus(root, command.json)
        is ManagementCommand.Connect -> {
            if (command.connection == null) println("Supported integrations: codex mcp, codex app-server, copilot, pi")
            else {
                val repeated =
                    connectHarness(
                        root,
                        Path.of(home),
                        command.connection,
                        command.ownership,
                        directory = command.directory,
                        environment = environment,
                    )
                println(
                    if (repeated) "${command.connection.publicName} is already registered"
                    else "Registered ${command.connection.publicName}"
                )
                if (command.connection == HarnessConnection.CODEX_APP_SERVER) {
                    val launcher =
                        (readReceipt(root) as ReceiptRead.Read)
                            .receipt
                            .registrations
                            .single { it.connection == command.connection }
                            .destination
                    println("Launch $launcher for Codex, or $launcher app-server for an App Server client.")
                } else println("Restart the harness to load it")
            }
        }
        is ManagementCommand.Disconnect -> {
            val removed = disconnectHarness(root, Path.of(home), command.harness)
            println(
                if (removed) "Removed ${command.harness.publicName} registration"
                else "${command.harness.publicName} was not registered by this installation"
            )
        }
        ManagementCommand.ListPluginHarnesses -> println("Supported plugin harnesses: codex")
        is ManagementCommand.Plugin -> {
            val outcome =
                when (command.harness) {
                    PluginHarness.CODEX -> installCodexPlugin(root)
                }
            if (command.json) println(outcome.asJson())
            when (outcome) {
                is PluginInstallOutcome.Installed ->
                    if (!command.json)
                        println("Installed Kast plugin for Codex; restart Codex to load its MCP and skill")
                is PluginInstallOutcome.AlreadyInstalled ->
                    if (!command.json) println("Kast plugin for Codex is already installed")
                is PluginInstallOutcome.Rejected -> {
                    if (!command.json) System.err.println("kast: plugin: ${outcome.asJson()}")
                    exitProcess(1)
                }
            }
        }
        is ManagementCommand.Lifecycle -> {
            val outcome = executeInstallationLifecycle(root, Path.of(home), environment, command.operation)
            if (command.json) println(outcome.asJson())
            else
                when (outcome) {
                    is LifecycleOutcome.Stopped ->
                        println("Kast shutdown verified; automatic restart and new tool calls are disabled")
                    is LifecycleOutcome.Reinstalled ->
                        println("Reinstalled Kast ${outcome.version}; reconnect affected harnesses")
                    is LifecycleOutcome.Rejected ->
                        System.err.println(
                            "kast: ${outcome.stage}: ${outcome.failure}; ${lifecycleRecovery(outcome.failure)}"
                        )
                    is LifecycleOutcome.Pending ->
                        System.err.println(
                            "kast: installed ${outcome.version}; ${outcome.stage}: ${outcome.failure}; " +
                                lifecycleRecovery(outcome.failure)
                        )
                }
            if (outcome is LifecycleOutcome.Rejected || outcome is LifecycleOutcome.Pending) exitProcess(1)
        }
        is ManagementCommand.Reset -> {
            val outcome = forceResetInstallation(root, Path.of(home), environment, command.operation)
            when (presentForceReset(outcome, command.json)) {
                ForceResetExit.COMPLETE -> Unit
                ForceResetExit.INCOMPLETE -> exitProcess(1)
            }
        }
        is ManagementCommand.Upgrade -> upgradeInstallation(root, Path.of(home), command.controlOnly)
        ManagementCommand.Uninstall -> uninstallInstallation(root, Path.of(home))
        ManagementCommand.Version -> println(MANAGEMENT_VERSION)
        ManagementCommand.Help -> Unit
    }
}

private fun lifecycleRecovery(failure: LifecycleFailure): String =
    when (failure) {
        LifecycleFailure.HOST_RESTART_REQUIRED -> "quit the selected IntelliJ IDEA, then repeat this command"
        LifecycleFailure.REGISTRATION_REPAIR_REQUIRED -> "repair the recorded harness registration with kast connect"
        LifecycleFailure.OWNERSHIP_UNPROVEN,
        LifecycleFailure.FENCE_REJECTED,
        LifecycleFailure.CHILD_REJECTED,
        LifecycleFailure.CHILD_DEADLINE_EXCEEDED,
        LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN,
        LifecycleFailure.REQUESTS_DID_NOT_RETIRE,
        LifecycleFailure.HOST_OBSERVATION_REJECTED,
        LifecycleFailure.INSTALLATION_REJECTED,
        LifecycleFailure.FILESYSTEM_REJECTED ->
            "inspect kast status --json; shutdown fencing and recovery evidence are retained"
    }

private fun printManagementStatus(root: Path, json: Boolean) {
    val commandPath =
        ProcessHandle.current()
            .info()
            .command()
            .map { Path.of(it).toAbsolutePath().normalize().toString() }
            .orElse("unavailable")
    val status = readStatus(root, commandPath)
    println(if (json) status.asJson() else status.asText())
}
