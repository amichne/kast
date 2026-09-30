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
        try {
            internalInstall(arguments.toList(), System.getenv())
        } catch (failure: ManagementRejected) {
            System.err.println("kast: ${failure.stage}: ${failure.reason}")
            exitProcess(1)
        }
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

@Suppress("ThrowsCount")
private fun internalInstall(arguments: List<String>, environment: Map<String, String>) {
    if (arguments.size != 2) throw ManagementRejected("installer-protocol", "invalid request")
    val rootRaw =
        environment["KAST_INSTALL_ROOT"]
            ?: throw ManagementRejected("installer-protocol", "installation root unavailable")
    val root =
        when (val resolved = ManagementRootResolution.Selected.admit(rootRaw)) {
            is ManagementRootResolution.Selected -> resolved.root
            is ManagementRootResolution.Rejected ->
                throw ManagementRejected("installer-protocol", resolved.failure.reason)
        }
    when (arguments[1]) {
        "preflight" -> println(preflightPublicExecutable(root, environment).path)
        "commit" -> {
            val channel =
                when (environment["KAST_MANAGEMENT_CHANNEL"]) {
                    "stable" -> ReleaseChannel.STABLE
                    "developer" -> ReleaseChannel.DEVELOPER
                    else -> throw ManagementRejected("installer-protocol", "release channel unavailable")
                }
            val destination = commitPublicExecutable(root, environment, channel)
            println(destination.path)
            if (!destination.onPath)
                System.err.println("kast: installed ${destination.path}; add ${destination.path.parent} to PATH")
        }
        else -> throw ManagementRejected("installer-protocol", "invalid request")
    }
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

private class ConnectCommand : ManagementNode("connect") {
    private val force by option("--force", help = "Replace only this harness's Kast registration slot.").flag()
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
        if (force && harness == null) throw CliktError("--force requires a selected harness")
        val connection =
            when (val admission = admitConnection(harness, transport)) {
                is ConnectionAdmission.Selected -> admission.connection
                is ConnectionAdmission.Rejected -> throw CliktError(admission.failure.explanation)
                ConnectionAdmission.ListAvailable -> null
            }
        return ManagementCommand.Connect(
            connection,
            if (force) RegistrationOwnership.REPLACE_SELECTED_SLOT else RegistrationOwnership.REQUIRE_OWNED,
        )
    }
}

private class DisconnectCommand : ManagementNode("disconnect") {
    private val harness by
        argument("harness").convert { raw ->
            Harness.parse(raw) ?: fail("Supported harnesses: codex, copilot, pi")
        }

    override fun help(context: Context) = "Remove a registration owned by this installation."

    override fun selection() = ManagementCommand.Disconnect(harness)
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
    override fun help(context: Context) = "Install the latest verified release on the selected channel."

    override fun selection() = ManagementCommand.Upgrade
}

private class UninstallCommand : ManagementNode("uninstall") {
    override fun help(context: Context) = "Remove this installation and its owned registrations."

    override fun selection() = ManagementCommand.Uninstall
}

internal data class ManagementRejected(val stage: String, val reason: String) : RuntimeException()

/** An explicit root is authoritative; invalid input never selects the default installation. */
internal fun resolveManagementRoot(environment: Map<String, String>): ManagementRootResolution {
    val explicit = environment["KAST_INSTALL_ROOT"]
    val raw =
        if (explicit != null) explicit
        else {
            val home =
                environment["HOME"] ?: return ManagementRootResolution.Rejected(ManagementRootFailure.HOME_UNAVAILABLE)
            val dataHome = environment["XDG_DATA_HOME"].takeUnless { it.isNullOrEmpty() } ?: "$home/.local/share"
            "$dataHome/kast"
        }
    return ManagementRootResolution.Selected.admit(raw)
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
        is ManagementCommand.Status -> {
            val commandPath =
                ProcessHandle.current()
                    .info()
                    .command()
                    .map { Path.of(it).toAbsolutePath().normalize().toString() }
                    .orElse("unavailable")
            val status = readStatus(root, commandPath)
            if (command.json) println(status.asJson())
            else {
                println("Command: ${status.commandPath}")
                println("Installation: ${status.resolvedInstallationPath.value ?: "unavailable"}")
                println("Installed: ${status.installedVersion.value ?: "unavailable"}")
                println("Loaded: ${status.loadedVersion.value ?: "unavailable"}")
                val integrations =
                    status.registrations.value?.joinToString { it.connection.publicName } ?: "unavailable"
                println("Recorded integrations: $integrations")
                println("Active workspaces: ${status.activeWorkspaces.value?.joinToString() ?: "unavailable"}")
                println("Live connections: ${status.liveConnections.value ?: "unavailable"}")
                println("One-shot requests in flight: ${status.oneShotRequestsInFlight.value ?: "unavailable"}")
            }
        }
        is ManagementCommand.Connect -> {
            if (command.connection == null) println("Supported integrations: codex mcp, codex app-server, copilot, pi")
            else {
                val repeated = connectHarness(root, Path.of(home), command.connection, command.ownership)
                println(
                    if (repeated) "${command.connection.publicName} is already registered"
                    else "Registered ${command.connection.publicName}"
                )
                if (command.connection == HarnessConnection.CODEX_APP_SERVER) {
                    val launcher = Path.of(home).resolve(".local/bin/kast-codex")
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
        ManagementCommand.Upgrade -> upgradeInstallation(root, Path.of(home))
        ManagementCommand.Uninstall -> uninstallInstallation(root, Path.of(home))
        ManagementCommand.Version -> println(MANAGEMENT_VERSION)
        ManagementCommand.Help -> Unit
    }
}
