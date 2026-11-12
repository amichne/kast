package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ApprovedProjectCloseInvocation
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeStage
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface WorkspaceLifecycleClient {
    fun execute(request: WorkspaceLifecycleRequest, client: String): IdeLifecycleResult

    fun approvedClose(invocation: ApprovedProjectCloseInvocation, client: String): IdeLifecycleResult

    data object Unavailable : WorkspaceLifecycleClient {
        override fun execute(request: WorkspaceLifecycleRequest, client: String) =
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)

        override fun approvedClose(invocation: ApprovedProjectCloseInvocation, client: String) =
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
    }
}

/** Explicit control client. Ordinary semantic clients never call this boundary. */
class IdeLifecycleClient
internal constructor(
    private val userHome: Path,
    private val selectedHome: Path,
    exchangeTimer: LifecycleExchangeTimer,
    nanoTime: () -> Long,
    private val platform: IdeLaunchPlatform = IdeLaunchPlatform.Native,
    private val deployment: IdeLifecycleDeployment,
) : WorkspaceLifecycleClient {
    constructor(
        userHome: Path,
        selectedHome: Path,
        deployment: IdeLifecycleDeployment,
    ) : this(
        userHome = userHome,
        selectedHome = selectedHome,
        exchangeTimer = LifecycleExchangeTimer.Native,
        nanoTime = System::nanoTime,
        deployment = deployment,
    )

    private val transport = LifecycleSocketExchange(userHome, exchangeTimer, nanoTime)

    /** Installation management uses the same admitted application endpoint without project preparation. */
    fun manageInstallation(command: IdeLifecycleCommand): IdeLifecycleResult =
        manageInspectedInstallation(inspectForInstallation(), command, transport::exchange)

    private fun exchange(home: Path, command: IdeLifecycleCommand): IdeLifecycleResult =
        transport.exchange(home, command)

    /** Starts or reuses the selected application without opening a project or granting readiness. */
    fun startInstallation(): IdeLifecycleResult {
        val inspected = execute(IdeLifecycleCommand.Inspect)
        return if (inspected is IdeLifecycleResult.Blocked && inspected.reason == IdeLifecycleFailure.HOST_UNAVAILABLE)
            coldHost()
        else inspected
    }

    /** Prior-release inspection preserves its stated capabilities without granting new management operations. */
    fun inspectForInstallation(): IdeLifecycleResult {
        val home =
            try {
                selectedHome.toRealPath()
            } catch (_: java.io.IOException) {
                return blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
            }
        val selectedDeployment =
            when (val selected = selectLifecycleDeployment(deployment, userHome, selectedHome)) {
                is Refinement.Rejected -> return blocked(selected.failure)
                is Refinement.Refined -> selected.value
            }
        val result = exchange(home, IdeLifecycleCommand.Inspect)
        if (result is IdeLifecycleResult.Inspected) {
            when (val admitted = admitLifecycleHost(selectedDeployment, result)) {
                is Refinement.Rejected -> return blocked(admitted.failure)
                is Refinement.Refined -> Unit
            }
        }
        return if (result is IdeLifecycleResult.Inspected && (result.home != home.toString() || result.protocol != 1))
            blocked(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
        else result
    }

    override fun execute(request: WorkspaceLifecycleRequest, client: String): IdeLifecycleResult =
        when (request) {
            WorkspaceLifecycleRequest.Inspect -> execute(IdeLifecycleCommand.Inspect)
            is WorkspaceLifecycleRequest.Open ->
                open(
                    root = request.root,
                    requestId = request.requestId,
                    client = client,
                    preparationMillis = request.preparationMillis,
                )
            is WorkspaceLifecycleRequest.Present ->
                execute(IdeLifecycleCommand.Present(request.requestId, client, request.target))
            is WorkspaceLifecycleRequest.Release ->
                execute(IdeLifecycleCommand.Release(request.requestId, client, request.target))
            is WorkspaceLifecycleRequest.Close ->
                execute(IdeLifecycleCommand.Close(request.requestId, client, request.target))
            is WorkspaceLifecycleRequest.RequestUserClose -> blocked(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)
            is WorkspaceLifecycleRequest.Status -> execute(IdeLifecycleCommand.Status(request.host, request.requestId))
        }

    override fun approvedClose(invocation: ApprovedProjectCloseInvocation, client: String): IdeLifecycleResult =
        execute(
            IdeLifecycleCommand.AuthorizedClose(
                invocation.arguments.requestId,
                client,
                invocation.arguments.target,
                invocation.confirmation,
            )
        )

    private fun execute(command: IdeLifecycleCommand): IdeLifecycleResult {
        val home =
            try {
                selectedHome.toRealPath()
            } catch (_: java.io.IOException) {
                return blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
            }
        val selectedDeployment =
            when (val selected = selectLifecycleDeployment(deployment, userHome, selectedHome)) {
                is Refinement.Rejected -> return blocked(selected.failure)
                is Refinement.Refined -> selected.value
            }
        val host = exchange(home, IdeLifecycleCommand.Inspect)
        if (host !is IdeLifecycleResult.Inspected) return host
        when (val admitted = admitWorkspaceLifecycleHost(home, host, command, selectedDeployment)) {
            is Refinement.Rejected -> return blocked(admitted.failure)
            is Refinement.Refined -> Unit
        }
        return if (command == IdeLifecycleCommand.Inspect) host else exchange(home, command)
    }

    /** Cold launch is available only to an explicit open; the returned host is live-handshake evidence. */
    fun open(
        root: String,
        requestId: String,
        client: String,
        preparationMillis: Long? = io.github.amichne.kast.protocol.contract.WorkspacePreparationBudget.limit.value,
    ): IdeLifecycleResult {
        val limit =
            when (val admitted = io.github.amichne.kast.kernel.ElapsedTimeAllowance.admitMillis(preparationMillis)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
            }
        val deadline =
            io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadline.start(limit, System.nanoTime())
        val inspected = execute(IdeLifecycleCommand.Inspect)
        val host =
            if (inspected is IdeLifecycleResult.Blocked && inspected.reason == IdeLifecycleFailure.HOST_UNAVAILABLE)
                coldHost(requireWorkspaceTrust = true)
            else inspected
        return when (host) {
            is IdeLifecycleResult.Inspected ->
                when (val remaining = deadline.remaining(System.nanoTime())) {
                    is Refinement.Refined ->
                        exchange(
                            Path.of(host.home),
                            IdeLifecycleCommand.Open(
                                host = host.host,
                                requestId = requestId,
                                client = client,
                                root = root,
                                preparationMillis = remaining.value.protocolMillis(),
                            ),
                        )
                    is Refinement.Rejected -> blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
                }
            else -> host
        }
    }

    private fun coldHost(requireWorkspaceTrust: Boolean = false): IdeLifecycleResult {
        val selectedDeployment =
            when (val selected = selectLifecycleDeployment(deployment, userHome, selectedHome)) {
                is Refinement.Rejected -> return blocked(selected.failure)
                is Refinement.Refined -> selected.value
            }
        // Native directory locking owns exclusivity for the admitted private profile.
        // Managed launch never enumerates unrelated processes. Only a live handshake grants admission.
        val lifetime =
            when (val started = launch(selectedDeployment, requireWorkspaceTrust)) {
                is Refinement.Rejected -> return blocked(started.failure)
                is Refinement.Refined -> started.value
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_WAIT_SECONDS)
        var host = execute(IdeLifecycleCommand.Inspect)
        while (host !is IdeLifecycleResult.Inspected && System.nanoTime() < deadline) {
            if (lifetime is IdeLaunchLifetime.Application && !lifetime.process.isAlive)
                return blocked(IdeLifecycleFailure.LAUNCH_FAILED)
            Thread.sleep(POLL_MILLIS)
            host = execute(IdeLifecycleCommand.Inspect)
        }
        return host
    }

    private fun launch(
        selectedDeployment: SelectedLifecycleDeployment,
        requireWorkspaceTrust: Boolean,
    ): Refinement<IdeLaunchLifetime, IdeLifecycleFailure> =
        when (selectedDeployment) {
            is SelectedLifecycleDeployment.Headless -> {
                val trust = selectedDeployment.installation.config.resolve("options/trusted-paths.xml")
                if (requireWorkspaceTrust && !Files.isRegularFile(trust, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    Refinement.Rejected(IdeLifecycleFailure.TRUST_REQUIRED)
                else launchManagedHeadless(selectedDeployment.installation, platform)
            }
        }

    private companion object {
        const val LAUNCH_WAIT_SECONDS = 60L
        const val POLL_MILLIS = 100L
    }

    private fun blocked(reason: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(reason)
}

internal class LifecycleSocketExchange(
    private val userHome: Path,
    private val exchangeTimer: LifecycleExchangeTimer = LifecycleExchangeTimer.Native,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val json = Json { encodeDefaults = true }

    fun exchange(home: Path, command: IdeLifecycleCommand): IdeLifecycleResult {
        val attempt = LifecycleExchangeAttempt(command, nanoTime)
        val directory =
            io.github.amichne.kast.distribution.contract.HostedEndpointPaths.directory(
                userHome,
                io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.LIFECYCLE,
                home.toString(),
            )
        val socket = directory.resolve("host.sock")
        if (!Files.exists(socket)) return attempt.reject(IdeLifecycleExchangeCause.SOCKET_MISSING)
        var ancestor: Path? = directory
        while (ancestor != null) {
            if (Files.isSymbolicLink(ancestor)) return attempt.reject(IdeLifecycleExchangeCause.SYMBOLIC_PATH)
            ancestor = ancestor.parent
        }
        return try {
            val result =
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    exchangeTimer
                        .schedule { attempt.expire { channel.close() } }
                        .use {
                            exchangeFrame(channel, socket, command, attempt)
                        }
                }
            attempt.complete(result)
        } catch (failure: java.io.IOException) {
            attempt.reject(failure.exchangeCause())
        } catch (_: SerializationException) {
            attempt.reject(IdeLifecycleExchangeCause.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            attempt.reject(IdeLifecycleExchangeCause.INVALID_FRAME)
        }
    }

    private fun exchangeFrame(
        channel: SocketChannel,
        socket: Path,
        command: IdeLifecycleCommand,
        attempt: LifecycleExchangeAttempt,
    ): IdeLifecycleResult {
        attempt.enter(IdeLifecycleExchangeStage.CONNECT)
        channel.connect(UnixDomainSocketAddress.of(socket))
        attempt.enter(IdeLifecycleExchangeStage.WRITE_REQUEST)
        val bytes = json.encodeToString(IdeLifecycleCommand.serializer(), command).toByteArray()
        DataOutputStream(Channels.newOutputStream(channel)).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
        val input = DataInputStream(Channels.newInputStream(channel))
        attempt.enter(IdeLifecycleExchangeStage.READ_LENGTH)
        val size = input.readInt()
        if (size !in 1..MAX_FRAME_BYTES) return attempt.reject(IdeLifecycleExchangeCause.INVALID_FRAME)
        attempt.enter(IdeLifecycleExchangeStage.READ_BODY)
        val response = input.readNBytes(size)
        if (response.size != size) return attempt.reject(IdeLifecycleExchangeCause.TRUNCATED_RESPONSE)
        attempt.enter(IdeLifecycleExchangeStage.DECODE)
        return json.decodeFromString<IdeLifecycleResult>(response.decodeToString(throwOnInvalidSequence = true))
    }

    private companion object {
        const val MAX_FRAME_BYTES = 1_048_576
    }
}

internal sealed interface IdeLaunchLifetime {
    data class Application(val process: Process) : IdeLaunchLifetime
}
