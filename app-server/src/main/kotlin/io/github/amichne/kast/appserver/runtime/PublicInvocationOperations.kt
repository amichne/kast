package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AdmittedPublicInvocation
import io.github.amichne.kast.appserver.AdmittedRuntimeSettings
import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.CoordinatorOperationRequest
import io.github.amichne.kast.appserver.CoordinatorRequestExecutor
import io.github.amichne.kast.appserver.DaemonManagementTarget
import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationInputFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.PublicInvocationCoordinator
import io.github.amichne.kast.appserver.PublicInvocationFailure
import io.github.amichne.kast.appserver.PublicInvocationInput
import io.github.amichne.kast.appserver.PublicInvocationOutcome
import io.github.amichne.kast.appserver.PublicInvocationProtocol
import io.github.amichne.kast.appserver.PublicInvocationReply
import io.github.amichne.kast.appserver.PublicInvocationRequest
import io.github.amichne.kast.appserver.PublicInvocations
import io.github.amichne.kast.appserver.RequestedRuntimeSettings
import io.github.amichne.kast.appserver.admitCallResponse
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One public shell invocation per connection. Envelope admission, lifecycle availability and the shared operation lease
 * precede the executor, so every rejection here is free of workspace effects. A disconnect cancels the invocation; the
 * frontend then owns the unobserved outcome. A requested input that misses its deadline cancels the invocation too.
 */
