package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.runtime.hosted.HostedEndpointAdvertisement
import io.github.amichne.kast.runtime.hosted.HostedEndpointObserver
import io.github.amichne.kast.runtime.hosted.HostedFrames
import io.github.amichne.kast.runtime.hosted.OwnedHostedEndpoint
import io.github.amichne.kast.runtime.hosted.acceptHostedConnection
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Control survives zero open projects; semantic state never leaves its project service. */
@Service(Service.Level.APP)
class IdeLifecycleApplication(private val scope: CoroutineScope) : Disposable {
    internal val admission = HostedApplicationAdmission()
    private val json = Json { encodeDefaults = true }
    private val state = IdeLifecycleState(UUID.randomUUID())
    private val native =
        IdeLifecycleNative(state) { observation ->
            Logger.getInstance(IdeLifecycleApplication::class.java)
                .info("kast_preparation " + json.encodeToString(NativePreparationObservation.serializer(), observation))
        }

    internal suspend fun <Value> withRegisteredPeer(
        expected: ImpactSemanticBasisDocument.Live,
        action: suspend (Project, CanonicalWorkspaceRoot) -> HostedSemanticReadResult<Value>,
    ): HostedSemanticReadResult<Value> = native.withRegisteredPeer(expected, action)

    internal fun hasActivePreparation(): Boolean = native.hasActivePreparation()

    private val home = Path.of(PathManager.getHomePath()).toRealPath()
    private val runtimeIdentity =
        io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity(
            ideaHome = home.toString(),
            profile = Path.of(PathManager.getConfigPath()).toRealPath().toString(),
            pid = ProcessHandle.current().pid(),
            processStartEpochMillis = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
            hostLifetime = state.host.toString(),
            buildIdentity = io.github.amichne.kast.runtime.hosted.HostedCompatibilityMetadata.buildIdentity,
            autoReload =
                if (System.getProperty("idea.auto.reload.plugins") == "true")
                    io.github.amichne.kast.protocol.contract.HostAutoReload.ENABLED
                else io.github.amichne.kast.protocol.contract.HostAutoReload.DISABLED,
        )
    private val hostRefresh = HostBundleRefreshAdapter(this)
    private val settlement =
        HostedApplicationSettlement(
            admission,
            runtimeIdentity,
            native::settlement,
            native::hasActivePreparation,
            prepareRefresh = { hostRefresh.admit(runtimeIdentity) },
            observe = ::recordHostRefresh,
        )
    private val logger = Logger.getInstance(IdeLifecycleApplication::class.java)
    private val job =
        scope.launch(Dispatchers.IO) {
            if (ApplicationManager.getApplication().isHeadlessEnvironment) return@launch
            if (ApplicationInfo.getInstance().build.baselineVersion != 262) return@launch
            val root =
                when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(home)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return@launch
                }
            val directory =
                OwnedHostedEndpoint.directory(Path.of(System.getProperty("user.home")), root).let {
                    it.parent.parent.resolve("ide-lifecycle").resolve(it.fileName)
                }
            when (
                val endpoint =
                    OwnedHostedEndpoint.open(
                        directory,
                        root,
                        IdeReadHostLifetime.fromBoundary(state.host),
                        advertisement = HostedEndpointAdvertisement.LIFECYCLE,
                        application =
                            io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement(
                                runtimeIdentity,
                                io.github.amichne.kast.runtime.hosted.HostedCompatibilityMetadata.document,
                            ),
                    )
            ) {
                is Refinement.Rejected -> observe(IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLATFORM_UNAVAILABLE))
                is Refinement.Refined ->
                    endpoint.value.use { owner ->
                        val connections = Semaphore(8)
                        coroutineScope {
                            while (isActive) {
                                val client =
                                    when (
                                        val accepted =
                                            acceptHostedConnection(
                                                HostedEndpointObserver { _, _ -> },
                                                accept = owner.server::accept,
                                            )
                                    ) {
                                        is Refinement.Refined -> accepted.value
                                        is Refinement.Rejected -> break
                                    }
                                if (!connections.tryAcquire()) client.close()
                                else
                                    launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                                        val connection = LifecycleConnectionAttempt()
                                        try {
                                            client.use {
                                                withTimeout(LifecycleConnectionAttempt.LIMIT_MILLIS) {
                                                    val reply =
                                                        when (
                                                            val frame = runInterruptible {
                                                                HostedFrames.read(Channels.newInputStream(client))
                                                            }
                                                        ) {
                                                            is Refinement.Rejected ->
                                                                LifecycleReply.immediate(
                                                                    IdeLifecycleResult.Blocked(
                                                                        IdeLifecycleFailure.INVALID_REQUEST
                                                                    )
                                                                )
                                                            is Refinement.Refined -> parse(frame.value, connection)
                                                        }
                                                    val written = reply.write { result ->
                                                        connection.enter(LifecycleConnectionStage.WRITE_FRAME)
                                                        runInterruptible {
                                                            HostedFrames.write(
                                                                Channels.newOutputStream(client),
                                                                json.encodeToString(
                                                                    IdeLifecycleResult.serializer(),
                                                                    result,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                    if (written is Refinement.Rejected)
                                                        observeLifecycleReplyRejection(written.failure)
                                                }
                                            }
                                        } catch (_: TimeoutCancellationException) {
                                            observeConnection(
                                                connection.failure(LifecycleConnectionCause.DEADLINE_EXCEEDED)
                                            )
                                        } catch (cancellation: CancellationException) {
                                            observeConnection(connection.failure(LifecycleConnectionCause.INTERRUPTED))
                                            throw cancellation
                                        } catch (_: java.io.IOException) {
                                            observeConnection(connection.failure(LifecycleConnectionCause.IO_FAILURE))
                                        } finally {
                                            connections.release()
                                        }
                                    }
                            }
                        }
                    }
            }
        }

    init {
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                ProjectManager.TOPIC,
                object : ProjectManagerListener {
                    override fun projectClosed(project: com.intellij.openapi.project.Project) {
                        native.retire(project)
                    }
                },
            )
    }

