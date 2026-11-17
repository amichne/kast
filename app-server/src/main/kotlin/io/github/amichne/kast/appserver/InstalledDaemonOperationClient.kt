package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

/** The caller supplies the socket and target from the same admitted coordinator. */
internal class InstalledDaemonOperationClient(private val socket: Path, private val target: DaemonManagementTarget) {
    suspend fun exchange(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult {
        val selection =
            call.selection() ?: return protocolRejection(DaemonOperationProtocolFailure.OPERATION_UNSUPPORTED)
        val request = DaemonOperationRequest(target, root.path.toString(), selection)
        val message = DaemonOperationProtocol.json.encodeToString(DaemonOperationRequest.serializer(), request)
        if (message.toByteArray(Charsets.UTF_8).size > DaemonOperationProtocol.maximumRequestBytes)
            return protocolRejection(DaemonOperationProtocolFailure.CAPACITY_EXCEEDED)
        val connection =
            when (
                val connected =
                    connectCodexUnixWebSocket(
                        socket,
                        DaemonOperationProtocol.maximumResponseBytes,
                        BrokerOperationalLimits.workerControlHandshake.value,
                        BrokerControlRoute.SEMANTIC,
                    )
            ) {
                is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                BrokerUpstreamConnectionAdmission.Rejected -> return rejected(DaemonOperationClientFailure.UNAVAILABLE)
            }
        try {
            if (connection.send(message) != BrokerUpstreamSend.SENT)
                return rejected(DaemonOperationClientFailure.OUTCOME_UNOBSERVED)
            val frame =
                connection.receive() as? BrokerUpstreamFrame.Text
                    ?: return rejected(DaemonOperationClientFailure.OUTCOME_UNOBSERVED)
            val invalidReply =
                if (call is DaemonOperationCall.Change) DaemonOperationClientFailure.OUTCOME_UNOBSERVED
                else DaemonOperationClientFailure.RESPONSE_REJECTED
            val response =
                try {
                    DaemonOperationProtocol.json.decodeFromString<DaemonOperationResponse>(frame.message)
                } catch (_: SerializationException) {
                    return rejected(invalidReply)
                }
            val hosted = call is DaemonOperationCall.Canonical || call is DaemonOperationCall.Change
            val result = admitOperationResponse(response, target, root, allowHosted = hosted)
            return if (result == rejected(DaemonOperationClientFailure.RESPONSE_REJECTED)) rejected(invalidReply)
            else result
        } finally {
            withContext(NonCancellable) { connection.close() }
        }
    }
}

private fun DaemonOperationCall.selection(): DaemonOperationSelection? =
    when (this) {
        is DaemonOperationCall.PublicTool ->
            DaemonOperationTool.entries
                .find { it.identity == tool.identity }
                ?.let {
                    DaemonOperationSelection.PublicTool(it, PublicToolContract.encode(tool), tool.catalog)
                }
        is DaemonOperationCall.Canonical -> DaemonOperationSelection.Canonical(read)
        is DaemonOperationCall.Change -> DaemonOperationSelection.Change(action)
    }

private fun protocolRejection(reason: DaemonOperationProtocolFailure) =
    DaemonOperationResult.Rejected(DaemonOperationClientRejection.Server(DaemonOperationFailure.Protocol(reason)))

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
                            is DaemonOperationInputFailure.Constraint -> "daemon-operation-${input.rule.name}"
                            is DaemonOperationInputFailure.SourceAnchor -> "daemon-operation-${input.cause.name}"
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
