package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.IdeLaunchFailure
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.protocol.contract.ApprovedProjectCloseInvocation
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
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
import java.security.MessageDigest
import java.util.concurrent.Executors
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
class IdeLifecycleClient(private val userHome: Path, private val selectedHome: Path) : WorkspaceLifecycleClient {
    private val json = Json { encodeDefaults = true }

    override fun execute(request: WorkspaceLifecycleRequest, client: String): IdeLifecycleResult =
        when (request) {
            WorkspaceLifecycleRequest.Inspect -> execute(IdeLifecycleCommand.Inspect)
            is WorkspaceLifecycleRequest.Open -> open(request.root, request.requestId, client)
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
                invocation.approval,
            )
        )

    private fun execute(command: IdeLifecycleCommand): IdeLifecycleResult {
        val home =
            try {
                selectedHome.toRealPath()
            } catch (_: java.io.IOException) {
                return blocked(IdeLifecycleFailure.SELECTED_IDE_UNAVAILABLE)
            }
        val host = exchange(home, IdeLifecycleCommand.Inspect)
        if (host !is IdeLifecycleResult.Inspected) return host
        if (host.home != home.toString()) return blocked(IdeLifecycleFailure.HOST_IDENTITY_MISMATCH)
        if (host.protocol != 1) return blocked(IdeLifecycleFailure.UNSUPPORTED_PROTOCOL)
        if (!host.capabilities.containsAll(IdeLifecycleCapabilityName.entries))
            return blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH)
        if (!host.build.substringAfter('-').startsWith("262."))
            return blocked(IdeLifecycleFailure.UNSUPPORTED_PLATFORM_LINE)
        return if (command == IdeLifecycleCommand.Inspect) host else exchange(home, command)
    }

    /** Cold launch is available only to an explicit open; the returned host is live-handshake evidence. */
    fun open(root: String, requestId: String, client: String): IdeLifecycleResult {
        val inspected = execute(IdeLifecycleCommand.Inspect)
        val host =
            if (inspected is IdeLifecycleResult.Blocked && inspected.reason == IdeLifecycleFailure.HOST_UNAVAILABLE)
                coldHost()
            else inspected
        return when (host) {
            is IdeLifecycleResult.Inspected -> execute(IdeLifecycleCommand.Open(host.host, requestId, client, root))
            else -> host
        }
    }

    private fun coldHost(): IdeLifecycleResult {
        val launch =
            when (val selected = SelectedIdeInstallation.resolve(selectedHome)) {
                is SelectedIdeLaunch.Resolved -> selected
                is SelectedIdeLaunch.Unavailable -> return blocked(selected.reason.lifecycleFailure())
            }
        when (running(launch)) {
            Running.PRESENT -> return blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE)
            Running.UNAVAILABLE -> return blocked(IdeLifecycleFailure.PLATFORM_UNAVAILABLE)
            Running.ABSENT -> Unit
        }
        when (val started = launch(launch)) {
            is io.github.amichne.kast.kernel.Refinement.Rejected -> return blocked(started.failure)
            is io.github.amichne.kast.kernel.Refinement.Refined -> Unit
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_WAIT_SECONDS)
        var host = execute(IdeLifecycleCommand.Inspect)
        while (host !is IdeLifecycleResult.Inspected && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            host = execute(IdeLifecycleCommand.Inspect)
        }
        return host
    }

    private fun launch(
        target: SelectedIdeLaunch.Resolved
    ): io.github.amichne.kast.kernel.Refinement<Unit, IdeLifecycleFailure> {
        val process =
            try {
                ProcessBuilder("/usr/bin/open", "-g", "-a", target.bundle, "--args", "nosplash", "dontReopenProjects")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: java.io.IOException) {
                return io.github.amichne.kast.kernel.Refinement.Rejected(IdeLifecycleFailure.LAUNCH_FAILED)
            }
        if (!process.waitFor(LAUNCH_HELPER_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return io.github.amichne.kast.kernel.Refinement.Rejected(IdeLifecycleFailure.LAUNCH_FAILED)
        }
        return if (process.exitValue() == 0) io.github.amichne.kast.kernel.Refinement.Refined(Unit)
        else io.github.amichne.kast.kernel.Refinement.Rejected(IdeLifecycleFailure.LAUNCH_FAILED)
    }

    private fun exchange(home: Path, command: IdeLifecycleCommand): IdeLifecycleResult {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(it)
            }
        val directory = userHome.resolve(".kast/ide-lifecycle/$digest")
        val socket = directory.resolve("host.sock")
        if (!Files.exists(socket)) return blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)
        var ancestor: Path? = directory
        while (ancestor != null) {
            if (Files.isSymbolicLink(ancestor)) return blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)
            ancestor = ancestor.parent
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                val timeout = timer.schedule({ channel.close() }, 5, TimeUnit.SECONDS)
                try {
                    exchangeFrame(channel, socket, command)
                } finally {
                    timeout.cancel(false)
                }
            }
        } catch (_: java.io.IOException) {
            blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)
        } catch (_: SerializationException) {
            blocked(IdeLifecycleFailure.INVALID_REQUEST)
        } catch (_: IllegalArgumentException) {
            blocked(IdeLifecycleFailure.INVALID_REQUEST)
        } finally {
            timer.shutdownNow()
        }
    }

    private fun exchangeFrame(channel: SocketChannel, socket: Path, command: IdeLifecycleCommand): IdeLifecycleResult {
        channel.connect(UnixDomainSocketAddress.of(socket))
        val bytes = json.encodeToString(IdeLifecycleCommand.serializer(), command).toByteArray()
        DataOutputStream(Channels.newOutputStream(channel)).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
        val input = DataInputStream(Channels.newInputStream(channel))
        val size = input.readInt()
        if (size !in 1..MAX_FRAME_BYTES) return blocked(IdeLifecycleFailure.INVALID_REQUEST)
        val response = input.readNBytes(size)
        if (response.size != size) return blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)
        return json.decodeFromString<IdeLifecycleResult>(response.decodeToString(throwOnInvalidSequence = true))
    }

    private enum class Running {
        PRESENT,
        ABSENT,
        UNAVAILABLE,
    }

    private fun running(launch: SelectedIdeLaunch.Resolved): Running {
        // Read native application identity; no activation or accessibility control.
        val script =
            """ObjC.import('AppKit'); function run(argv) { var apps = $.NSWorkspace.sharedWorkspace.runningApplications; for (var i = 0; i < apps.count; i++) { var a = apps.objectAtIndex(i); if (a.bundleURL && ObjC.unwrap(a.bundleURL.path) === argv[0]) return 'present'; } return 'absent'; }"""
        return try {
            val process =
                ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", script, launch.bundle)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!process.waitFor(DISCOVERY_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                Running.UNAVAILABLE
            } else if (process.exitValue() != 0) Running.UNAVAILABLE
            else
                when (process.inputStream.readNBytes(MAX_RUNNING_REPLY).decodeToString().trim()) {
                    "present" -> Running.PRESENT
                    "absent" -> Running.ABSENT
                    else -> Running.UNAVAILABLE
                }
        } catch (_: java.io.IOException) {
            Running.UNAVAILABLE
        }
    }

    private companion object {
        const val LAUNCH_HELPER_SECONDS = 10L
        const val LAUNCH_WAIT_SECONDS = 60L
        const val DISCOVERY_SECONDS = 5L
        const val POLL_MILLIS = 100L
        const val MAX_FRAME_BYTES = 1_048_576
        const val MAX_RUNNING_REPLY = 32
    }

    private fun blocked(reason: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(reason)
}

private fun IdeLaunchFailure.lifecycleFailure(): IdeLifecycleFailure =
    when (this) {
        IdeLaunchFailure.METADATA_UNAVAILABLE -> IdeLifecycleFailure.LAUNCH_METADATA_UNAVAILABLE
        IdeLaunchFailure.INVALID_METADATA -> IdeLifecycleFailure.LAUNCH_METADATA_INVALID
        IdeLaunchFailure.UNSUPPORTED_PLATFORM_LINE -> IdeLifecycleFailure.UNSUPPORTED_PLATFORM_LINE
        IdeLaunchFailure.MISSING_LAUNCHER -> IdeLifecycleFailure.LAUNCHER_MISSING
        IdeLaunchFailure.AMBIGUOUS_LAUNCHER -> IdeLifecycleFailure.LAUNCHER_AMBIGUOUS
        IdeLaunchFailure.INVALID_LAUNCHER -> IdeLifecycleFailure.LAUNCHER_INVALID
        IdeLaunchFailure.EXECUTABLE_UNAVAILABLE -> IdeLifecycleFailure.LAUNCH_EXECUTABLE_UNAVAILABLE
    }