    private fun observeConnection(observation: LifecycleConnectionFailure) {
        logger.info(
            "kast_lifecycle_connection " + Json.encodeToString(LifecycleConnectionFailure.serializer(), observation)
        )
    }

    private fun parse(raw: String, connection: LifecycleConnectionAttempt): LifecycleReply =
        decodeLifecycleReply(raw, json) { command ->
            connection.dispatch(command)
            if (!lifecycleHostModeSupported()) {
                LifecycleReply.immediate(IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE))
            } else if (command is IdeLifecycleCommand.RefreshHostBundle) {
                applicationHostRefreshReply(command, settlement, scope)
            } else {
                val result = submit(command)
                if (result is IdeLifecycleResult.HostStopRequested)
                    LifecycleReply.afterAcknowledgment(result) {
                        ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit() }
                    }
                else LifecycleReply.immediate(result)
            }
        }

    private fun submit(received: IdeLifecycleCommand): IdeLifecycleResult {
        native.observeProjects()
        if (received is IdeLifecycleCommand.Inspect)
            return inspectApplicationHost(runtimeIdentity, native.inspectProjects(), hostRefresh)
        if (received is IdeLifecycleCommand.SettleHost) return settlement.settle(received)
        if (received is IdeLifecycleCommand.SettleHostForReload) return settlement.settleForReload(received)
        if (received is IdeLifecycleCommand.ResumeHost) return settlement.resume(received)
        if (received is IdeLifecycleCommand.StopHost) return settlement.stop(received)
        if (received is IdeLifecycleCommand.Status) {
            if (received.host != state.host.toString())
                return IdeLifecycleResult.Blocked(IdeLifecycleFailure.WRONG_HOST)
            return state.status(LifecycleRequest(received.requestId))
        }
        return submitAuthorized(received)
    }

    private fun submitAuthorized(received: IdeLifecycleCommand): IdeLifecycleResult {
        val authority =
            if (received is IdeLifecycleCommand.AuthorizedClose) {
                when (val verified = ProjectCloseAuthority.UserDirected.admit(received)) {
                    is Refinement.Rejected -> return IdeLifecycleResult.Blocked(verified.failure)
                    is Refinement.Refined -> verified.value
                }
            } else ProjectCloseAuthority.ManagedCleanup
        val command =
            if (received is IdeLifecycleCommand.AuthorizedClose)
                IdeLifecycleCommand.Close(received.requestId, received.client, received.target)
            else received
        return submitEffect(command, authority)
    }

    private fun submitEffect(command: IdeLifecycleCommand, authority: ProjectCloseAuthority): IdeLifecycleResult {
        val (id, client) =
            when (command) {
                is IdeLifecycleCommand.Open -> command.requestId to command.client
                is IdeLifecycleCommand.Present -> command.requestId to command.client
                is IdeLifecycleCommand.Sync -> command.requestId to command.client
                is IdeLifecycleCommand.ConfigureSync -> command.requestId to command.client
                is IdeLifecycleCommand.Release -> command.requestId to command.client
                is IdeLifecycleCommand.Close -> command.requestId to command.client
                else -> return IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
            }
        if (!TOKEN.matches(id) || !TOKEN.matches(client))
            return IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
        val admitted =
            when (val normalized = normalizeRoot(command)) {
                is Refinement.Refined -> normalized.value
                is Refinement.Rejected -> return IdeLifecycleResult.Blocked(normalized.failure)
            }
        val request = LifecycleRequest(id)
        return when (val submission = state.begin(admitted, request, LifecycleClient(client), authority)) {
            is LifecycleSubmission.Existing -> submission.result
            is LifecycleSubmission.Start -> {
                observe(submission.pending)
                when (val entry = admission.enter()) {
                    HostedApplicationAdmission.Admission.Fenced -> {
                        val rejected = IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_FENCED)
                        state.complete(request, rejected)
                        return rejected
                    }
                    is HostedApplicationAdmission.Admission.Admitted -> executeEffect(admitted, request, entry.lease)
                }
                submission.pending
            }
        }
    }

    private fun normalizeRoot(command: IdeLifecycleCommand): Refinement<IdeLifecycleCommand, IdeLifecycleFailure> =
        if (command is IdeLifecycleCommand.Open)
            when (val root = canonicalLifecycleRoot(command.root)) {
                is Refinement.Refined -> Refinement.Refined(command.copy(root = root.value.value))
                is Refinement.Rejected -> root
            }
        else Refinement.Refined(command)

    // Native failures become finite lifecycle outcomes with bounded stage evidence.
    @Suppress("TooGenericExceptionCaught")
    private fun executeEffect(
        admitted: IdeLifecycleCommand,
        request: LifecycleRequest,
        lease: HostedApplicationAdmission.Lease,
    ) {
        // Application scope owns native work. Peer cancellation never cancels the operation.
        val admittedAtNanos = System.nanoTime()
        scope.launch {
            val result =
                try {
                    native.execute(admitted, admittedAtNanos)
                } catch (_: CancellationException) {
                    IdeLifecycleResult.Blocked(IdeLifecycleFailure.SHUTDOWN)
                } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                    IdeLifecycleResult.Blocked(IdeLifecycleFailure.CANCELLED)
                } catch (failure: RuntimeException) {
                    val origin =
                        failure.stackTrace.take(12).joinToString(",") {
                            "${it.className.take(128)}:${it.methodName.take(128)}:${it.lineNumber}"
                        }
                    logger.warn(
                        "kast_lifecycle_effect stage=EXECUTION outcome=PLATFORM_UNAVAILABLE " +
                            "exception=${failure.javaClass.name.take(128)} " +
                            "origin=$origin"
                    )
                    IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLATFORM_UNAVAILABLE)
                }
            try {
                state.complete(request, result)
                observe(result)
            } finally {
                lease.close()
            }
        }
    }

    private fun observe(result: IdeLifecycleResult) {
        logger.info(
            "kast_lifecycle " +
                json.encodeToString(
                    LifecycleObservation(
                        when (result) {
                            is IdeLifecycleResult.Blocked -> LifecycleObservationOutcome.BLOCKED
                            is IdeLifecycleResult.Pending -> LifecycleObservationOutcome.PENDING
                            else -> LifecycleObservationOutcome.COMPLETE
                        },
                        (result as? IdeLifecycleResult.Blocked)?.reason,
                        (result as? IdeLifecycleResult.Pending)?.stage,
                    )
                )
        )
    }

    override fun dispose() {
        state.shutdown()
        job.cancel()
    }

    internal fun canUnloadHost(): Boolean = settlement.canUnload()

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

@Serializable
private enum class LifecycleObservationOutcome {
    PENDING,
    COMPLETE,
    BLOCKED,
}

@Serializable
private data class LifecycleObservation(
    val outcome: LifecycleObservationOutcome,
    val failure: IdeLifecycleFailure?,
    val stage: IdeLifecycleStage?,
)
