package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.runtime.hosted.HostedEndpointAdvertisement
import io.github.amichne.kast.runtime.hosted.HostedEndpointObserver
import io.github.amichne.kast.runtime.hosted.HostedFrames
import io.github.amichne.kast.runtime.hosted.OwnedHostedEndpoint
import io.github.amichne.kast.runtime.hosted.acceptHostedConnection
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Control survives zero open projects; semantic state never leaves its project service. */
@Service(Service.Level.APP)
class IdeLifecycleApplication(private val scope: CoroutineScope) : Disposable {
    private val json = Json { encodeDefaults = true }
    private val state = IdeLifecycleState(UUID.randomUUID())
    private val native = IdeLifecycleNative(state)
    private val home = Path.of(PathManager.getHomePath()).toRealPath()
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
                                        try {
                                            client.use {
                                                withTimeout(5_000) {
                                                    val result =
                                                        when (
                                                            val frame = runInterruptible {
                                                                HostedFrames.read(Channels.newInputStream(client))
                                                            }
                                                        ) {
                                                            is Refinement.Rejected ->
                                                                IdeLifecycleResult.Blocked(
                                                                    IdeLifecycleFailure.INVALID_REQUEST
                                                                )
                                                            is Refinement.Refined -> parse(frame.value)
                                                        }
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
                                            }
                                        } catch (_: java.io.IOException) {} finally {
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

    private fun parse(raw: String): IdeLifecycleResult =
        try {
            submit(json.decodeFromString<IdeLifecycleCommand>(raw))
        } catch (_: SerializationException) {
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
        } catch (_: IllegalArgumentException) {
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST)
        }

    private fun submit(received: IdeLifecycleCommand): IdeLifecycleResult {
        if (
            com.intellij.idea.AppMode.isRemoteDevHost() ||
                com.intellij.util.PlatformUtils.isJetBrainsClient() ||
                com.intellij.util.PlatformUtils.isGateway()
        )
            return IdeLifecycleResult.Blocked(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE)
        native.observeProjects()
        if (received is IdeLifecycleCommand.Inspect)
            return IdeLifecycleResult.Inspected(
                state.host.toString(),
                home.toString(),
                ApplicationInfo.getInstance().build.asString(),
                state.inspect(),
            )
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
                when (
                    val verified =
                        ProjectCloseAuthority.UserApproved.verify(Path.of(System.getProperty("user.home")), received)
                ) {
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
                executeEffect(admitted, request)
                submission.pending
            }
        }
    }

    private fun normalizeRoot(command: IdeLifecycleCommand): Refinement<IdeLifecycleCommand, IdeLifecycleFailure> =
        if (command is IdeLifecycleCommand.Open)
            when (val root = native.canonicalRoot(command.root)) {
                is Refinement.Refined -> Refinement.Refined(command.copy(root = root.value.value))
                is Refinement.Rejected -> root
            }
        else Refinement.Refined(command)

    // Native failures become finite lifecycle outcomes with bounded stage evidence.
    @Suppress("TooGenericExceptionCaught")
    private fun executeEffect(admitted: IdeLifecycleCommand, request: LifecycleRequest) {
        // Application scope owns native work. Peer cancellation never cancels the operation.
        scope.launch {
            val result =
                try {
                    native.execute(admitted)
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
            state.complete(request, result)
            observe(result)
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

class IdeLifecycleStartup : AppLifecycleListener {
    override fun appStarted() {
        ApplicationManager.getApplication().getService(IdeLifecycleApplication::class.java)
    }
}
