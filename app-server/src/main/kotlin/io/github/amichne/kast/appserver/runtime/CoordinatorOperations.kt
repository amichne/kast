package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.CoordinatorOperationExecutor
import io.github.amichne.kast.appserver.DaemonManagementTarget
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationInputFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocol
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationRequest
import io.github.amichne.kast.appserver.DaemonOperationResponse
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.DaemonOperationSelection
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.CanonicalRootFailure
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** One request per connection. A disconnect cancels that request's execution. */
internal class CoordinatorOperations(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val executor: CoordinatorOperationExecutor?,
) {
    suspend fun handle(session: DefaultWebSocketServerSession) {
        val frame =
            withTimeoutOrNull(BrokerOperationalLimits.workerControlHandshake.value) {
                session.incoming.receiveCatching().getOrNull()
            } ?: return
        val response =
            coroutineScope {
                val invocation = async { exchange(frame) }
                try {
                    select<DaemonOperationResponse?> {
                        invocation.onAwait { it }
                        session.incoming.onReceiveCatching { null }
                    }
                } finally {
                    invocation.cancel()
                }
            } ?: return
        val encoded = encode(response)
        session.send(
            if (encoded.toByteArray(Charsets.UTF_8).size <= DaemonOperationProtocol.maximumResponseBytes) encoded
            else encode(reject(DaemonOperationProtocolFailure.OUTCOME_UNOBSERVED))
        )
    }

    private suspend fun exchange(frame: Frame): DaemonOperationResponse {
        val text = (frame as? Frame.Text)?.readText() ?: return reject(DaemonOperationProtocolFailure.INVALID_REQUEST)
        if (text.toByteArray(Charsets.UTF_8).size > DaemonOperationProtocol.maximumRequestBytes)
            return reject(DaemonOperationProtocolFailure.CAPACITY_EXCEEDED)
        val request =
            try {
                DaemonOperationProtocol.json.decodeFromString<DaemonOperationRequest>(text)
            } catch (_: SerializationException) {
                return reject(DaemonOperationProtocolFailure.INVALID_REQUEST)
            }
        if (request.version != DaemonOperationProtocol.version)
            return reject(DaemonOperationProtocolFailure.UNSUPPORTED_VERSION)
        if (request.target != target) return reject(DaemonOperationProtocolFailure.IDENTITY_REJECTED)
        val execute = executor ?: return reject(DaemonOperationProtocolFailure.UNAVAILABLE)
        val root =
            when (val admitted = exactRoot(request.root)) {
                is Refinement.Rejected ->
                    return DaemonOperationResponse.Rejected(DaemonOperationFailure.Root(admitted.failure))
                is Refinement.Refined -> admitted.value
            }
        val call =
            when (val admitted = request.selection.admit()) {
                is Refinement.Rejected ->
                    return DaemonOperationResponse.Rejected(DaemonOperationFailure.Input(admitted.failure))
                is Refinement.Refined -> admitted.value
            }
        if (!available()) return reject(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
        return execute.execute(root, call).response(root)
    }

    private fun DaemonOperationResult.response(root: CanonicalRoot): DaemonOperationResponse {
        fun CanonicalJsonDocument.asElement() = DaemonOperationProtocol.json.parseToJsonElement(value)
        val path = root.path.toString()
        return when (this) {
            is DaemonOperationResult.Complete -> DaemonOperationResponse.Complete(target, path, document.asElement())
            is DaemonOperationResult.Qualified -> DaemonOperationResponse.Qualified(target, path, document.asElement())
            is DaemonOperationResult.Hosted -> DaemonOperationResponse.Hosted(target, path, document.asElement())
            is DaemonOperationResult.OperationRejected ->
                DaemonOperationResponse.OperationRejected(target, path, document.asElement())
            is DaemonOperationResult.Rejected ->
                when (val reason = failure) {
                    is DaemonOperationClientRejection.Server -> DaemonOperationResponse.Rejected(reason.failure)
                    is DaemonOperationClientRejection.Transport ->
                        reject(
                            when (reason.failure) {
                                DaemonOperationClientFailure.OUTCOME_UNOBSERVED ->
                                    DaemonOperationProtocolFailure.OUTCOME_UNOBSERVED
                                DaemonOperationClientFailure.RESPONSE_REJECTED ->
                                    DaemonOperationProtocolFailure.RESPONSE_REJECTED
                                else -> DaemonOperationProtocolFailure.UNAVAILABLE
                            }
                        )
                    is DaemonOperationClientRejection.Command,
                    is DaemonOperationClientRejection.Coordinator -> reject(DaemonOperationProtocolFailure.UNAVAILABLE)
                }
        }
    }
}

private fun encode(response: DaemonOperationResponse): String =
    DaemonOperationProtocol.json.encodeToString(DaemonOperationResponse.serializer(), response)

private fun reject(reason: DaemonOperationProtocolFailure) =
    DaemonOperationResponse.Rejected(DaemonOperationFailure.Protocol(reason))

private fun exactRoot(raw: String): Refinement<CanonicalRoot, CanonicalRootFailure> {
    val path =
        try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            return Refinement.Rejected(CanonicalRootFailure.START_UNAVAILABLE)
        }
    if (!path.isAbsolute || path.normalize() != path) return Refinement.Rejected(CanonicalRootFailure.START_UNAVAILABLE)
    return when (val discovered = FilesystemCanonicalRootDiscovery.discover(path)) {
        is CanonicalRootDiscovery.Rejected -> Refinement.Rejected(discovered.failure)
        is CanonicalRootDiscovery.Discovered ->
            if (discovered.root.path == path) Refinement.Refined(discovered.root)
            else Refinement.Rejected(CanonicalRootFailure.START_UNAVAILABLE)
    }
}

private fun DaemonOperationSelection.admit(): Refinement<DaemonOperationCall, DaemonOperationInputFailure> =
    when (this) {
        is DaemonOperationSelection.Canonical -> Refinement.Refined(DaemonOperationCall.Canonical(read))
        is DaemonOperationSelection.Change -> Refinement.Refined(DaemonOperationCall.Change(action))
        is DaemonOperationSelection.PublicTool ->
            when (val admitted = PublicToolContract.admit(tool.identity, arguments, catalog)) {
                is Refinement.Refined -> Refinement.Refined(DaemonOperationCall.PublicTool(admitted.value))
                is Refinement.Rejected -> Refinement.Rejected(admitted.failure.wireFailure())
            }
    }

private fun PublicToolInputFailure.wireFailure(): DaemonOperationInputFailure =
    when (this) {
        PublicToolInputFailure.SchemaMismatch -> DaemonOperationInputFailure.SchemaMismatch
        PublicToolInputFailure.SchemaRejected -> DaemonOperationInputFailure.SchemaRejected
        PublicToolInputFailure.SyntaxRejected -> DaemonOperationInputFailure.SyntaxRejected
        is PublicToolInputFailure.SourceAnchor -> DaemonOperationInputFailure.SourceAnchor(cause)
        is PublicToolInputFailure.Parameter -> DaemonOperationInputFailure.Parameter(parameter, rule)
        is PublicToolInputFailure.Constraint ->
            DaemonOperationInputFailure.Constraint(firstParameter, remainingParameters, rule)
    }
