package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AdmittedRuntimeSettings
import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.CoordinatorExecution
import io.github.amichne.kast.appserver.CoordinatorOperationRequest
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
import io.github.amichne.kast.appserver.DaemonRuntimeSettingsFailure
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/**
 * One request per connection. A disconnect cancels that request's execution. Output presentation, timeouts and
 * cancellation stay with the caller; only stated runtime settings cross this boundary.
 */
internal class CoordinatorOperations(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val execution: CoordinatorExecution?,
) {
    private val admissions = OperationAdmissions()

    suspend fun handle(session: DefaultWebSocketServerSession) {
        val frame =
            withTimeoutOrNull(BrokerOperationalLimits.workerControlHandshake.value) {
                session.incoming.receiveCatching().getOrNull()
            } ?: return
        val lease = OperationLease(admissions)
        try {
            val response =
                coroutineScope {
                    val invocation = async { exchange(frame, lease) }
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
        } finally {
            lease.release()
        }
    }

    /**
     * Closes admission, then waits for admitted operations to deliver or abandon their replies. Remaining operations
     * are cancelled by the server shutdown that follows; their callers observe an unobserved outcome.
     */
    suspend fun settle(allowance: Long): OperationSettlement =
        if (withTimeoutOrNull(allowance) { admissions.close().await() } == null) OperationSettlement.UNSETTLED
        else OperationSettlement.SETTLED

    private suspend fun exchange(frame: Frame, lease: OperationLease): DaemonOperationResponse {
        val request =
            when (val admitted = admitFrame(frame)) {
                is Refinement.Rejected -> return reject(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        val settings =
            when (val admitted = AdmittedRuntimeSettings.admit(request.settings)) {
                is Refinement.Rejected -> return settingsRejected(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        val execute = execution ?: return reject(DaemonOperationProtocolFailure.UNAVAILABLE)
        if (execute is CoordinatorExecution.LaunchBound) {
            val conflicts = settings.conflictsWith(execute.launch)
            if (conflicts.isNotEmpty()) return settingsRejected(DaemonRuntimeSettingsFailure.Conflict(conflicts))
        }
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
        if (!available() || !lease.acquire()) return reject(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
        val result =
            when (execute) {
                is CoordinatorExecution.LaunchBound -> execute.executor.execute(root, call)
                is CoordinatorExecution.RequestBound ->
                    execute.executor.execute(CoordinatorOperationRequest(root, call, settings))
            }
        return result.response(root)
    }

    private fun admitFrame(frame: Frame): Refinement<DaemonOperationRequest, DaemonOperationProtocolFailure> {
        val text =
            (frame as? Frame.Text)?.readText()
                ?: return Refinement.Rejected(DaemonOperationProtocolFailure.INVALID_REQUEST)
        if (text.toByteArray(Charsets.UTF_8).size > DaemonOperationProtocol.maximumRequestBytes)
            return Refinement.Rejected(DaemonOperationProtocolFailure.CAPACITY_EXCEEDED)
        val request =
            try {
                DaemonOperationProtocol.json.decodeFromString<DaemonOperationRequest>(text)
            } catch (_: SerializationException) {
                return Refinement.Rejected(DaemonOperationProtocolFailure.INVALID_REQUEST)
            }
        if (request.version != DaemonOperationProtocol.version)
            return Refinement.Rejected(DaemonOperationProtocolFailure.UNSUPPORTED_VERSION)
        if (request.target != target) return Refinement.Rejected(DaemonOperationProtocolFailure.IDENTITY_REJECTED)
        return Refinement.Refined(request)
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

internal enum class OperationSettlement {
    SETTLED,
    UNSETTLED,
}

/** Admission and closure share one monitor, so no operation is admitted after settlement begins. */
private class OperationAdmissions {
    private var active = 0
    private var closed = false
    private val settled = CompletableDeferred<Unit>()

    @Synchronized
    fun admit(): Boolean {
        if (closed) return false
        active++
        return true
    }

    @Synchronized
    fun release() {
        active--
        if (closed && active == 0) settled.complete(Unit)
    }

    @Synchronized
    fun close(): CompletableDeferred<Unit> {
        closed = true
        if (active == 0) settled.complete(Unit)
        return settled
    }
}

/** One admitted operation per connection, released after its reply is delivered or abandoned. */
private class OperationLease(private val admissions: OperationAdmissions) {
    private val held = AtomicBoolean(false)

    fun acquire(): Boolean = admissions.admit().also { held.set(it) }

    fun release() {
        if (held.compareAndSet(true, false)) admissions.release()
    }
}

private fun encode(response: DaemonOperationResponse): String =
    DaemonOperationProtocol.json.encodeToString(DaemonOperationResponse.serializer(), response)

private fun settingsRejected(reason: DaemonRuntimeSettingsFailure) =
    DaemonOperationResponse.Rejected(DaemonOperationFailure.Input(DaemonOperationInputFailure.RuntimeSettings(reason)))

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
        DaemonOperationSelection.WorkspaceStatus -> Refinement.Refined(DaemonOperationCall.WorkspaceStatus)
        DaemonOperationSelection.PrepareWorkspace -> Refinement.Refined(DaemonOperationCall.PrepareWorkspace)
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