internal class PublicInvocationOperations(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val admissions: OperationAdmissions,
    private val invocations: PublicInvocations,
    private val semantic: CoordinatorRequestExecutor,
) {
    private val coordinator = PublicInvocationCoordinator(target.serviceGeneration, ProcessHandle.current().pid())

    suspend fun handle(session: DefaultWebSocketServerSession) {
        val frame =
            withTimeoutOrNull(BrokerOperationalLimits.workerControlHandshake.value) {
                session.incoming.receiveCatching().getOrNull()
            } ?: return
        val lease = OperationLease(admissions)
        try {
            val reply = exchange(session, frame, lease) ?: return
            session.send(encode(reply))
        } finally {
            lease.release()
        }
    }

    private suspend fun exchange(
        session: DefaultWebSocketServerSession,
        frame: Frame,
        lease: OperationLease,
    ): PublicInvocationReply? {
        val invocation =
            when (val admitted = admitInvocation(frame)) {
                is Refinement.Rejected -> return PublicInvocationReply.Rejected(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        if (!available() || !lease.acquire())
            return PublicInvocationReply.Rejected(PublicInvocationFailure.LIFECYCLE_TRANSITION)
        val input = FrontendInput()
        // A cancelled worker may still fail with its own exception, such as an interrupted input read. Supervision
        // keeps
        // that failure from aborting the reply this exchange already decided.
        return supervisorScope {
            val work =
                async(Dispatchers.IO) {
                    runInterruptible {
                        invocations.executor.invoke(
                            invocation,
                            { installation, environment ->
                                InProcessCoordinatorClient(target, semantic, installation, environment)
                            },
                            input,
                        )
                    }
                }
            val deadline = InputDeadline(this, invocations.inputLimit.value)
            try {
                pump(session, work, input, deadline)
            } finally {
                deadline.stop()
                work.cancel()
            }
        }
    }

    /**
     * Relays the one optional input request until the invocation completes or the connection ends. The input deadline
     * runs only while the requested input is outstanding.
     */
    private suspend fun pump(
        session: DefaultWebSocketServerSession,
        work: Deferred<PublicInvocationOutcome>,
        input: FrontendInput,
        deadline: InputDeadline,
    ): PublicInvocationReply? {
        while (true) {
            when (val step = next(session, work, input, deadline)) {
                is InvocationStep.Finished -> return completed(step.outcome)
                is InvocationStep.InputRequested -> {
                    deadline.start()
                    session.send(encode(PublicInvocationReply.InputRequired(step.maximumBytes)))
                }
                InvocationStep.InputExpired ->
                    return PublicInvocationReply.Rejected(PublicInvocationFailure.INPUT_EXPIRED)
                // Closure, or any frame other than the single requested input, cancels the invocation.
                is InvocationStep.Received -> if (!deliver(step.frame, input, deadline)) return null
            }
        }
    }

    private suspend fun next(
        session: DefaultWebSocketServerSession,
        work: Deferred<PublicInvocationOutcome>,
        input: FrontendInput,
        deadline: InputDeadline,
    ): InvocationStep {
        val expiry = deadline.expiry
        return select {
            work.onAwait { InvocationStep.Finished(it) }
            if (!deadline.started) input.requested.onAwait { InvocationStep.InputRequested(it) }
            expiry?.onAwait { InvocationStep.InputExpired }
            session.incoming.onReceiveCatching { InvocationStep.Received(it) }
        }
    }

    private fun completed(outcome: PublicInvocationOutcome) =
        PublicInvocationReply.Completed(coordinator, outcome.exitCode, outcome.output, outcome.diagnostics)

    private fun deliver(frame: ChannelResult<Frame>, input: FrontendInput, deadline: InputDeadline): Boolean {
        val received = frame.getOrNull()
        val delivered = received is Frame.Binary && input.deliver(received.readBytes())
        if (delivered) deadline.stop()
        return delivered
    }

    private sealed interface InvocationStep {
        class Finished(val outcome: PublicInvocationOutcome) : InvocationStep

        class InputRequested(val maximumBytes: Int) : InvocationStep

        data object InputExpired : InvocationStep

        class Received(val frame: ChannelResult<Frame>) : InvocationStep
    }
}

/** The timer for one requested input. It starts once, on the request, and stops on delivery or exchange end. */
private class InputDeadline(private val scope: CoroutineScope, private val milliseconds: Long) {
    var started = false
        private set

    var expiry: Deferred<Unit>? = null
        private set

    fun start() {
        started = true
        expiry = scope.async { delay(milliseconds) }
    }

    fun stop() {
        expiry?.cancel()
        expiry = null
    }
}

/** The requested input bound travels to the frontend; a frame beyond it or a second frame is a protocol violation. */
private class FrontendInput : PublicInvocationInput {
    val requested = CompletableDeferred<Int>()
    private val delivered = CompletableFuture<ByteArray>()

    override fun read(maximumBytes: Int): ByteArray {
        if (maximumBytes < 0 || !requested.complete(maximumBytes)) throw IOException("input already requested")
        // Only deliver completes this future, so cancellation is its one failure.
        return try {
            delivered.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("invocation cancelled while awaiting input")
        }
    }

    fun deliver(bytes: ByteArray): Boolean {
        val limit = if (requested.isCompleted) requested.getCompleted() else return false
        return bytes.size <= limit && delivered.complete(bytes)
    }
}

@Serializable private data class InvocationVersion(@Required val version: Int)

private val versionJson = Json { ignoreUnknownKeys = true }

/** The version is admitted first, so an incompatible frontend receives that reason rather than a shape failure. */
internal fun admitInvocation(frame: Frame): Refinement<AdmittedPublicInvocation, PublicInvocationFailure> {
    val text = (frame as? Frame.Text)?.readText() ?: return Refinement.Rejected(PublicInvocationFailure.INVALID_REQUEST)
    if (text.toByteArray(Charsets.UTF_8).size > PublicInvocationProtocol.maximumRequestBytes)
        return Refinement.Rejected(PublicInvocationFailure.CAPACITY_EXCEEDED)
    val request =
        try {
            if (versionJson.decodeFromString<InvocationVersion>(text).version != PublicInvocationProtocol.version)
                return Refinement.Rejected(PublicInvocationFailure.UNSUPPORTED_VERSION)
            PublicInvocationProtocol.json.decodeFromString<PublicInvocationRequest>(text)
        } catch (_: SerializationException) {
            return Refinement.Rejected(PublicInvocationFailure.INVALID_REQUEST)
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(PublicInvocationFailure.INVALID_REQUEST)
        }
    val directory =
        try {
            Path.of(request.directory)
        } catch (_: InvalidPathException) {
            return Refinement.Rejected(PublicInvocationFailure.INVALID_REQUEST)
        }
    return AdmittedPublicInvocation.admit(directory, request.arguments, request.environment)?.let {
        Refinement.Refined(it)
    } ?: Refinement.Rejected(PublicInvocationFailure.INVALID_REQUEST)
}

private fun encode(reply: PublicInvocationReply): String =
    PublicInvocationProtocol.json.encodeToString(PublicInvocationReply.serializer(), reply)

/**
 * The coordinator's own executor reached without a socket. It applies the semantic route's runtime-settings admission
 * and result projection, and the socket client's reply admission, so outcomes match a remote call exactly.
 */
internal class InProcessCoordinatorClient(
    private val target: DaemonManagementTarget,
    private val executor: CoordinatorRequestExecutor,
    private val installation: Path,
    private val environment: Map<String, String>,
) : DaemonOperationClient {
    override fun read(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult {
        val requested = RequestedRuntimeSettings.fromWorkspace(installation, root.path, environment)
        val settings =
            when (val admitted = AdmittedRuntimeSettings.admit(requested.wire)) {
                is Refinement.Rejected ->
                    return DaemonOperationResult.Rejected(
                        DaemonOperationClientRejection.Server(
                            DaemonOperationFailure.Input(DaemonOperationInputFailure.RuntimeSettings(admitted.failure))
                        )
                    )
                is Refinement.Refined -> admitted.value
            }
        val result = runBlocking { executor.execute(CoordinatorOperationRequest(root, call, settings)) }
        return admitCallResponse(result.response(target, root), target, root, call)
    }
}
