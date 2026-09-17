package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

internal fun projectedLocalFamily(family: LocalCommandFamily, hidden: Boolean): LocalCommandFamily {
    val commands = family.commands.filter { it.command.exposure != CliLocalExposure.INTERNAL }
    val root =
        when (val candidate = family.root) {
            is LocalKastCommand ->
                if (candidate in commands) candidate else ProjectedCommandGroup(candidate).subcommands(commands)
            else -> ProjectedCommandGroup(candidate).subcommands(commands)
        }
    val agentOnly = commands.isNotEmpty() && commands.all { it.command.exposure == CliLocalExposure.AGENT }
    val projectedRoot = if (hidden || agentOnly) HiddenProjectedCommandGroup(root).subcommands(commands) else root
    return LocalCommandFamily(projectedRoot, commands)
}

/** Rebuilds a plain family group before registration, retaining its name and help description. */
internal class ProjectedCommandGroup(private val source: KastCommand) : KastCommandGroup(source.commandName, "") {
    override fun help(context: Context): String = source.help(context)
}

internal class HiddenProjectedCommandGroup(private val source: KastCommand) : KastCommandGroup(source.commandName, "") {
    override val hiddenFromHelp: Boolean = true

    override fun help(context: Context): String = source.help(context)
}

internal val hiddenImplementationCommands =
    setOf(
        CliProductCommand.INDEX_REFRESH,
        CliProductCommand.INDEX_STATUS,
        CliProductCommand.INDEX_CLASSES,
        CliProductCommand.INDEX_SUPERTYPE,
        CliProductCommand.INDEX_COMPLETION,
        CliProductCommand.IDE_TRUST_BROKER,
        CliProductCommand.IDE_REFRESH,
        CliProductCommand.IDE_STATUS,
        CliProductCommand.IDE_CLASSES,
        CliProductCommand.IDE_SUPERTYPE,
        CliProductCommand.IDE_COMPLETION,
    )
