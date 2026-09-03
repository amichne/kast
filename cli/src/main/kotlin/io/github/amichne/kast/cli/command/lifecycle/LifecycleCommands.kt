package io.github.amichne.kast.cli.command.lifecycle

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
import io.github.amichne.kast.cli.command.closedChoiceOption
import io.github.amichne.kast.cli.command.defaultOnce
import io.github.amichne.kast.cli.command.optionalOnce

internal fun lifecycleCommands(): List<LifecycleKastCommand> = listOf(
    StartCommand,
    StopCommand,
    StatusCommand,
)

private data object StartCommand : LifecycleKastCommand("start", CliLifecycleCommand.START) {
    private val ideaHome by absolutePathOption(
        "--idea-home",
        "Exactly supported local IntelliJ IDEA home.",
    ).optionalOnce()
    private val cacheMode by closedChoiceOption(
        "--cache",
        "reuse|seed|rebuild",
        "Private cache policy for this start.",
        linkedMapOf(
            "reuse" to StartCacheMode.REUSE,
            "seed" to StartCacheMode.SEED,
            "rebuild" to StartCacheMode.REBUILD,
        ),
    ).defaultOnce(StartCacheMode.REUSE, "reuse")
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
                cacheMode,
                sourceIdeaSystem,
                acceptGlobalIndexCopy,
            )
        ) {
            is StartInputRefinement.Refined -> refinement.request
            is StartInputRefinement.Rejected -> return CliActionResolution.UsageRejected(
                refinement.failure,
            )
        }
        return CliActionResolution.Selected(CliAction.Lifecycle.Start(startup))
    }
}

private enum class StartCacheMode { REUSE, SEED, REBUILD }

private sealed interface StartInputRefinement {
    data class Refined(val request: RuntimeStartupRequest) : StartInputRefinement
    data class Rejected(val failure: CliUsageFailure.Start) : StartInputRefinement
}

private fun refineStartup(
    ideaHome: CliOptionValue<java.nio.file.Path>,
    cacheMode: StartCacheMode,
    sourceIdeaSystem: CliOptionValue<java.nio.file.Path>,
    acceptGlobalIndexCopy: Boolean,
): StartInputRefinement {
    if (
        cacheMode != StartCacheMode.SEED &&
        (sourceIdeaSystem is CliOptionValue.Present || acceptGlobalIndexCopy)
    ) {
        return StartInputRefinement.Rejected(CliUsageFailure.Start.OPTIONS_REQUIRE_SEED)
    }
    if (
        ideaHome is CliOptionValue.Absent && cacheMode == StartCacheMode.REUSE &&
        sourceIdeaSystem is CliOptionValue.Absent && !acceptGlobalIndexCopy
    ) {
        return StartInputRefinement.Refined(RuntimeStartupRequest.Default)
    }
    val ideHome = when (ideaHome) {
        CliOptionValue.Absent -> StartupIdeHome.Standard
        is CliOptionValue.Present -> StartupIdeHome.Explicit(ideaHome.value)
    }
    val cacheIntent = when (cacheMode) {
        StartCacheMode.REUSE -> StartupCacheIntent.Reuse
        StartCacheMode.REBUILD -> StartupCacheIntent.Rebuild
        StartCacheMode.SEED -> StartupCacheIntent.Seed(
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
