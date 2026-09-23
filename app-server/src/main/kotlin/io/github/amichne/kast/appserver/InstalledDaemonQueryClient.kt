package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/** The CLI's read capability; a rejection never causes a direct IDE invocation. */
fun interface DaemonQueryClient {
    fun query(root: CanonicalRoot, tool: AdmittedPublicTool): DaemonQueryResult
}

sealed interface DaemonQueryResult {
    data class Complete(val document: CanonicalJsonDocument) : DaemonQueryResult

    data class Qualified(val document: CanonicalJsonDocument) : DaemonQueryResult

    data class OperationRejected(val document: CanonicalJsonDocument) : DaemonQueryResult

    data class Rejected(val failure: DaemonQueryClientRejection) : DaemonQueryResult
}

sealed interface DaemonQueryClientRejection {
    data class Server(val failure: DaemonQueryFailure) : DaemonQueryClientRejection

    data class Command(val failure: PersistentBrokerServiceFailure) : DaemonQueryClientRejection

    data class Coordinator(val failure: WorkerControlFailure) : DaemonQueryClientRejection

    data class Transport(val failure: DaemonQueryClientFailure) : DaemonQueryClientRejection
}

enum class DaemonQueryClientFailure {
    TOOL_UNSUPPORTED,
    EXECUTABLE_UNAVAILABLE,
    UNAVAILABLE,
    OUTCOME_UNOBSERVED,
    RESPONSE_REJECTED,
}

class InstalledDaemonQueryClient(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String, String>,
) : DaemonQueryClient {
    override fun query(root: CanonicalRoot, tool: AdmittedPublicTool): DaemonQueryResult = runBlocking {
        queryInstalled(root, tool)
    }

    private suspend fun queryInstalled(root: CanonicalRoot, tool: AdmittedPublicTool): DaemonQueryResult {
        if (tool.identity != PublicToolIdentity.QUERY_SYMBOLS)
            return rejected(DaemonQueryClientFailure.TOOL_UNSUPPORTED)
        val command =
            when (val resolved = BrokerServiceDemandContext.resolveCommand(kast, userHome, environment)) {
                is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
                is BrokerServiceLaunchCommandResolution.Rejected ->
                    return DaemonQueryResult.Rejected(DaemonQueryClientRejection.Command(resolved.failure))
            }
        val target =
            when (val status = InstalledCoordinatorClient(kast).status(command)) {
                is CoordinatorStatusRead.Observed -> DaemonManagementTarget.from(status.snapshot)
                is CoordinatorStatusRead.Rejected ->
                    return DaemonQueryResult.Rejected(DaemonQueryClientRejection.Coordinator(status.failure))
            }
        val request = DaemonQueryRequest(target, root.path.toString(), PublicToolContract.encode(tool))
        val encoded = DaemonQueryProtocol.json.encodeToString(DaemonQueryRequest.serializer(), request)
        if (encoded.toByteArray().size > DaemonQueryProtocol.maximumRequestBytes)
            return rejected(DaemonQueryClientFailure.RESPONSE_REJECTED)
        return exchange(command.publicSocket, encoded, target, root)
    }

    private suspend fun exchange(
        socket: Path,
        encoded: String,
        target: DaemonManagementTarget,
        root: CanonicalRoot,
    ): DaemonQueryResult =
        withTimeoutOrNull(OperationExecutionBudget.SEMANTIC_READ.invocation.value) {
            val connection =
                when (
                    val connected =
                        connectCodexUnixWebSocket(
                            socket,
                            DaemonQueryProtocol.maximumResponseBytes,
                            BrokerOperationalLimits.managementConnect.value,
                            BrokerControlRoute.QUERY,
                        )
                ) {
                    is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                    BrokerUpstreamConnectionAdmission.Rejected ->
                        return@withTimeoutOrNull rejected(DaemonQueryClientFailure.UNAVAILABLE)
                }
            try {
                if (connection.send(encoded) != BrokerUpstreamSend.SENT)
                    return@withTimeoutOrNull rejected(DaemonQueryClientFailure.OUTCOME_UNOBSERVED)
                val frame = connection.receive()
                if (frame !is BrokerUpstreamFrame.Text)
                    return@withTimeoutOrNull rejected(DaemonQueryClientFailure.OUTCOME_UNOBSERVED)
                val response =
                    try {
                        DaemonQueryProtocol.json.decodeFromString<DaemonQueryResponse>(frame.message)
                    } catch (_: SerializationException) {
                        return@withTimeoutOrNull rejected(DaemonQueryClientFailure.RESPONSE_REJECTED)
                    }
                admitQueryResponse(response, target, root)
            } finally {
                withContext(NonCancellable) { connection.close() }
            }
        } ?: rejected(DaemonQueryClientFailure.OUTCOME_UNOBSERVED)
}

