package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.BaseCliktCommand
import io.github.amichne.kast.cli.CliProjectionFailure
import io.github.amichne.kast.cli.CliProjectionPreparation
import io.github.amichne.kast.cli.CliRequestPreparer
import io.github.amichne.kast.cli.PreparedCliRequest
import io.github.amichne.kast.cli.RuntimeStartupRequest
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationRequest

enum class CliLocalMetadataCommand { VERSION, SCHEMA }

enum class CliProductCommand(
    val usage: String,
) {
    INSPECT("product inspect"),
    BROKER_SERVE("broker serve"),
}

/** Process-local operator actions that do not extend the semantic wire protocol. */
enum class CliLifecycleCommand(
    val command: String,
) {
    START("start"),
    STOP("stop"),
    STATUS("status"),
    CLEAN("clean"),
    REINDEX("reindex"),
}

/** One fully refined action selected by the public command graph. */
sealed interface CliAction {
    sealed interface Local : CliAction {
        data class Metadata(
            val command: CliLocalMetadataCommand,
        ) : Local

        data object ProductInspect : Local

        data object BrokerServe : Local
    }

    data class Semantic(
        val request: PreparedCliRequest,
    ) : CliAction

    sealed interface Lifecycle : CliAction {
        val command: CliLifecycleCommand

        data class Start(
            val request: PreparedCliRequest,
            val startup: RuntimeStartupRequest,
        ) : Lifecycle {
            override val command: CliLifecycleCommand = CliLifecycleCommand.START
        }

        data object Stop : Lifecycle {
            override val command: CliLifecycleCommand = CliLifecycleCommand.STOP
        }

        data object Status : Lifecycle {
            override val command: CliLifecycleCommand = CliLifecycleCommand.STATUS
        }

        data object Clean : Lifecycle {
            override val command: CliLifecycleCommand = CliLifecycleCommand.CLEAN
        }

        data class Reindex(
            val request: PreparedCliRequest,
        ) : Lifecycle {
            override val command: CliLifecycleCommand = CliLifecycleCommand.REINDEX
        }
    }
}

/** Closed domain failures produced after Clikt has refined individual option values. */
sealed interface CliUsageFailure {
    enum class Start : CliUsageFailure {
        OPTIONS_REQUIRE_SEED,
    }

    enum class SymbolDiscover : CliUsageFailure {
        OPTIONS_DO_NOT_MATCH_MODE,
        TEXT_SCOPE_REQUIRED,
        TEXT_FILE_REQUIRED,
        TEXT_FILE_REJECTED,
    }

    enum class SourceRead : CliUsageFailure {
        ANCHOR_REJECTED,
        VISIBILITY_REQUIRES_DECLARATIONS,
        CONTAINMENT_REQUIRES_ENTITIES,
        WINDOW_LINES_REQUIRE_WINDOW_TEXT,
        DUPLICATE_SELECTION,
    }

    enum class ChangePlan : CliUsageFailure {
        OPTIONS_DO_NOT_MATCH_INTENT,
    }
}

internal fun CliUsageFailure.message(): String = when (this) {
    CliUsageFailure.Start.OPTIONS_REQUIRE_SEED ->
        "--source-idea-system and --accept-global-index-copy require --seed-from-idea"
    CliUsageFailure.SymbolDiscover.OPTIONS_DO_NOT_MATCH_MODE ->
        "options do not match the selected discovery mode"
    CliUsageFailure.SymbolDiscover.TEXT_SCOPE_REQUIRED ->
        "text discovery requires --scope workspace or --scope file"
    CliUsageFailure.SymbolDiscover.TEXT_FILE_REQUIRED ->
        "text discovery with --scope file requires --file"
    CliUsageFailure.SymbolDiscover.TEXT_FILE_REJECTED ->
        "text discovery with --scope workspace does not accept --file"
    CliUsageFailure.SourceRead.ANCHOR_REJECTED ->
        "--anchor must be one valid candidate, exact-symbol, or source selector token"
    CliUsageFailure.SourceRead.VISIBILITY_REQUIRES_DECLARATIONS ->
        "--visibility requires at least one --declaration-kind"
    CliUsageFailure.SourceRead.CONTAINMENT_REQUIRES_ENTITIES ->
        "--containment requires at least one entity filter"
    CliUsageFailure.SourceRead.WINDOW_LINES_REQUIRE_WINDOW_TEXT ->
        "--before-lines and --after-lines require --text window"
    CliUsageFailure.SourceRead.DUPLICATE_SELECTION ->
        "declaration kinds and visibility values may each be selected once"
    CliUsageFailure.ChangePlan.OPTIONS_DO_NOT_MATCH_INTENT ->
        "options do not match the selected change intent"
}

internal sealed interface CliNodeResolution {
    data object NoAction : CliNodeResolution
}

internal sealed interface CliActionResolution : CliNodeResolution {
    data class Selected(
        val action: CliAction,
    ) : CliActionResolution

    data class UsageRejected(
        val failure: CliUsageFailure,
    ) : CliActionResolution

    data class ProjectionRejected(
        val failure: CliProjectionFailure,
    ) : CliActionResolution
}

/** One Clikt node whose only result is a typed CLI action resolution. */
internal abstract class KastCommand(
    name: String,
) : BaseCliktCommand<KastCommand>(name) {
    final override val autoCompleteEnvvar: String? = null

    abstract fun resolveAction(): CliNodeResolution
}

internal open class KastCommandGroup(
    name: String,
    private val description: String,
) : KastCommand(name) {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = description

    final override fun resolveAction(): CliNodeResolution = CliNodeResolution.NoAction
}

internal abstract class SemanticKastCommand<Request : OperationRequest>(
    name: String,
    val operation: CanonicalOperation,
    val schemaUsage: String,
    private val preparer: CliRequestPreparer<Request>,
) : KastCommand(name) {
    protected fun prepare(request: Request): CliActionResolution =
        when (val preparation = preparer.prepare(request)) {
            is CliProjectionPreparation.Prepared -> CliActionResolution.Selected(
                CliAction.Semantic(preparation.request),
            )
            is CliProjectionPreparation.Rejected -> CliActionResolution.ProjectionRejected(
                preparation.failure,
            )
        }

    protected fun prepareLifecycle(
        request: Request,
        action: (PreparedCliRequest) -> CliAction.Lifecycle,
    ): CliActionResolution = when (val preparation = preparer.prepare(request)) {
        is CliProjectionPreparation.Prepared -> CliActionResolution.Selected(
            action(preparation.request),
        )
        is CliProjectionPreparation.Rejected -> CliActionResolution.ProjectionRejected(
            preparation.failure,
        )
    }
}

internal abstract class LocalKastCommand(
    name: String,
    val command: CliProductCommand,
) : KastCommand(name)

internal class LocalCommandFamily(
    val root: KastCommand,
    val commands: List<LocalKastCommand>,
)
