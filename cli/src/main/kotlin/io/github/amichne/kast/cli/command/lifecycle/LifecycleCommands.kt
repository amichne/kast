package io.github.amichne.kast.cli.command.lifecycle

import com.github.ajalt.clikt.core.Context
import io.github.amichne.kast.cli.CliProjectionPreparation
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.LifecycleKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest

internal fun lifecycleCommands(
    preparers: CanonicalCliRequestPreparers,
): List<LifecycleKastCommand> = listOf(
    StartCommand(preparers),
    StopCommand,
    StatusCommand,
    CleanCommand,
    ReindexCommand(preparers),
)

private class StartCommand(
    private val preparers: CanonicalCliRequestPreparers,
) : LifecycleKastCommand("start", CliLifecycleCommand.START) {
    override fun help(context: Context): String = "Admit the existing exact-root IDE endpoint."

    override fun resolveAction(): CliActionResolution = prepareInspection(preparers) { request ->
        CliAction.Lifecycle.Start(request)
    }
}

private data object StopCommand : LifecycleKastCommand("stop", CliLifecycleCommand.STOP) {
    override fun help(context: Context): String = "Reject because the IDE owns endpoint lifecycle."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Lifecycle.Stop)
}

private data object StatusCommand : LifecycleKastCommand("status", CliLifecycleCommand.STATUS) {
    override fun help(context: Context): String =
        "Report the admitted exact-root IDE endpoint as running."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Lifecycle.Status)
}

private data object CleanCommand : LifecycleKastCommand("clean", CliLifecycleCommand.CLEAN) {
    override fun help(context: Context): String =
        "Reject because hosted endpoint state is IDE-owned."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Lifecycle.Clean)
}

private class ReindexCommand(
    private val preparers: CanonicalCliRequestPreparers,
) : LifecycleKastCommand("reindex", CliLifecycleCommand.REINDEX) {
    override fun help(context: Context): String =
        "Reject because the CLI cannot rebuild IDE-owned state."

    override fun resolveAction(): CliActionResolution = prepareInspection(preparers) { request ->
        CliAction.Lifecycle.Reindex(request)
    }
}

private fun prepareInspection(
    preparers: CanonicalCliRequestPreparers,
    action: (io.github.amichne.kast.cli.PreparedCliRequest) -> CliAction.Lifecycle,
): CliActionResolution = when (
    val preparation = preparers.workspaceInspect.prepare(WorkspaceInspectRequest)
) {
    is CliProjectionPreparation.Prepared -> CliActionResolution.Selected(
        action(preparation.request),
    )
    is CliProjectionPreparation.Rejected -> CliActionResolution.ProjectionRejected(
        preparation.failure,
    )
}
