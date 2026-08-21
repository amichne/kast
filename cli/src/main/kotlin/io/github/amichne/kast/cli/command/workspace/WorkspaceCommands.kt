package io.github.amichne.kast.cli.command.workspace

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest

internal fun workspaceCommandGroup(
    preparers: CanonicalCliRequestPreparers,
): CommandFamily {
    val inspect = WorkspaceInspectCommand(preparers)
    return CommandFamily(
        KastCommandGroup("workspace", "Inspect the exact canonical workspace.")
            .subcommands(inspect),
        listOf(inspect),
    )
}

private class WorkspaceInspectCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<WorkspaceInspectRequest>(
    name = "inspect",
    operation = CanonicalOperation.WORKSPACE_INSPECT,
    schemaUsage = "workspace inspect",
    preparer = preparers.workspaceInspect,
) {
    override fun help(context: Context): String =
        "Inspect workspace readiness and canonical root identity."

    override fun resolveAction(): CliActionResolution = prepare(WorkspaceInspectRequest)
}
