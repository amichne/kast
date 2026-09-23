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
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/** The CLI's read capability; a rejection never causes a direct IDE invocation. */
fun interface DaemonReadClient {
    fun read(root: CanonicalRoot, tool: AdmittedPublicTool): DaemonReadResult
}

sealed interface DaemonReadResult {
    data class Complete(val document: CanonicalJsonDocument) : DaemonReadResult

    data class Qualified(val document: CanonicalJsonDocument) : DaemonReadResult

    data class OperationRejected(val document: CanonicalJsonDocument) : DaemonReadResult

    data class Rejected(val failure: DaemonReadClientRejection) : DaemonReadResult
}

sealed interface DaemonReadClientRejection {
    data class Server(val failure: DaemonReadFailure) : DaemonReadClientRejection

    data class Command(val failure: PersistentBrokerServiceFailure) : DaemonReadClientRejection

    data class Coordinator(val failure: WorkerControlFailure) : DaemonReadClientRejection

    data class Transport(val failure: DaemonReadClientFailure) : DaemonReadClientRejection
}

enum class DaemonReadClientFailure {
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

class InstalledDaemonReadClient(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String, String>,
) : DaemonReadClient {
    override fun read(root: CanonicalRoot, tool: AdmittedPublicTool): DaemonReadResult = runBlocking {
        readInstalled(root, tool)
    }

    private suspend fun readInstalled(root: CanonicalRoot, tool: AdmittedPublicTool): DaemonReadResult {
        val command =
            when (val resolved = BrokerServiceDemandContext.resolveCommand(kast, userHome, environment)) {
                is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
                is BrokerServiceLaunchCommandResolution.Rejected ->
                    return DaemonReadResult.Rejected(DaemonReadClientRejection.Command(resolved.failure))
            }
        val target =
            when (val status = InstalledCoordinatorClient(kast).status(command)) {
                is CoordinatorStatusRead.Observed -> DaemonManagementTarget.from(status.snapshot)
                is CoordinatorStatusRead.Rejected ->
                    return DaemonReadResult.Rejected(DaemonReadClientRejection.Coordinator(status.failure))
            }
        val request =
            DaemonReadRequest(
                target,
                root.path.toString(),
                DaemonReadTool.from(tool.identity),
                PublicToolContract.encode(tool),
            )
        val encoded = DaemonReadProtocol.json.encodeToString(DaemonReadRequest.serializer(), request)
        if (encoded.toByteArray().size > DaemonReadProtocol.maximumRequestBytes)
            return rejected(DaemonReadClientFailure.RESPONSE_REJECTED)
        return exchange(
            command.publicSocket,
            encoded,
            target,
            root,
            OperationExecutionBudget.forOperation(tool.identity.operation).invocation.value,
        )
    }

    private suspend fun exchange(
        socket: Path,
        encoded: String,
        target: DaemonManagementTarget,
        root: CanonicalRoot,
        timeoutMillis: Long,
    ): DaemonReadResult =
        withTimeoutOrNull(timeoutMillis) {
            val connection =
                when (
                    val connected =
                        connectCodexUnixWebSocket(
                            socket,
                            DaemonReadProtocol.maximumResponseBytes,
                            BrokerOperationalLimits.managementConnect.value,
                            BrokerControlRoute.READ,
                        )
                ) {
                    is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                    BrokerUpstreamConnectionAdmission.Rejected ->
                        return@withTimeoutOrNull rejected(DaemonReadClientFailure.UNAVAILABLE)
                }
            try {
                if (connection.send(encoded) != BrokerUpstreamSend.SENT)
                    return@withTimeoutOrNull rejected(DaemonReadClientFailure.OUTCOME_UNOBSERVED)
                val frame = connection.receive()
                if (frame !is BrokerUpstreamFrame.Text)
                    return@withTimeoutOrNull rejected(DaemonReadClientFailure.OUTCOME_UNOBSERVED)
                val response =
                    try {
                        DaemonReadProtocol.json.decodeFromString<DaemonReadResponse>(frame.message)
                    } catch (_: SerializationException) {
                        return@withTimeoutOrNull rejected(DaemonReadClientFailure.RESPONSE_REJECTED)
                    }
                admitQueryResponse(response, target, root)
            } finally {
                withContext(NonCancellable) { connection.close() }
            }
        } ?: rejected(DaemonReadClientFailure.OUTCOME_UNOBSERVED)
}

internal fun admitQueryResponse(
    response: DaemonReadResponse,
    target: DaemonManagementTarget,
    root: CanonicalRoot,
): DaemonReadResult {
    if (!response.matches(target, root)) return rejected(DaemonReadClientFailure.RESPONSE_REJECTED)
    fun document(value: JsonElement): CanonicalJsonDocument =
        CanonicalJsonDocument.generated(JsonElement.serializer()).create(value)
    return when (response) {
        is DaemonReadResponse.Rejected -> DaemonReadResult.Rejected(DaemonReadClientRejection.Server(response.failure))
        is DaemonReadResponse.Complete -> DaemonReadResult.Complete(document(response.document))
        is DaemonReadResponse.Qualified -> DaemonReadResult.Qualified(document(response.document))
        is DaemonReadResponse.OperationRejected -> DaemonReadResult.OperationRejected(document(response.document))
    }
}

private fun DaemonReadResponse.matches(target: DaemonManagementTarget, root: CanonicalRoot): Boolean =
    when (this) {
        is DaemonReadResponse.Rejected -> true
        is DaemonReadResponse.Complete -> this.target == target && this.root == root.path.toString()
        is DaemonReadResponse.Qualified -> this.target == target && this.root == root.path.toString()
        is DaemonReadResponse.OperationRejected -> this.target == target && this.root == root.path.toString()
    }

private fun rejected(failure: DaemonReadClientFailure) =
    DaemonReadResult.Rejected(DaemonReadClientRejection.Transport(failure))

fun DaemonReadClientRejection.diagnosticCode(): String =
    when (this) {
            is DaemonReadClientRejection.Command -> "daemon-read-command-${failure.name}"
            is DaemonReadClientRejection.Coordinator -> "daemon-read-coordinator-${failure.name}"
            is DaemonReadClientRejection.Transport -> "daemon-read-${failure.name}"
            is DaemonReadClientRejection.Server ->
                when (val reason = failure) {
                    is DaemonReadFailure.Protocol -> "daemon-read-${reason.reason.name}"
                    is DaemonReadFailure.Root -> "daemon-read-root-${reason.reason.name}"
                    is DaemonReadFailure.Input ->
                        when (val input = reason.reason) {
                            DaemonReadInputFailure.SchemaMismatch -> "daemon-read-schema-mismatch"
                            DaemonReadInputFailure.SchemaRejected -> "daemon-read-schema-rejected"
                            DaemonReadInputFailure.SyntaxRejected -> "daemon-read-syntax-rejected"
                            is DaemonReadInputFailure.Parameter ->
                                "daemon-read-${input.parameter.name}-${input.rule.name}"
                        }
                    is DaemonReadFailure.Preparation -> "daemon-read-preparation-${reason.reason.name}"
                    is DaemonReadFailure.Workspace ->
                        when (val cause = reason.cause) {
                            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Preparation ->
                                "daemon-read-preparation-${cause.failure.name}"
                            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Lifecycle ->
                                "daemon-read-lifecycle-${cause.reason.name}"
                            io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.HostChanged ->
                                "daemon-read-host-changed"
                        }
                    is DaemonReadFailure.Host -> "daemon-read-host-${reason.reason.name}"
                }
        }
        .lowercase()
        .replace('_', '-')
