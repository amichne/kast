package io.github.amichne.kast.cli.command.knowledge

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.command.LocalCommandFamily
import io.github.amichne.kast.cli.command.LocalKastCommand
import io.github.amichne.kast.cli.knowledge.KnowledgeSelection
import io.github.amichne.kast.cli.knowledge.KnowledgeSelectionAdmission

internal fun knowledgeCommandFamily(): LocalCommandFamily {
    val command = KnowledgeCommand()
    return LocalCommandFamily(command, listOf(command))
}

private class KnowledgeCommand : LocalKastCommand("knowledge", CliProductCommand.KNOWLEDGE) {
    private val selection by
        argument(
                "QUERY_OR_RESOURCE",
                help = "Declaration/module text to search, or an exact resource path returned by a prior lookup.",
            )
            .convert { raw ->
                when (val admission = KnowledgeSelection.parse(raw)) {
                    is KnowledgeSelectionAdmission.Accepted -> admission.selection
                    is KnowledgeSelectionAdmission.Rejected ->
                        fail("Invalid knowledge selector: ${admission.failure.name.lowercase()}")
                }
            }

    override fun help(context: Context): String =
        "Search the installed Kast API/guidance bundle, or read one exact returned resource; no workspace or IDE is required."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Local.Knowledge(selection))
}
