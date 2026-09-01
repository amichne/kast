package io.github.amichne.kast.cli.command.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.amichne.kast.cli.CliProjectionPreparation
import io.github.amichne.kast.cli.IndexSeedConsentRequest
import io.github.amichne.kast.cli.RuntimeStartupRequest
import io.github.amichne.kast.cli.StartupCacheIntent
import io.github.amichne.kast.cli.StartupIdeHome
import io.github.amichne.kast.cli.StartupIdeaSystem
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.LifecycleKastCommand
import io.github.amichne.kast.cli.command.CliOptionValue
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.absolutePathOption
import io.github.amichne.kast.cli.command.optionalOnce
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
    private val ideaHome by absolutePathOption(
        "--idea-home",
        "Exactly supported local IntelliJ IDEA home.",
    ).optionalOnce()
    private val seedFromIdea by option(
        "--seed-from-idea",
        help = "Clone compatible quiescent IDEA indexes into Kast's private cache.",
    ).flag(default = false)
    private val sourceIdeaSystem by absolutePathOption(
        "--source-idea-system",
        "Source IntelliJ system directory used only for explicit seeding.",
    ).optionalOnce()
    private val acceptGlobalIndexCopy by option(
        "--accept-global-index-copy",
        help = "Consent to copying required global VFS and index data.",
    ).flag(default = false)

    override fun help(context: Context): String =
        "Start the isolated exact-root IntelliJ sidecar."

    override fun resolveAction(): CliActionResolution {
        val startup = when (
            val refinement = refineStartup(
                ideaHome,
                seedFromIdea,
                sourceIdeaSystem,
                acceptGlobalIndexCopy,
            )
        ) {
            is StartInputRefinement.Refined -> refinement.request
            is StartInputRefinement.Rejected -> return CliActionResolution.UsageRejected(
                refinement.failure,
            )
        }
        return prepareInspection(preparers) { request ->
            CliAction.Lifecycle.Start(request, startup)
        }
    }
}

private sealed interface StartInputRefinement {
    data class Refined(val request: RuntimeStartupRequest) : StartInputRefinement
    data class Rejected(val failure: CliUsageFailure.Start) : StartInputRefinement
}

private fun refineStartup(
    ideaHome: CliOptionValue<java.nio.file.Path>,
    seedFromIdea: Boolean,
    sourceIdeaSystem: CliOptionValue<java.nio.file.Path>,
    acceptGlobalIndexCopy: Boolean,
): StartInputRefinement {
    if (!seedFromIdea && (sourceIdeaSystem is CliOptionValue.Present || acceptGlobalIndexCopy)) {
        return StartInputRefinement.Rejected(CliUsageFailure.Start.OPTIONS_REQUIRE_SEED)
    }
    if (
        ideaHome is CliOptionValue.Absent && !seedFromIdea &&
        sourceIdeaSystem is CliOptionValue.Absent && !acceptGlobalIndexCopy
    ) {
        return StartInputRefinement.Refined(RuntimeStartupRequest.Default)
    }
    val ideHome = when (ideaHome) {
        CliOptionValue.Absent -> StartupIdeHome.Standard
        is CliOptionValue.Present -> StartupIdeHome.Explicit(ideaHome.value)
    }
    val cacheIntent = if (seedFromIdea) {
        StartupCacheIntent.Seed(
            when (sourceIdeaSystem) {
                CliOptionValue.Absent -> StartupIdeaSystem.Standard
                is CliOptionValue.Present -> StartupIdeaSystem.Explicit(sourceIdeaSystem.value)
            },
            if (acceptGlobalIndexCopy) {
                IndexSeedConsentRequest.PREGRANTED
            } else {
                IndexSeedConsentRequest.INTERACTIVE
            },
        )
    } else {
        StartupCacheIntent.ReuseOrFresh
    }
    return StartInputRefinement.Refined(RuntimeStartupRequest.Requested(ideHome, cacheIntent))
}

private data object StopCommand : LifecycleKastCommand("stop", CliLifecycleCommand.STOP) {
    override fun help(context: Context): String =
        "Stop only the process proven to own this exact workspace endpoint."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Lifecycle.Stop)
}

private data object StatusCommand : LifecycleKastCommand("status", CliLifecycleCommand.STATUS) {
    override fun help(context: Context): String =
        "Report exact-root runtime and private cache identity and state."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Lifecycle.Status)
}

private data object CleanCommand : LifecycleKastCommand("clean", CliLifecycleCommand.CLEAN) {
    override fun help(context: Context): String =
        "Remove only inactive exact-root endpoint artifacts; retain the private cache."

    override fun resolveAction(): CliActionResolution =
        CliActionResolution.Selected(CliAction.Lifecycle.Clean)
}

private class ReindexCommand(
    private val preparers: CanonicalCliRequestPreparers,
) : LifecycleKastCommand("reindex", CliLifecycleCommand.REINDEX) {
    override fun help(context: Context): String =
        "Quarantine the exact Kast-owned cache and rebuild with its recorded IDEA runtime."

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
