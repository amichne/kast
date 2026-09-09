package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.BaseCliktCommand
import io.github.amichne.kast.cli.CliProjectionFailure
import io.github.amichne.kast.cli.CliProjectionPreparation
import io.github.amichne.kast.cli.CliRequestPreparer
import io.github.amichne.kast.cli.PreparedCliRequest
import io.github.amichne.kast.cli.RuntimeStartupRequest
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class CliLocalMetadataCommand { VERSION, SCHEMA }

enum class CliLocalExposure { PUBLIC, INTERNAL }

enum class CliProductCommand(
    val usage: String,
    val exposure: CliLocalExposure = CliLocalExposure.INTERNAL,
) {
    APP_SERVER_REGISTER("app-server register", CliLocalExposure.PUBLIC),
    APP_SERVER_ENABLE("app-server enable", CliLocalExposure.PUBLIC),
    APP_SERVER_REPAIR("app-server repair --destructive", CliLocalExposure.PUBLIC),
    APP_SERVER_STATUS("app-server status", CliLocalExposure.PUBLIC),
    APP_SERVER_STOP("app-server stop", CliLocalExposure.PUBLIC),
    APP_SERVER_DISABLE("app-server disable", CliLocalExposure.PUBLIC),
    APP_SERVER_CLAIM("app-server control claim", CliLocalExposure.PUBLIC),
    APP_SERVER_RELEASE("app-server control release", CliLocalExposure.PUBLIC),
    INSPECT("product inspect"),
    BROKER_SERVE("broker serve"),
    CODEX_CLI("codex", CliLocalExposure.PUBLIC),
    CODEX_DESKTOP("codex desktop", CliLocalExposure.PUBLIC),
}

/** Process-local operator actions that do not extend the semantic wire protocol. */
enum class CliLifecycleCommand(
    val command: String,
    val exposure: CliLocalExposure = CliLocalExposure.PUBLIC,
) {
    START("start"),
    STOP("stop"),
    STATUS("status", CliLocalExposure.INTERNAL),
}

/** One fully refined action selected by the public command graph. */
sealed interface CliAction {
    sealed interface Local : CliAction {
        data class Metadata(
            val command: CliLocalMetadataCommand,
        ) : Local

        data object Inspect : Local

        data object ProductInspect : Local

        data class AppServer(val action: io.github.amichne.kast.appserver.AppServerAction) : Local

        data object BrokerServe : Local

        data object CodexCli : Local

        data object CodexDesktop : Local
    }

    data class Semantic(
        val request: PreparedCliRequest,
    ) : CliAction

    sealed interface Lifecycle : CliAction {
        val command: CliLifecycleCommand

        data class Start(
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

    }
}

/** Closed domain failures produced after Clikt has refined individual option values. */
sealed interface CliUsageFailure {
    enum class Start : CliUsageFailure {
        OPTIONS_REQUIRE_SEED,
    }

    enum class RequestDocument : CliUsageFailure {
        REQUIRED,
        REJECTED,
    }
}

internal fun CliUsageFailure.message(): String = when (this) {
    CliUsageFailure.Start.OPTIONS_REQUIRE_SEED ->
        "--source-idea-system and --accept-global-index-copy require --cache seed"
    CliUsageFailure.RequestDocument.REQUIRED ->
        "semantic commands read one canonical JSON request document from standard input"
    CliUsageFailure.RequestDocument.REJECTED ->
        "standard input must be one canonical, bounded request document for this operation"
}

internal sealed interface CliRequestDocumentInput {
    data object Absent : CliRequestDocumentInput
    data object Rejected : CliRequestDocumentInput
    data class Provided(val document: String) : CliRequestDocumentInput
    data class Deferred(
        val read: () -> CliRequestDocumentInput,
    ) : CliRequestDocumentInput
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

internal class SemanticKastCommand<Request : OperationRequest>(
    name: String,
    val operation: CanonicalOperation,
    val schemaUsage: String,
    private val description: String,
    private val serializer: KSerializer<Request>,
    private val requestInput: CliRequestDocumentInput,
    private val preparer: CliRequestPreparer<Request>,
) : KastCommand(name) {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = description

    override fun resolveAction(): CliActionResolution = when (requestInput) {
        CliRequestDocumentInput.Absent -> CliActionResolution.UsageRejected(
            CliUsageFailure.RequestDocument.REQUIRED,
        )
        CliRequestDocumentInput.Rejected -> CliActionResolution.UsageRejected(
            CliUsageFailure.RequestDocument.REJECTED,
        )
        is CliRequestDocumentInput.Provided -> decode(requestInput.document)
        is CliRequestDocumentInput.Deferred -> when (val supplied = requestInput.read()) {
            is CliRequestDocumentInput.Deferred -> CliActionResolution.UsageRejected(
                CliUsageFailure.RequestDocument.REJECTED,
            )
            else -> resolve(supplied)
        }
    }

    private fun resolve(input: CliRequestDocumentInput): CliActionResolution = when (input) {
        CliRequestDocumentInput.Absent -> CliActionResolution.UsageRejected(
            CliUsageFailure.RequestDocument.REQUIRED,
        )
        CliRequestDocumentInput.Rejected -> CliActionResolution.UsageRejected(
            CliUsageFailure.RequestDocument.REJECTED,
        )
        is CliRequestDocumentInput.Provided -> decode(input.document)
        is CliRequestDocumentInput.Deferred -> CliActionResolution.UsageRejected(
            CliUsageFailure.RequestDocument.REJECTED,
        )
    }

    private fun decode(document: String): CliActionResolution {
        val request = try {
            requestJson.decodeFromString(serializer, document)
        } catch (_: SerializationException) {
            return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
        } catch (_: IllegalArgumentException) {
            return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
        }
        return prepare(request)
    }

    private fun prepare(request: Request): CliActionResolution =
        when (val preparation = preparer.prepare(request)) {
            is CliProjectionPreparation.Prepared -> CliActionResolution.Selected(
                CliAction.Semantic(preparation.request),
            )
            is CliProjectionPreparation.Rejected -> CliActionResolution.ProjectionRejected(
                preparation.failure,
            )
        }
}

private val requestJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

internal abstract class LocalKastCommand(
    name: String,
    val command: CliProductCommand,
) : KastCommand(name)

internal class LocalCommandFamily(
    val root: KastCommand,
    val commands: List<LocalKastCommand>,
)