internal fun admitQueryResponse(
    response: DaemonQueryResponse,
    target: DaemonManagementTarget,
    root: CanonicalRoot,
): DaemonQueryResult {
    if (!response.matches(target, root)) return rejected(DaemonQueryClientFailure.RESPONSE_REJECTED)
    fun document(value: JsonElement): CanonicalJsonDocument =
        CanonicalJsonDocument.generated(JsonElement.serializer()).create(value)
    return when (response) {
        is DaemonQueryResponse.Rejected ->
            DaemonQueryResult.Rejected(DaemonQueryClientRejection.Server(response.failure))
        is DaemonQueryResponse.Complete -> DaemonQueryResult.Complete(document(response.document))
        is DaemonQueryResponse.Qualified -> DaemonQueryResult.Qualified(document(response.document))
        is DaemonQueryResponse.OperationRejected -> DaemonQueryResult.OperationRejected(document(response.document))
    }
}

private fun DaemonQueryResponse.matches(target: DaemonManagementTarget, root: CanonicalRoot): Boolean =
    when (this) {
        is DaemonQueryResponse.Rejected -> true
        is DaemonQueryResponse.Complete -> this.target == target && this.root == root.path.toString()
        is DaemonQueryResponse.Qualified -> this.target == target && this.root == root.path.toString()
        is DaemonQueryResponse.OperationRejected -> this.target == target && this.root == root.path.toString()
    }

private fun rejected(failure: DaemonQueryClientFailure) =
    DaemonQueryResult.Rejected(DaemonQueryClientRejection.Transport(failure))

fun DaemonQueryClientRejection.diagnosticCode(): String =
    when (this) {
            is DaemonQueryClientRejection.Command -> "daemon-query-command-${failure.name}"
            is DaemonQueryClientRejection.Coordinator -> "daemon-query-coordinator-${failure.name}"
            is DaemonQueryClientRejection.Transport -> "daemon-query-${failure.name}"
            is DaemonQueryClientRejection.Server ->
                when (val reason = failure) {
                    is DaemonQueryFailure.Protocol -> "daemon-query-${reason.reason.name}"
                    is DaemonQueryFailure.Root -> "daemon-query-root-${reason.reason.name}"
                    is DaemonQueryFailure.Input ->
                        when (val input = reason.reason) {
                            DaemonQueryInputFailure.SchemaMismatch -> "daemon-query-schema-mismatch"
                            DaemonQueryInputFailure.SchemaRejected -> "daemon-query-schema-rejected"
                            DaemonQueryInputFailure.SyntaxRejected -> "daemon-query-syntax-rejected"
                            is DaemonQueryInputFailure.Parameter ->
                                "daemon-query-${input.parameter.name}-${input.rule.name}"
                        }
                    is DaemonQueryFailure.Preparation -> "daemon-query-preparation-${reason.reason.name}"
                    is DaemonQueryFailure.Workspace ->
                        when (val cause = reason.cause) {
                            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Preparation ->
                                "daemon-query-preparation-${cause.failure.name}"
                            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Lifecycle ->
                                "daemon-query-lifecycle-${cause.reason.name}"
                            io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.HostChanged ->
                                "daemon-query-host-changed"
                        }
                    is DaemonQueryFailure.Host -> "daemon-query-host-${reason.reason.name}"
                }
        }
        .lowercase()
        .replace('_', '-')
