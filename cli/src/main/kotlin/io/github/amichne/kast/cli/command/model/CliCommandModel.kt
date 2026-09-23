package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.BaseCliktCommand
import io.github.amichne.kast.appserver.DaemonCanonicalRead
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.explanation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.OperationProjectionFailure
import io.github.amichne.kast.protocol.wire.presentation.OperationRequestPreparer
import io.github.amichne.kast.protocol.wire.presentation.PreparedOperationRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class CliLocalMetadataCommand {
    VERSION,
    SCHEMA,
}

enum class CliProductCommand(val usage: String) {
    APP_SERVER_STATUS("app-server status"),
    INSPECT("product inspect"),
    CODEX_CLI("codex"),
    CODEX_DESKTOP("codex desktop"),
    KNOWLEDGE("knowledge <query-or-resource>"),
    IDE_STATUS("ide status [--root <path>]"),
}

/** One fully refined action selected by the public command graph. */
sealed interface CliAction {
    sealed interface Local : CliAction {
        data class Metadata(val command: CliLocalMetadataCommand) : Local

        data object Inspect : Local

        data object ProductInspect : Local

        data class Knowledge(val selection: io.github.amichne.kast.cli.knowledge.KnowledgeSelection) : Local

        data class AppServer(val action: io.github.amichne.kast.appserver.AppServerAction) : Local

        data object CodexCli : Local

        data object CodexDesktop : Local

        data class ExistingIde(
            val operation: io.github.amichne.kast.appserver.ide.ExistingIdeOperation,
            val root: io.github.amichne.kast.cli.command.ide.ExistingIdeRootSelection,
        ) : Local
    }

    data class Semantic(
        val request: PreparedOperationRequest,
        val source: SemanticSource = SemanticSource.Canonical,
    ) : CliAction
}

sealed interface SemanticSource {
    data object Canonical : SemanticSource

    data class PublicTool(val tool: AdmittedPublicTool) : SemanticSource

    data class CanonicalRead(val read: DaemonCanonicalRead) : SemanticSource

    data class ChangePlan(val request: ChangePlanRequest) : SemanticSource

    data class ChangeApply(val request: ChangeApplyRequest) : SemanticSource

    data class ChangeRecover(val request: ChangeRecoverRequest) : SemanticSource
}

/** Closed domain failures produced after Clikt has refined individual option values. */
sealed interface CliUsageFailure {
    data class PublicTool(val failure: io.github.amichne.kast.appserver.query.PublicToolInputFailure) : CliUsageFailure

    enum class RequestDocument : CliUsageFailure {
        REQUIRED,
        REJECTED,
    }
}

internal fun CliUsageFailure.message(): String =
    when (this) {
        is CliUsageFailure.PublicTool -> failure.explanation()
        CliUsageFailure.RequestDocument.REQUIRED ->
            "semantic commands read one canonical JSON request document from standard input"
        CliUsageFailure.RequestDocument.REJECTED ->
            "standard input must be one canonical, bounded request document for this operation"
    }

internal sealed interface CliRequestDocumentInput {
    data object Absent : CliRequestDocumentInput

    data object Rejected : CliRequestDocumentInput

    data class Provided(val document: String) : CliRequestDocumentInput

    data class Deferred(val read: () -> CliRequestDocumentInput) : CliRequestDocumentInput
}

internal sealed interface CliNodeResolution {
    data object NoAction : CliNodeResolution
}

internal sealed interface CliActionResolution : CliNodeResolution {
    data class SourceRejected(val failure: io.github.amichne.kast.protocol.contract.SourceReadCause) :
        CliActionResolution

    data class Selected(val action: CliAction) : CliActionResolution

    data class UsageRejected(val failure: CliUsageFailure) : CliActionResolution

    data class ProjectionRejected(val failure: OperationProjectionFailure) : CliActionResolution
}

/** One Clikt node whose only result is a typed CLI action resolution. */
internal abstract class KastCommand(name: String) : BaseCliktCommand<KastCommand>(name) {
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
    private val preparer: OperationRequestPreparer<Request>,
    private val source: (Request) -> SemanticSource = { SemanticSource.Canonical },
) : KastCommand(name) {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = description

    override fun resolveAction(): CliActionResolution =
        when (requestInput) {
            CliRequestDocumentInput.Absent ->
                CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REQUIRED)
            CliRequestDocumentInput.Rejected ->
                CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
            is CliRequestDocumentInput.Provided -> decode(requestInput.document)
            is CliRequestDocumentInput.Deferred ->
                when (val supplied = requestInput.read()) {
                    is CliRequestDocumentInput.Deferred ->
                        CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
                    else -> resolve(supplied)
                }
        }

    private fun resolve(input: CliRequestDocumentInput): CliActionResolution =
        when (input) {
            CliRequestDocumentInput.Absent ->
                CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REQUIRED)
            CliRequestDocumentInput.Rejected ->
                CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
            is CliRequestDocumentInput.Provided -> decode(input.document)
            is CliRequestDocumentInput.Deferred ->
                CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
        }

    private fun decode(document: String): CliActionResolution {
        val request =
            try {
                requestJson.decodeFromString(serializer, document)
            } catch (failure: io.github.amichne.kast.protocol.contract.SourceRequestSerializationException) {
                return CliActionResolution.SourceRejected(failure.failure)
            } catch (failure: io.github.amichne.kast.appserver.query.PublicToolSerializationException) {
                return CliActionResolution.UsageRejected(CliUsageFailure.PublicTool(failure.failure))
            } catch (_: SerializationException) {
                if (operation == CanonicalOperation.SOURCE_READ)
                    return CliActionResolution.SourceRejected(
                        io.github.amichne.kast.protocol.contract.SourceReadFailureDetail.RequestRejected(
                            io.github.amichne.kast.protocol.contract.SourceRequestField(
                                io.github.amichne.kast.protocol.contract.SourceRequestPath.DOCUMENT
                            ),
                            io.github.amichne.kast.protocol.contract.SourceRequestRule.INVALID_JSON,
                        )
                    )
                return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
            } catch (_: IllegalArgumentException) {
                return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
            }
        return prepare(request)
    }

    private fun prepare(request: Request): CliActionResolution =
        when (val preparation = preparer.prepare(request)) {
            is OperationPreparation.Prepared ->
                CliActionResolution.Selected(CliAction.Semantic(preparation.request, source(request)))
            is OperationPreparation.Rejected -> CliActionResolution.ProjectionRejected(preparation.failure)
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
