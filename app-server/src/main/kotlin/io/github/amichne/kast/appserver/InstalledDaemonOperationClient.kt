package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

sealed interface DaemonOperationCall {
    data class PublicTool(val tool: AdmittedPublicTool) : DaemonOperationCall

    data class Canonical(val read: DaemonCanonicalRead) : DaemonOperationCall

    data class Change(val action: DaemonChangeAction) : DaemonOperationCall
}

/** The CLI's semantic capability; a rejection never causes a direct IDE invocation. */
fun interface DaemonOperationClient {
    fun read(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult
}

sealed interface DaemonOperationResult {
    data class Complete(val document: CanonicalJsonDocument) : DaemonOperationResult

    data class Qualified(val document: CanonicalJsonDocument) : DaemonOperationResult

    data class OperationRejected(val document: CanonicalJsonDocument) : DaemonOperationResult

    data class Hosted(val document: CanonicalJsonDocument) : DaemonOperationResult

    data class Rejected(val failure: DaemonOperationClientRejection) : DaemonOperationResult
}

sealed interface DaemonOperationClientRejection {
    data class Server(val failure: DaemonOperationFailure) : DaemonOperationClientRejection

    data class Command(val failure: PersistentBrokerServiceFailure) : DaemonOperationClientRejection

    data class Coordinator(val failure: WorkerControlFailure) : DaemonOperationClientRejection

    data class Transport(val failure: DaemonOperationClientFailure) : DaemonOperationClientRejection
}

enum class DaemonOperationClientFailure {
    CODE_SOURCE_UNAVAILABLE,
    CODE_SOURCE_INVALID,
    LIBRARY_DIRECTORY_INVALID,
    PRODUCT_ROOT_UNAVAILABLE,
    RESOURCE_DIRECTORY_UNAVAILABLE,
    KAST_EXECUTABLE_UNAVAILABLE,
    UNAVAILABLE,
    OUTCOME_UNOBSERVED,
    RESPONSE_REJECTED,
}

class InstalledDaemonOperationClient(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String, String>,
) : DaemonOperationClient {
    override fun read(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult = runBlocking {
        executeInstalled(root, call)
    }

    private suspend fun executeInstalled(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult {
        val command =
            when (val resolved = BrokerServiceDemandContext.resolveCommand(kast, userHome, environment)) {
                is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
                is BrokerServiceLaunchCommandResolution.Rejected ->
                    return DaemonOperationResult.Rejected(DaemonOperationClientRejection.Command(resolved.failure))
            }
        val target =
            when (val status = InstalledCoordinatorClient(kast).status(command)) {
                is CoordinatorStatusRead.Observed -> DaemonManagementTarget.from(status.snapshot)
                is CoordinatorStatusRead.Rejected ->
                    return DaemonOperationResult.Rejected(DaemonOperationClientRejection.Coordinator(status.failure))
            }
        val selection =
            when (call) {
                is DaemonOperationCall.PublicTool ->
                    DaemonOperationSelection.PublicTool(
                        DaemonOperationTool.from(call.tool.identity),
                        PublicToolContract.encode(call.tool),
                    )
                is DaemonOperationCall.Canonical -> DaemonOperationSelection.Canonical(call.read)
                is DaemonOperationCall.Change -> DaemonOperationSelection.Change(call.action)
            }
        val operation: CanonicalOperation =
            when (call) {
                is DaemonOperationCall.PublicTool -> call.tool.identity.operation
                is DaemonOperationCall.Canonical -> call.read.operation()
                is DaemonOperationCall.Change -> call.action.operation()
            }
        val request = DaemonOperationRequest(target, root.path.toString(), selection)
        val encoded = DaemonOperationProtocol.json.encodeToString(DaemonOperationRequest.serializer(), request)
        if (encoded.toByteArray().size > DaemonOperationProtocol.maximumRequestBytes)
            return rejected(DaemonOperationClientFailure.RESPONSE_REJECTED)
        return exchange(
            command.publicSocket,
            encoded,
            target,
            root,
            OperationExecutionBudget.forOperation(operation).invocation.value,
            call is DaemonOperationCall.Change && call.action is DaemonChangeAction.Prepare,
        )
    }

    private suspend fun exchange(
        socket: Path,
        encoded: String,
        target: DaemonManagementTarget,
        root: CanonicalRoot,
        timeoutMillis: Long,
        allowHosted: Boolean,
    ): DaemonOperationResult =
        withTimeoutOrNull(timeoutMillis) {
            val connection =
                when (
                    val connected =
                        connectCodexUnixWebSocket(
                            socket,
                            DaemonOperationProtocol.maximumResponseBytes,
                            BrokerOperationalLimits.managementConnect.value,
                            BrokerControlRoute.OPERATION,
                        )
                ) {
                    is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                    BrokerUpstreamConnectionAdmission.Rejected ->
                        return@withTimeoutOrNull rejected(DaemonOperationClientFailure.UNAVAILABLE)
                }
            try {
                if (connection.send(encoded) != BrokerUpstreamSend.SENT)
                    return@withTimeoutOrNull rejected(DaemonOperationClientFailure.OUTCOME_UNOBSERVED)
                val frame = connection.receive()
                if (frame !is BrokerUpstreamFrame.Text)
                    return@withTimeoutOrNull rejected(DaemonOperationClientFailure.OUTCOME_UNOBSERVED)
                val response =
                    try {
                        DaemonOperationProtocol.json.decodeFromString<DaemonOperationResponse>(frame.message)
                    } catch (_: SerializationException) {
                        return@withTimeoutOrNull rejected(DaemonOperationClientFailure.RESPONSE_REJECTED)
                    }
                admitOperationResponse(response, target, root, allowHosted)
            } finally {
                withContext(NonCancellable) { connection.close() }
            }
        } ?: rejected(DaemonOperationClientFailure.OUTCOME_UNOBSERVED)
}

internal fun admitOperationResponse(
    response: DaemonOperationResponse,
    target: DaemonManagementTarget,
    root: CanonicalRoot,
    allowHosted: Boolean = false,
): DaemonOperationResult {
    if (!response.matches(target, root)) return rejected(DaemonOperationClientFailure.RESPONSE_REJECTED)
    if (response is DaemonOperationResponse.Hosted && !allowHosted)
        return rejected(DaemonOperationClientFailure.RESPONSE_REJECTED)
    if (allowHosted && response !is DaemonOperationResponse.Hosted && response !is DaemonOperationResponse.Rejected)
        return rejected(DaemonOperationClientFailure.RESPONSE_REJECTED)
    fun document(value: JsonElement): CanonicalJsonDocument =
        CanonicalJsonDocument.generated(JsonElement.serializer()).create(value)
    return when (response) {
        is DaemonOperationResponse.Rejected ->
            DaemonOperationResult.Rejected(DaemonOperationClientRejection.Server(response.failure))
        is DaemonOperationResponse.Complete -> DaemonOperationResult.Complete(document(response.document))
        is DaemonOperationResponse.Qualified -> DaemonOperationResult.Qualified(document(response.document))
        is DaemonOperationResponse.OperationRejected ->
            DaemonOperationResult.OperationRejected(document(response.document))
        is DaemonOperationResponse.Hosted -> DaemonOperationResult.Hosted(document(response.document))
    }
}

private fun DaemonOperationResponse.matches(target: DaemonManagementTarget, root: CanonicalRoot): Boolean =
    when (this) {
        is DaemonOperationResponse.Rejected -> true
        is DaemonOperationResponse.Complete -> this.target == target && this.root == root.path.toString()
        is DaemonOperationResponse.Qualified -> this.target == target && this.root == root.path.toString()
        is DaemonOperationResponse.OperationRejected -> this.target == target && this.root == root.path.toString()
        is DaemonOperationResponse.Hosted -> this.target == target && this.root == root.path.toString()
    }

private fun rejected(failure: DaemonOperationClientFailure) =
    DaemonOperationResult.Rejected(DaemonOperationClientRejection.Transport(failure))

fun DaemonOperationClientRejection.diagnosticCode(): String =
    when (this) {
            is DaemonOperationClientRejection.Command -> "daemon-operation-command-${failure.name}"
            is DaemonOperationClientRejection.Coordinator -> "daemon-operation-coordinator-${failure.name}"
            is DaemonOperationClientRejection.Transport -> "daemon-operation-${failure.name}"
            is DaemonOperationClientRejection.Server ->
                when (val reason = failure) {
                    is DaemonOperationFailure.Protocol -> "daemon-operation-${reason.reason.name}"
                    is DaemonOperationFailure.Root -> "daemon-operation-root-${reason.reason.name}"
                    is DaemonOperationFailure.Input ->
                        when (val input = reason.reason) {
                            DaemonOperationInputFailure.SchemaMismatch -> "daemon-operation-schema-mismatch"
                            DaemonOperationInputFailure.SchemaRejected -> "daemon-operation-schema-rejected"
                            DaemonOperationInputFailure.SyntaxRejected -> "daemon-operation-syntax-rejected"
                            is DaemonOperationInputFailure.Parameter ->
                                "daemon-operation-${input.parameter.name}-${input.rule.name}"
                        }
                    is DaemonOperationFailure.Preparation -> "daemon-operation-preparation-${reason.reason.name}"
                    is DaemonOperationFailure.Workspace ->
                        when (val cause = reason.cause) {
                            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Preparation ->
                                "daemon-operation-preparation-${cause.failure.name}"
                            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Lifecycle ->
                                "daemon-operation-lifecycle-${cause.reason.name}"
                            io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.HostChanged ->
                                "daemon-operation-host-changed"
                        }
                    is DaemonOperationFailure.Host -> "daemon-operation-host-${reason.reason.name}"
                }
        }
        .lowercase()
        .replace('_', '-')
