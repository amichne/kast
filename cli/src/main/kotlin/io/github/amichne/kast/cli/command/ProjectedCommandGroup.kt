package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.Context

/** Rebuilds a plain family group before registration, retaining its name and help description. */
internal class ProjectedCommandGroup(private val source: KastCommand) : KastCommandGroup(source.commandName, "") {
    override fun help(context: Context): String = source.help(context)
}
